package com.example.despertate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.despertate.core.DrowsinessEngine
import com.example.despertate.core.EngineOutput
import com.example.despertate.core.GrayRegion
import com.example.despertate.core.SunglassesDetector
import com.example.despertate.core.SunglassesMode
import com.example.despertate.core.FaceLandmarks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dueño de la cámara, MediaPipe, el motor de detección y la alarma.
 * Corre en primer plano (tipo cámara) para seguir funcionando con la ventana flotante cerrada,
 * con otra app encima o con la pantalla bloqueada. La pantalla (MainActivity) solo muestra resultados.
 */
class DetectionService : Service(), LifecycleOwner, FaceLandmarkerHelper.Listener {

    data class Update(
        val out: EngineOutput,
        val face: FaceLandmarks?,
        val frame: Bitmap?,
        val width: Int,
        val height: Int,
        val inferMs: Long,
        val totalMs: Long,
        val gamma: Double,
        val refLum: Double,
        val sgRatio: Double,
        val claheOn: Boolean,
    )

    interface UiListener {
        fun onUpdate(u: Update)
        fun onMessage(message: String)
        fun onStopped()
    }

    inner class LocalBinder : Binder() {
        val service: DetectionService get() = this@DetectionService
    }

    private val binder = LocalBinder()
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    val engine = DrowsinessEngine()
    var alarm: AlarmPlayer? = null
        private set
    var helper: FaceLandmarkerHelper? = null
        private set

    private var executor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraStarted = false
    private var orientationListener: OrientationEventListener? = null
    private var lastRotation = -1

    @Volatile
    private var ui: UiListener? = null

    @Volatile
    private var testAlarmUntil = 0L

    // Tratamiento digital de imágenes (interruptores)
    @Volatile
    var claheEnabled = false
        private set
    @Volatile private var lastFaceBox: IntArray? = null
    @Volatile private var lastGamma = 1.0
    @Volatile private var lastLum = 0.0
    @Volatile private var lastSgRatio = 1.0

    /** Cuadro original (sin CLAHE) de cada análisis en curso, para medir lentes de sol sobre la imagen real. */
    private val originals = ConcurrentHashMap<Long, Bitmap>()

    // Diagnóstico visible en pantalla
    @Volatile var lastMessage: String? = null
        private set
    @Volatile private var stage = "creando"
    @Volatile private var framesIn = 0
    @Volatile private var framesDropped = 0
    @Volatile private var results = 0

    fun diagnostics(): String =
        "Servicio: $stage\n" +
            "Detector: ${helper?.delegateName ?: "-"} listo=${helper?.isReady}\n" +
            "Cuadros: recibidos=$framesIn descartados=$framesDropped resultados=$results\n" +
            "Ultimo aviso: ${lastMessage ?: "ninguno"}"

