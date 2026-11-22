package com.example.despertate

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.despertate.core.DriverState
import com.example.despertate.core.SunglassesMode
import java.util.Locale

/**
 * Pantalla: muestra el cuadro analizado, el estado y las métricas.
 * La cámara, el detector y la alarma viven en DetectionService (primer plano).
 */
class MainActivity : AppCompatActivity(), DetectionService.UiListener {

    private lateinit var overlay: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnRecalibrar: Button
    private lateinit var btnEma: Button
    private lateinit var btnPip: Button
    private lateinit var btnClahe: Button
    private lateinit var btnDatos: Button
    private lateinit var btnDetener: Button
    private lateinit var btnLentes: Button
    private lateinit var btnProbarAlarma: Button
    private lateinit var panelButtons: View
    private var inPip = false
    private var showMetrics = true

    private var service: DetectionService? = null
    private var bound = false
    private var started = false
    private var gotUpdate = false
    private var startError: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // Diagnóstico
    private var fpsCount = 0
    private var fpsStamp = 0L
    private var fps = 0
    private val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL})"

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as DetectionService.LocalBinder).service
            service = s
            s.setUi(this@MainActivity)
            s.lastMessage?.let { onMessage(it) }
            updateEmaButton()
            updateTdiButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startDetection()
            } else {
                tvStatus.text = "Permiso de cámara denegado"
                tvStatus.setTextColor(Color.rgb(255, 165, 0))
            }
        }

    // Mientras no llegue el primer resultado, se muestra en qué paso está el servicio.
    private val watchdog = object : Runnable {
        override fun run() {
            if (gotUpdate) return
            val s = service
            val txt = "Esperando datos...\n" + when {
                startError != null -> "No se pudo iniciar el servicio: $startError"
                !started -> "Iniciando"
                s == null -> "Conectando con el servicio (bind=$bound)"
                else -> s.diagnostics()
            }
            if (!inPip) tvMetrics.text = txt
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Los botones de volumen del celu controlan el volumen de ALARMA mientras la app está abierta
        volumeControlStream = AudioManager.STREAM_ALARM
        setContentView(R.layout.activity_main)

        overlay = findViewById(R.id.overlay)
        tvStatus = findViewById(R.id.tvStatus)
        tvMetrics = findViewById(R.id.tvMetrics)
        progress = findViewById(R.id.progressCalib)
        btnRecalibrar = findViewById(R.id.btnRecalibrar)
        btnEma = findViewById(R.id.btnEma)
        btnPip = findViewById(R.id.btnPip)
        btnClahe = findViewById(R.id.btnClahe)
        btnDatos = findViewById(R.id.btnDatos)
        btnDetener = findViewById(R.id.btnDetener)
        btnLentes = findViewById(R.id.btnLentes)
        panelButtons = findViewById(R.id.panelButtons)
        btnProbarAlarma = findViewById(R.id.btnProbarAlarma)

        btnRecalibrar.setOnClickListener { service?.recalibrate() }
        btnEma.setOnClickListener {
            service?.engine?.let { it.useEma = !it.useEma }
            updateEmaButton()
        }
        updateEmaButton()

        btnClahe.setOnClickListener {
            service?.toggleClahe()
            updateTdiButtons()
        }
        btnLentes.setOnClickListener {
            service?.cycleSunglassesMode()
            updateTdiButtons()
        }

        btnDatos.setOnClickListener {
            showMetrics = !showMetrics
            tvMetrics.visibility = if (showMetrics) View.VISIBLE else View.GONE
            btnDatos.text = if (showMetrics) "Datos: SI" else "Datos: NO"
        }
        btnDetener.setOnClickListener {
            // Apaga cámara, detección y alarma; sin esto el servicio sigue aunque cierres la app
            val s = service
            if (s != null) s.stopDetection() else finishAndRemoveTask()
        }

        // Picture-in-Picture: al salir de la app queda una ventana flotante con el estado.
        setPictureInPictureParams(pipParams())
        btnPip.setOnClickListener { enterPip() }

        btnProbarAlarma.setOnClickListener {
            val msg = service?.testAlarm(3000) ?: "Servicio todavía no listo"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // El aviso de notificaciones no bloquea nada: el servicio arranca igual.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startDetection()
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Se arranca con la app visible: Android solo deja iniciar un servicio de cámara en ese caso. */
    private fun startDetection() {
        if (started) return
        started = true
        try {
            val intent = Intent(this, DetectionService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            startError = "${e.javaClass.simpleName}: ${e.message}"
            tvStatus.text = "No se pudo iniciar el servicio: ${e.message}"
        }
        handler.post(watchdog)
    }

    override fun onStart() {
        super.onStart()
        service?.setUi(this)
    }

    override fun onStop() {
        super.onStop()
        // Sin pantalla visible el servicio sigue detectando y sonando, pero no dibuja.
        service?.setUi(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchdog)
        service?.setUi(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
    }

    // ---- Picture-in-Picture ----

    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(4, 3))
            .setAutoEnterEnabled(true)
            .build()

    private fun enterPip() {
        if (isInPictureInPictureMode) return
        try {
            enterPictureInPictureMode(pipParams())
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo pasar a segundo plano: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Cubre la navegación con 3 botones; con gestos se encarga setAutoEnterEnabled.
        if (!isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(pipParams())
            } catch (_: Exception) {
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        val vis = if (inPip) View.GONE else View.VISIBLE
        tvMetrics.visibility = if (inPip || !showMetrics) View.GONE else View.VISIBLE
        panelButtons.visibility = vis
        if (inPip) progress.visibility = View.GONE
        tvStatus.textSize = if (inPip) 12f else 20f
    }

    private fun updateTdiButtons() {
        val s = service
        btnClahe.text = if (s?.claheEnabled == true) "CLAHE: ON" else "CLAHE: OFF"
        btnLentes.text = "Lentes: ${(s?.engine?.sunglassesMode ?: SunglassesMode.AUTO).name}"
    }

    private fun updateEmaButton() {
        btnEma.text = if (service?.engine?.useEma ?: true) "EMA: ON" else "EMA: OFF"
    }

    // ---- Resultados del servicio (hilo principal) ----

    override fun onMessage(message: String) {
        tvStatus.text = message
        tvStatus.setTextColor(Color.rgb(255, 165, 0))
    }

    override fun onStopped() {
        // Se tocó "Detener" en la notificación
        finishAndRemoveTask()
    }

    override fun onUpdate(u: DetectionService.Update) {
        gotUpdate = true
        val out = u.out
        val nowMs = SystemClock.uptimeMillis()
        fpsCount++
        if (nowMs - fpsStamp >= 1000) {
            fps = fpsCount
            fpsCount = 0
            fpsStamp = nowMs
        }
        val orange = Color.rgb(255, 165, 0)
        val statusColor = when (out.state) {
            DriverState.PELIGRO -> Color.RED
            DriverState.SOMNOLIENTO, DriverState.SIN_ROSTRO -> orange
            DriverState.ACTIVO -> Color.GREEN
            DriverState.CALIBRANDO -> Color.CYAN
        }
        tvStatus.text = out.status
        tvStatus.setTextColor(statusColor)

        progress.visibility = if (out.calibrating && !inPip) View.VISIBLE else View.GONE
        progress.progress = (out.calibProgress * 100).toInt()

        val axisColor = when {
            out.headDanger -> Color.RED
            out.headAnomaly -> orange
            else -> Color.CYAN
        }
        val mouthColor = if (out.yawning) orange else Color.rgb(0, 120, 255)
        val eyeColor = if (out.sunglasses) Color.rgb(180, 100, 220) else Color.GREEN
        overlay.update(u.frame, u.face, u.width, u.height, eyeColor, mouthColor, axisColor)

        if (inPip) return
        val h = service?.helper
        val vol = service?.alarm?.volumePercent() ?: 0
        tvMetrics.text = String.format(
            Locale.US,
            "EAR: %.2f\nMAR: %.2f\nPERCLOS: %.1f%%\nPitch: %.2f\ndRoll: %+.1f°\nYaw: %+.2f d:%+.2f\n" +
                "Bostezos: %d\nCabeceos: %d\nParpadeos: %d\nUmb EAR: %.2f/%.2f\nSensib: %s\n" +
                "Ojos cerrados: %.1fs | Cabeza: %.1fs\nLentes: %s (ratio %.2f) | CLAHE: %s gamma %.2f lum %.0f\nFPS: %d | %s\nInfer: %d ms | Total: %s\nImagen: %dx%d\n%s\nVol alarma: %d%%%s",
            out.ear, out.mar, out.perclos, out.pitch, out.dRoll, out.yaw, out.dYaw,
            out.totalYawns, out.totalNods, out.totalBlinks,
            out.earActiveThresh, out.earSleepThresh,
            if (out.drowsyMode) "ALTA (Somnoliento)" else "Normal (Activo)",
            out.eyeClosedSec, out.headSec,
            if (out.sunglasses) "SI" else "NO", u.sgRatio, if (u.claheOn) "ON" else "OFF", u.gamma, u.refLum,
            fps, h?.delegateName ?: "-",
            u.inferMs, if (u.totalMs in 0..5000) "${u.totalMs} ms" else "n/d",
            u.width, u.height,
            deviceInfo,
            vol,
            if (vol < 50) " (BAJO)" else "",
        )
    }
}
