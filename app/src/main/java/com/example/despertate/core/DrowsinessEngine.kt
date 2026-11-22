package com.example.despertate.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

enum class DriverState { CALIBRANDO, ACTIVO, SOMNOLIENTO, PELIGRO, SIN_ROSTRO }

/** Modo de lentes de sol: automático por TDI, o forzado (como la tecla 'g' del script). */
enum class SunglassesMode { AUTO, ON, OFF }

/** Resultado de procesar un frame. La UI solo lee esto. */
class EngineOutput(
    val state: DriverState,
    val status: String,
    val alarm: Boolean,
    val calibrating: Boolean,
    val calibProgress: Double,
    val ear: Double,
    val mar: Double,
    val pitch: Double,
    val dRoll: Double,
    val yaw: Double,
    val dYaw: Double,
    val perclos: Double,
    val totalYawns: Int,
    val totalNods: Int,
    val totalBlinks: Int,
    val earActiveThresh: Double,
    val earSleepThresh: Double,
    val drowsyMode: Boolean,
    val headAnomaly: Boolean,
    val yawning: Boolean,
    val headDanger: Boolean,
    val eyeState: Int,
    val eyeClosedSec: Double = 0.0,
    val headSec: Double = 0.0,
    val sunglasses: Boolean = false,
)

/**
 * Port de la lógica de main() de EspecialistaEnDescanso.py (sin UI, sin cámara, sin sonido):
 * calibración, EMA, bostezos, cabeceo, ojos, PERCLOS y máquina de 3 estados.
 * Lentes de sol: la detección por imagen (SunglassesDetector) la hace quien llama y pasa el resultado crudo;
 * acá se aplican el modo (AUTO/ON/OFF) y la histéresis de 0,4 s. process() recibe una función que mide
 * lentes de sol sobre la imagen original (se llama solo en modo AUTO, con el yaw ya filtrado).
 *
 * Todos los tiempos son segundos reales (monotónicos). Llamar a process() una vez por frame.
 */
class DrowsinessEngine(private val cfg: DetectorConfig = DetectorConfig()) {

    @Volatile
    var useEma: Boolean = true

    @Volatile
    var sunglassesMode: SunglassesMode = SunglassesMode.AUTO

    /** Estado actual de lentes de sol (tras modo + histéresis). */
    @Volatile
    var sunglassesActive: Boolean = false

    private var sunglassesTimer = 0.0
    private val sunglassesHysteresisSec = 0.40

    private companion object {
        /** Razón de pitch típica de dlib de frente, con la que se calibraron delta y piso. */
        const val PITCH_REF = 0.73
    }

    private val calibrationDuration = 3.0
    private val alphaEar = 0.60
    private val alphaSlow = 0.35
    private val yawThresh = 0.18

    private val perclosTracker = PerclosTracker()
    private var perclos = 0.0

    private var drowsyModeUntil = -1e9
    private var drowsyModeActive = false

    // Umbrales dinámicos
    private var earActiveThresh = 0.25
    private var earSleepThresh = 0.21
    private var pitchNodThresh = 1.00
    private var rollTiltThresh = cfg.activoRollGrados
    private var headThreshSec = cfg.activoCabeceoPeligroSeg
    private var sleepThreshSec = cfg.activoOjosPeligroSeg

    // Duraciones acumuladas
    private var sleepDuration = 0.0
    private var drowsyDuration = 0.0
    private var activeDuration = 0.0
    private var blinkDuration = 0.0
    private var yawnDuration = 0.0
    private var headDuration = 0.0
    private var yawnCounted = false
    private var nodCounted = false
    private var totalBlinks = 0
    private var totalYawns = 0
    private var totalNods = 0

    // Calibración
    private var isCalibrating = true
    private var calibElapsedSec = 0.0
    private var eyeCalibElapsedSec = 0.0
    private val calibLeftEars = ArrayList<Double>()
    private val calibRightEars = ArrayList<Double>()
    private val calibPitches = ArrayList<Double>()
    private val calibRolls = ArrayList<Double>()
    private val calibYaws = ArrayList<Double>()
    private var leftEarBase: Double? = null
    private var rightEarBase: Double? = null
    private var pitchBase: Double? = null
    private var rollBase = 0.0
    private var yawBase = 0.0

