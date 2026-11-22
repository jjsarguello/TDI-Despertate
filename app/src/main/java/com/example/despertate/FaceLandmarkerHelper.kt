package com.example.despertate

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.despertate.core.FaceLandmarks
import com.example.despertate.core.LandmarkMapper
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Envuelve MediaPipe Face Landmarker (un solo rostro: el conductor).
 * Modo LIVE_STREAM: asíncrono, con seguimiento entre cuadros.
 * El modelo debe estar en app/src/main/assets/face_landmarker.task
 */
class FaceLandmarkerHelper(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * face == null cuando no se detecta rostro.
         * inferMs: tiempo desde que se envió el cuadro hasta tener el resultado.
         * totalMs: desde que el sensor capturó el cuadro hasta tener el resultado.
         */
        fun onResult(
            face: FaceLandmarks?,
            frame: Bitmap?,
            width: Int,
            height: Int,
            inferMs: Long,
            totalMs: Long,
            sensorTsMs: Long,
        )

        fun onError(message: String)
    }

    @Volatile
    private var landmarker: FaceLandmarker? = null

    /** "CPU" o "GPU": el que está activo ahora. */
    @Volatile
    var delegateName: String = "-"
        private set

    /** Si no hay pantalla visible no hace falta copiar el cuadro. */
    @Volatile
    var wantFrames: Boolean = true

    @Volatile
    private var busy = false

    @Volatile
    private var busySince = 0L

    /** timestamp del sensor (ms) -> momento de envío (ms), para medir la latencia de cada cuadro. */
    private val submitTimes = ConcurrentHashMap<Long, Long>()

    init {
        // GPU primero; si falla, create() vuelve solo a CPU
        create(useGpu = true)
    }

    val isReady: Boolean get() = landmarker != null

    /** En modo LIVE: MediaPipe sigue procesando el cuadro anterior (no conviene mandarle otro). */
    val isBusy: Boolean
        get() = busy && (SystemClock.elapsedRealtime() - busySince) < 1000

    /** (Re)crea el detector. Si la GPU falla, vuelve a CPU. Devuelve true si quedó lo pedido. */
    @Synchronized
    fun create(useGpu: Boolean): Boolean {
        val old = landmarker
        landmarker = null
        busy = false
        submitTimes.clear()
        try {
            old?.close()
        } catch (_: Exception) {
        }
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
                .build()
            val builder = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)
            builder.setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, input -> handleLive(result, input) }
                .setErrorListener { e ->
                    busy = false
                    listener.onError(e.message ?: "Error de MediaPipe")
                }
            landmarker = FaceLandmarker.createFromOptions(context, builder.build())
            delegateName = if (useGpu) "GPU" else "CPU"
            true
        } catch (e: Exception) {
            listener.onError(
                "No se pudo iniciar MediaPipe (${if (useGpu) "GPU" else "CPU"}): ${e.message}. " +
                    "¿Está $MODEL_FILE en app/src/main/assets?",
            )
            if (useGpu) create(useGpu = false)
            false
        }
    }

    /**
     * El bitmap debe estar ya rotado (cabeza hacia arriba) y ser ARGB_8888.
     * sensorTsMs: timestamp de captura del sensor en ms (estrictamente creciente).
     */
    fun detect(bitmap: Bitmap, sensorTsMs: Long) {
        val lm = landmarker ?: return
        val mpImage = BitmapImageBuilder(bitmap).build()
        val submit = SystemClock.elapsedRealtime()
        try {
            if (submitTimes.size > 64) submitTimes.clear()
            submitTimes[sensorTsMs] = submit
            busy = true
            busySince = submit
            lm.detectAsync(mpImage, sensorTsMs)
        } catch (_: Exception) {
            // puede ocurrir justo mientras se recrea el detector
            busy = false
        }
    }

    private fun handleLive(result: FaceLandmarkerResult, input: MPImage) {
        val ts = result.timestampMs()
        val submit = submitTimes.remove(ts) ?: SystemClock.elapsedRealtime()
        busy = false
        deliver(result, input, submit, ts)
    }

    private fun deliver(result: FaceLandmarkerResult, input: MPImage, submitMs: Long, sensorTsMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val inferMs = now - submitMs
        val totalMs = now - sensorTsMs
        val frame = if (!wantFrames) null else try {
            BitmapExtractor.extract(input)
        } catch (_: Exception) {
            null
        }
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            listener.onResult(null, frame, input.width, input.height, inferMs, totalMs, sensorTsMs)
            return
        }
        val lms = faces[0]
        val n = lms.size
        val xs = FloatArray(n) { lms[it].x() }
        val ys = FloatArray(n) { lms[it].y() }
        val face = LandmarkMapper.map(xs, ys, input.width, input.height)
        listener.onResult(face, frame, input.width, input.height, inferMs, totalMs, sensorTsMs)
    }

    fun close() {
        try {
            landmarker?.close()
        } catch (_: Exception) {
        }
        landmarker = null
    }

    companion object {
        const val MODEL_FILE = "face_landmarker.task"
    }
}