    override fun onCreate() {
        super.onCreate()
        try {
            alarm = AlarmPlayer(this)
            executor = Executors.newSingleThreadExecutor()
            registry.currentState = Lifecycle.State.STARTED
            startOrientationTracking()
            stage = "creado, creando detector"
            helper = FaceLandmarkerHelper(this, this)
            stage = "detector creado"
        } catch (e: Throwable) {
            stage = "ERROR en onCreate: ${e.message}"
            postMessage(stage)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDetection()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            stage = "en primer plano"
        } catch (e: Throwable) {
            stage = "ERROR startForeground: ${e.message}"
            postMessage(stage)
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despertate:deteccion").apply {
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        if (!cameraStarted) {
            cameraStarted = true
            startCamera()
        }
        return START_NOT_STICKY
    }

    // ---- API para la pantalla ----

    fun setUi(listener: UiListener?) {
        ui = listener
        helper?.wantFrames = listener != null
    }

    fun toggleClahe(): Boolean {
        claheEnabled = !claheEnabled
        return claheEnabled
    }

    fun cycleSunglassesMode(): SunglassesMode {
        val next = when (engine.sunglassesMode) {
            SunglassesMode.AUTO -> SunglassesMode.ON
            SunglassesMode.ON -> SunglassesMode.OFF
            SunglassesMode.OFF -> SunglassesMode.AUTO
        }
        engine.sunglassesMode = next
        return next
    }

    fun recalibrate() {
        alarm?.stop()
        engine.recalibrate()
    }

    /** Prueba solo el audio, sin depender de la detección. Devuelve un texto para mostrar. */
    fun testAlarm(ms: Long): String {
        val a = alarm ?: return "Alarma no disponible"
        testAlarmUntil = SystemClock.uptimeMillis() + ms
        a.setActive(true)
        return a.lastError ?: "Sonando ${ms / 1000} s | Volumen de alarma: ${a.volumePercent()}%"
    }

    fun stopDetection() {
        ui?.onStopped()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        alarm?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Rotación según la orientación física del celu ----

    private fun startOrientationTracking() {
        val l = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rot != lastRotation) {
                    lastRotation = rot
                    imageAnalysis?.targetRotation = rot
                }
            }
        }
        orientationListener = l
        if (l.canDetectOrientation()) l.enable()
    }

    // ---- Cámara ----

    private fun startCamera() {
        stage = "abriendo cámara"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider)
            } catch (e: Throwable) {
                stage = "ERROR cámara: ${e.message}"
                postMessage(stage)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val rotation = if (lastRotation >= 0) lastRotation else
            (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

        // Sin vista previa en vivo: se muestra el cuadro analizado, así imagen y landmarks coinciden.
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(executor ?: return) { image -> analyze(image) }
        imageAnalysis = analysis
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            stage = "cámara abierta"
        } catch (e: Throwable) {
            stage = "ERROR al abrir cámara frontal: ${e.message}"
            postMessage(stage)
        }
    }

    private fun analyze(image: ImageProxy) {
        framesIn++
        val h = helper
        // Si MediaPipe todavía está con el cuadro anterior, este se descarta sin convertirlo.
        if (h == null || !h.isReady || h.isBusy) {
            framesDropped++
            image.close()
            return
        }
        val rotationDegrees = image.imageInfo.rotationDegrees
        val sensorTsMs = image.imageInfo.timestamp / 1_000_000L
        val bitmap = image.toBitmap()
        image.close()
        val rotated = if (rotationDegrees == 0) {
            bitmap
        } else {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        // TDI opcional: gamma adaptativo + CLAHE antes de MediaPipe
        var input = rotated
        if (claheEnabled) {
            try {
                val r = ImagePreprocessor.process(rotated, lastFaceBox)
                input = r.bitmap
                lastGamma = r.gamma
                lastLum = r.refLum
            } catch (e: Throwable) {
                postMessage("Error en CLAHE: ${e.message}")
            }
        } else {
            lastGamma = 1.0
        }
        if (originals.size > 8) originals.clear()
        originals[sensorTsMs] = rotated
        h.detect(input, sensorTsMs)
    }

    // ---- Resultados de MediaPipe (hilo de MediaPipe) ----

    override fun onResult(
        face: FaceLandmarks?,
        frame: Bitmap?,
        width: Int,
        height: Int,
        inferMs: Long,
        totalMs: Long,
        sensorTsMs: Long,
    ) {
        results++
        val nowSec = SystemClock.elapsedRealtimeNanos() / 1e9
        val original = originals.remove(sensorTsMs)
        originals.entries.removeIf { it.key < sensorTsMs }
        if (face != null) lastFaceBox = faceBox(face, width, height)
        val probe: ((Double) -> Boolean)? = if (face != null && original != null) {
            { yaw ->
                val r = SunglassesDetector.detect(face, yaw, original.width, original.height, grayReader(original))
                lastSgRatio = r.ratio
                r.isDarkLens
            }
        } else {
            null
        }
        val out = engine.process(face, nowSec, probe)
        val claheOn = claheEnabled
        val gamma = lastGamma
        val lum = lastLum
        val sg = lastSgRatio
        mainHandler.post {
            // La alarma se maneja en el hilo principal, con o sin pantalla.
            alarm?.setActive(out.alarm || SystemClock.uptimeMillis() < testAlarmUntil)
            ui?.onUpdate(Update(out, face, frame, width, height, inferMs, totalMs, gamma, lum, sg, claheOn))
        }
    }

    private fun faceBox(f: FaceLandmarks, w: Int, h: Int): IntArray {
        val xs = listOf(f.jawRight.x, f.jawLeft.x)
        val topY = (f.rightBrow + f.leftBrow).minOfOrNull { it.y } ?: f.noseBridge.y
        val faceH = f.chin.y - topY
        return intArrayOf(
            xs.min().toInt().coerceIn(0, w),
            (topY - 0.15 * faceH).toInt().coerceIn(0, h),
            xs.max().toInt().coerceIn(0, w),
            f.chin.y.toInt().coerceIn(0, h),
        )
    }

    /** Luminancia (0..255) de una región del bitmap, igual que cv2.cvtColor(BGR2GRAY). */
    private fun grayReader(bmp: Bitmap) = GrayRegion { x1, y1, x2, y2 ->
        val ww = x2 - x1
        val hh = y2 - y1
        val px = IntArray(ww * hh)
        bmp.getPixels(px, 0, ww, x1, y1, ww, hh)
        IntArray(px.size) {
            val c = px[it]
            (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c) + 500) / 1000
        }
    }

    override fun onError(message: String) = postMessage(message)

    private fun postMessage(message: String) {
        lastMessage = message
        mainHandler.post { ui?.onMessage(message) }
    }

    // ---- Notificación ----

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detección de somnolencia", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Despertate")
            .setContentText("Vigilando somnolencia")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        orientationListener?.disable()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        helper?.close()
        helper = null
        executor?.shutdown()
        alarm?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.despertate.STOP"
        private const val CHANNEL_ID = "deteccion"
        private const val NOTIF_ID = 1
    }
}