    // EMA
    private var emaEar: Double? = null
    private var emaMar: Double? = null
    private var emaPitch: Double? = null
    private var emaRoll: Double? = null
    private var emaYaw: Double? = null

    private var prevFrameTs: Double? = null
    private var status = "Iniciando..."
    private var state = DriverState.CALIBRANDO

    // Últimos valores mostrados (para el frame sin rostro)
    private var lastOut: EngineOutput? = null

    @Synchronized
    fun recalibrate() {
        isCalibrating = true
        calibElapsedSec = 0.0
        eyeCalibElapsedSec = 0.0
        leftEarBase = null
        rightEarBase = null
        calibLeftEars.clear()
        calibRightEars.clear()
        calibPitches.clear()
        calibRolls.clear()
        calibYaws.clear()
        perclosTracker.clear()
        perclos = 0.0
        drowsyModeUntil = -1e9
        drowsyModeActive = false
        resetDurations()
        status = "CALIBRANDO... 0%"
        state = DriverState.CALIBRANDO
    }

    private fun resetDurations() {
        sleepDuration = 0.0
        drowsyDuration = 0.0
        activeDuration = 0.0
        yawnDuration = 0.0
        headDuration = 0.0
        blinkDuration = 0.0
        yawnCounted = false
        nodCounted = false
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0

    /**
     * Umbral de cabeceo. En el script original el delta mínimo y el piso (0.94 / 0.88) estaban
     * calibrados para la razón de pitch de dlib (~0.73 de frente). Con los landmarks de MediaPipe esa
     * razón vale otra cosa (~0.3-0.4), así que delta y piso se escalan según la razón calibrada del
     * usuario. El factor (145% / 128% de Pitch_0) queda igual.
     */
    private fun pitchThreshold(pb: Double, factor: Double, deltaMin: Double, floor: Double): Double {
        val s = pb / PITCH_REF
        return round3(maxOf(pb * factor, pb + deltaMin * s, floor * s))
    }

    private fun calibProgress(): Double = minOf(calibElapsedSec / calibrationDuration, 1.0)

    @Synchronized
    fun process(
        face: FaceLandmarks?,
        nowTs: Double,
        probeSunglasses: ((yawRatio: Double) -> Boolean)? = null,
    ): EngineOutput {
        val prev = prevFrameTs
        val frameDt = if (prev == null) 0.0 else (nowTs - prev).coerceIn(0.0, 0.25)
        prevFrameTs = nowTs

        if (face == null) return noFaceOutput()

        // ---- Métricas instantáneas ----
        val leftEar = FaceMetrics.eyeAspectRatio(face.leftEye)
        val rightEar = FaceMetrics.eyeAspectRatio(face.rightEye)
        val hp = FaceMetrics.headPose(face)
        val rollRaw = hp.rollDeg
        val pitchRaw = hp.pitchRatio
        val yawRaw = hp.yawRatio

        val marRaw = FaceMetrics.mouthAspectRatio(face.innerMouth) * max(0.75, 1.0 - 0.35 * abs(yawRaw))
        val earRaw = FaceMetrics.perspectiveCompensatedEar(
            leftEar, rightEar, yawRaw, leftEarBase, rightEarBase, yawThresh,
        )

        emaEar = FaceMetrics.emaFilter(earRaw, emaEar, alphaEar, frameDt)
        emaMar = FaceMetrics.emaFilter(marRaw, emaMar, alphaSlow, frameDt)
        emaPitch = FaceMetrics.emaFilter(pitchRaw, emaPitch, alphaSlow, frameDt)
        emaRoll = FaceMetrics.emaFilter(rollRaw, emaRoll, alphaSlow, frameDt)
        emaYaw = FaceMetrics.emaFilter(yawRaw, emaYaw, alphaSlow, frameDt)

        val ear: Double
        val mar: Double
        val pitchRatio: Double
        val rollDeg: Double
        val yawRatio: Double
        if (useEma) {
            ear = emaEar!!
            mar = emaMar!!
            pitchRatio = emaPitch!!
            rollDeg = emaRoll!!
            yawRatio = emaYaw!!
        } else {
            ear = earRaw
            mar = marRaw
            pitchRatio = pitchRaw
            rollDeg = rollRaw
            yawRatio = yawRaw
        }

        when (sunglassesMode) {
            SunglassesMode.ON -> sunglassesActive = true
            SunglassesMode.OFF -> sunglassesActive = false
            SunglassesMode.AUTO -> if (probeSunglasses?.invoke(yawRatio) == true) {
                sunglassesTimer = minOf(sunglassesHysteresisSec, sunglassesTimer + frameDt)
                if (sunglassesTimer >= sunglassesHysteresisSec) sunglassesActive = true
            } else {
                sunglassesTimer = maxOf(-sunglassesHysteresisSec, sunglassesTimer - frameDt)
                if (sunglassesTimer <= -sunglassesHysteresisSec) sunglassesActive = false
            }
        }
        val sunglasses = sunglassesActive

        // ---- Calibración personalizada (3 s efectivos con rostro) ----
        if (isCalibrating) {
            calibElapsedSec += frameDt
            if (!sunglasses && leftEar > 0.14 && rightEar > 0.14) {
                calibLeftEars.add(leftEar)
                calibRightEars.add(rightEar)
                eyeCalibElapsedSec += frameDt
            }
            calibPitches.add(pitchRaw)
            calibRolls.add(rollRaw)
            calibYaws.add(yawRaw)

            status = "CALIBRANDO... ${(calibProgress() * 100).toInt()}%"
            state = DriverState.CALIBRANDO

            if (calibElapsedSec >= calibrationDuration && calibPitches.size >= 5) {
                if (calibLeftEars.size >= 5) {
                    setEarBases(median(calibLeftEars), median(calibRightEars))
                }
                val pb = median(calibPitches)
                pitchBase = pb
                rollBase = median(calibRolls)
                yawBase = median(calibYaws)
                pitchNodThresh = pitchThreshold(pb, cfg.activoPitchFactor, cfg.activoPitchDeltaMin, 0.94)
                isCalibrating = false
                perclosTracker.clear()
                perclos = 0.0
                drowsyModeUntil = -1e9
                drowsyModeActive = false
                resetDurations()
            }
        } else if (!sunglasses && leftEarBase == null) {
            // Auto-calibración ocular diferida (calibró con lentes de sol y luego se los sacó)
            if (leftEar > 0.14 && rightEar > 0.14) {
                calibLeftEars.add(leftEar)
                calibRightEars.add(rightEar)
                eyeCalibElapsedSec += frameDt
                if (eyeCalibElapsedSec >= calibrationDuration && calibLeftEars.size >= 5) {
                    setEarBases(median(calibLeftEars), median(calibRightEars))
                }
            }
        }

        // ---- Modo somnoliento y umbrales dinámicos ----
        drowsyModeActive = !isCalibrating &&
            (nowTs < drowsyModeUntil || perclos >= cfg.perclosUmbralSomnolientoPct)
        val pb = pitchBase
        if (drowsyModeActive) {
            sleepThreshSec = cfg.somnolientoOjosPeligroSeg
            headThreshSec = cfg.somnolientoCabeceoPeligroSeg
            rollTiltThresh = cfg.somnolientoRollGrados
            pitchNodThresh = if (pb != null) {
                pitchThreshold(pb, cfg.somnolientoPitchFactor, cfg.somnolientoPitchDeltaMin, 0.88)
            } else {
                0.92
            }
        } else {
            sleepThreshSec = cfg.activoOjosPeligroSeg
            headThreshSec = cfg.activoCabeceoPeligroSeg
            rollTiltThresh = cfg.activoRollGrados
            pitchNodThresh = if (pb != null) {
                pitchThreshold(pb, cfg.activoPitchFactor, cfg.activoPitchDeltaMin, 0.94)
            } else {
                1.00
            }
        }

        val deltaRoll = rollDeg - rollBase
        val deltaYaw = yawRatio - yawBase
        val headAnomaly = (pitchRatio > pitchNodThresh) || (abs(deltaRoll) > rollTiltThresh)

        // ---- 1. Bostezo (síntoma, nunca dispara alarma solo) ----
        var yawning = false
        if (!isCalibrating && mar > cfg.bostezoMarUmbral) {
            yawnDuration += frameDt
            if (yawnDuration >= cfg.bostezoDuracionSeg) {
                yawning = true
                if (!yawnCounted) {
                    totalYawns += 1
                    yawnCounted = true
                    perclosTracker.yawnTimestamps.addLast(nowTs)
                    drowsyModeUntil = max(drowsyModeUntil, nowTs + cfg.somnolientoMemoriaSeg)
                }
            }
        } else {
            yawnDuration = 0.0
            yawnCounted = false
        }

        // ---- 2. Cabeceo / inclinación lateral ----
        var headDangerNow = false
        if (!isCalibrating && headAnomaly) {
            headDuration += frameDt
            if (headDuration >= cfg.activoCabeceoSintomaSeg) {
                drowsyModeUntil = max(drowsyModeUntil, nowTs + cfg.somnolientoMemoriaSeg)
            }
            if (headDuration >= headThreshSec) {
                headDangerNow = true
                if (!nodCounted) {
                    totalNods += 1
                    nodCounted = true
                }
            }
        } else {
            headDuration = 0.0
            nodCounted = false
        }

        // ---- 3. Ojos (EAR) ----
        val eyeState: Int
        val eyeW: Double
        if (sunglasses) {
            eyeState = 2
            sleepDuration = 0.0
            drowsyDuration = 0.0
            blinkDuration = 0.0
            eyeW = 0.0
        } else {
            eyeState = FaceMetrics.classifyEyeState(ear, earActiveThresh, earSleepThresh)
            val eyeClosedRaw = FaceMetrics.classifyEyeState(earRaw, earActiveThresh, earSleepThresh) == 0
            val eyeClosed = eyeState == 0

            if (!isCalibrating) {
                if (eyeClosedRaw) {
                    blinkDuration += frameDt
                } else {
                    if (blinkDuration >= cfg.parpadeoMinSeg) totalBlinks += 1
                    blinkDuration = 0.0
                }

                if (eyeClosed) {
                    sleepDuration += frameDt
                    drowsyDuration = 0.0
                    activeDuration = 0.0
                } else if (eyeState == 1) {
                    sleepDuration = 0.0
                    activeDuration = 0.0
                    drowsyDuration += frameDt
                    if (drowsyDuration >= cfg.activoOjosEntrecerradosSeg) {
                        drowsyModeUntil = max(drowsyModeUntil, nowTs + cfg.somnolientoMemoriaSeg)
                    }
                } else {
                    drowsyDuration = 0.0
                    sleepDuration = 0.0
                    activeDuration += frameDt
                }
            }

            eyeW = when {
                eyeClosed -> cfg.perclosPesoOjoCerrado
                eyeState == 1 -> cfg.perclosPesoOjoEntrecerrado
                else -> 0.0
            }
        }

        // ---- PERCLOS unificado (ojos + cabeceo + bostezos) ----
        if (!isCalibrating) {
            val headW = if (headAnomaly) cfg.perclosPesoCabeceo else 0.0
            val instantFatigueWeight = minOf(1.0, max(eyeW, headW))
            perclos = perclosTracker.update(
                nowTs,
                instantFatigueWeight,
                windowSec = cfg.perclosVentanaSeg,
                minObsSec = cfg.perclosMinObsSeg,
                yawnBonusPct = cfg.perclosBonoPorBostezoPct,
                yawnMaxBonusPct = cfg.perclosTopeBostezosPct,
            )
            if (perclos >= cfg.perclosUmbralSomnolientoPct) {
                drowsyModeUntil = max(drowsyModeUntil, nowTs + 5.0)
            }
            drowsyModeActive = (nowTs < drowsyModeUntil) || (perclos >= cfg.perclosUmbralSomnolientoPct)
        } else {
            perclos = 0.0
            drowsyModeActive = false
        }

        // ---- 4. Máquina de 3 estados ----
        val eyeDanger = !isCalibrating && !sunglasses && (
            sleepDuration >= sleepThreshSec ||
                (perclos >= cfg.perclosUmbralPeligroPct && eyeState < 2)
            )
        val headDanger = !isCalibrating && (
            headDuration >= headThreshSec ||
                (perclos >= cfg.perclosUmbralPeligroPct && headAnomaly)
            )

        var alarm = false
        if (!isCalibrating) {
            if (eyeDanger || headDanger) {
                status = when {
                    sleepDuration >= sleepThreshSec -> "PELIGRO: DURMIENDO !!!"
                    headDuration >= headThreshSec -> "PELIGRO: CABECEO CRITICO!"
                    else -> "PELIGRO: SOMNOLENCIA (PERCLOS)!"
                }
                state = DriverState.PELIGRO
                alarm = true
            } else if (drowsyModeActive || headAnomaly || mar > cfg.bostezoMarUmbral) {
                status = if (sunglasses) "MODO SOMNOLIENTO [Lentes] !" else "MODO SOMNOLIENTO (Fatiga) !"
                state = DriverState.SOMNOLIENTO
            } else if (sunglasses || activeDuration >= cfg.recuperacionActivoSeg) {
                status = if (sunglasses) "Activo [Lentes de Sol]" else "Activo :)"
                state = DriverState.ACTIVO
            }
            // si no, se conserva el estado/mensaje anterior (histéresis de recuperación)
        }

        val out = EngineOutput(
            sunglasses = sunglasses,
            state = state,
            status = status,
            alarm = alarm,
            calibrating = isCalibrating,
            calibProgress = calibProgress(),
            ear = ear,
            mar = mar,
            pitch = pitchRatio,
            dRoll = deltaRoll,
            yaw = yawRatio,
            dYaw = deltaYaw,
            perclos = perclos,
            totalYawns = totalYawns,
            totalNods = totalNods,
            totalBlinks = totalBlinks,
            earActiveThresh = earActiveThresh,
            earSleepThresh = earSleepThresh,
            drowsyMode = drowsyModeActive,
            headAnomaly = headAnomaly,
            yawning = yawning,
            headDanger = headDangerNow,
            eyeState = eyeState,
            eyeClosedSec = sleepDuration,
            headSec = headDuration,
        )
        lastOut = out
        return out
    }

    private fun setEarBases(left: Double, right: Double) {
        leftEarBase = left
        rightEarBase = right
        val base = 0.5 * (left + right)
        earActiveThresh = round3(base * cfg.earFactorActivo)
        earSleepThresh = round3(base * cfg.earFactorCerrado)
    }

    /** Frame sin rostro: se corta la alarma y se reinician las duraciones (como en el script). */
    private fun noFaceOutput(): EngineOutput {
        resetDurations()
        // El mensaje es solo de visualización: no pisa el estado persistente (como en el script).
        val msg = if (isCalibrating) {
            "CALIBRANDO (${(calibProgress() * 100).toInt()}%) - Esperando rostro..."
        } else {
            "SIN CONDUCTOR EN CUADRO"
        }
        val l = lastOut
        return EngineOutput(
            state = DriverState.SIN_ROSTRO,
            status = msg,
            alarm = false,
            calibrating = isCalibrating,
            calibProgress = calibProgress(),
            ear = l?.ear ?: 0.0,
            mar = l?.mar ?: 0.0,
            pitch = l?.pitch ?: 0.0,
            dRoll = l?.dRoll ?: 0.0,
            yaw = l?.yaw ?: 0.0,
            dYaw = l?.dYaw ?: 0.0,
            perclos = perclos,
            totalYawns = totalYawns,
            totalNods = totalNods,
            totalBlinks = totalBlinks,
            earActiveThresh = earActiveThresh,
            earSleepThresh = earSleepThresh,
            drowsyMode = drowsyModeActive,
            headAnomaly = false,
            yawning = false,
            headDanger = false,
            eyeState = 2,
        )
    }
}
