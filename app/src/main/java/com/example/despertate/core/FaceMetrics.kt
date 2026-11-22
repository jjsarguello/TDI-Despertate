package com.example.despertate.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/** Punto 2D en píxeles de la imagen analizada. */
data class Pt(val x: Double, val y: Double)

fun dist(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

/**
 * Subconjunto de landmarks que usa el sistema, con el mismo orden que dlib 68:
 *  - rightEye = landmarks 36..41, leftEye = 42..47
 *  - innerMouth = 60..67
 *  - noseBridge = 27, noseTip = 30, chin = 8, jawRight = 0, jawLeft = 16
 */
class FaceLandmarks(
    val rightEye: Array<Pt>,
    val leftEye: Array<Pt>,
    val innerMouth: Array<Pt>,
    val noseBridge: Pt,
    val noseTip: Pt,
    val chin: Pt,
    val jawRight: Pt,
    val jawLeft: Pt,
    /** Cejas (dlib 17..21 y 22..26); solo las usa la detección de lentes de sol. */
    val rightBrow: Array<Pt> = emptyArray(),
    val leftBrow: Array<Pt> = emptyArray(),
)

data class HeadPose(val rollDeg: Double, val pitchRatio: Double, val yawRatio: Double)

/** Port 1:1 de las funciones de métricas de EspecialistaEnDescanso.py. */
object FaceMetrics {

    /** EAR = (||p2-p6|| + ||p3-p5||) / (2 ||p1-p4||) */
    fun eyeAspectRatio(eye: Array<Pt>): Double {
        val v1 = dist(eye[1], eye[5])
        val v2 = dist(eye[2], eye[4])
        val h = dist(eye[0], eye[3])
        if (h == 0.0) return 0.0
        return (v1 + v2) / (2.0 * h)
    }

    /** MAR = (||p61-p67|| + ||p62-p66|| + ||p63-p65||) / (3 ||p60-p64||) */
    fun mouthAspectRatio(m: Array<Pt>): Double {
        val v1 = dist(m[1], m[7])
        val v2 = dist(m[2], m[6])
        val v3 = dist(m[3], m[5])
        val h = dist(m[0], m[4])
        if (h == 0.0) return 0.0
        return (v1 + v2 + v3) / (3.0 * h)
    }

    private fun center(pts: Array<Pt>): Pt =
        Pt(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)

    fun headPose(lm: FaceLandmarks): HeadPose {
        val rc = center(lm.rightEye)
        val lc = center(lm.leftEye)
        val dy = lc.y - rc.y
        val dx = lc.x - rc.x
        val rollDeg = Math.toDegrees(atan2(dy, max(abs(dx), 1e-6)))

        val noseLen = dist(lm.noseTip, lm.noseBridge)
        val noseToChin = dist(lm.chin, lm.noseTip)
        val pitchRatio = noseLen / max(noseToChin, 1e-6)

        val distR = max(lm.noseTip.x - lm.jawRight.x, 1.0)
        val distL = max(lm.jawLeft.x - lm.noseTip.x, 1.0)
        val yawRatio = (distL - distR) / (distL + distR)

        return HeadPose(rollDeg, pitchRatio, yawRatio)
    }

    /** EAR normalizado por ojo y ponderado hacia el ojo expuesto a la cámara cuando hay yaw. */
    fun perspectiveCompensatedEar(
        leftEar: Double,
        rightEar: Double,
        yawRatio: Double,
        leftEarBase: Double? = null,
        rightEarBase: Double? = null,
        yawThresh: Double = 0.18,
    ): Double {
        val lNorm: Double
        val rNorm: Double
        if (leftEarBase != null && rightEarBase != null && leftEarBase > 1e-4 && rightEarBase > 1e-4) {
            val meanBase = 0.5 * (leftEarBase + rightEarBase)
            lNorm = leftEar * (meanBase / leftEarBase)
            rNorm = rightEar * (meanBase / rightEarBase)
        } else {
            lNorm = leftEar
            rNorm = rightEar
        }
        return when {
            yawRatio > yawThresh -> 0.85 * lNorm + 0.15 * rNorm
            yawRatio < -yawThresh -> 0.85 * rNorm + 0.15 * lNorm
            else -> 0.5 * (lNorm + rNorm)
        }
    }

    /** EMA independiente de los FPS: alpha_eff = 1 - (1-alpha)^(dt/refDt). */
    fun emaFilter(
        current: Double,
        prev: Double?,
        alpha: Double,
        dt: Double? = null,
        refDt: Double = 0.04,
    ): Double {
        if (prev == null) return current
        val aEff = if (dt != null && dt > 0.0 && alpha > 0.0 && alpha < 1.0) {
            (1.0 - (1.0 - alpha).pow(dt / refDt)).coerceIn(0.05, 0.98)
        } else {
            alpha
        }
        return aEff * current + (1.0 - aEff) * prev
    }

    /** 2 = abierto, 1 = entrecerrado, 0 = cerrado. */
    fun classifyEyeState(ear: Double, activeThresh: Double = 0.25, sleepThresh: Double = 0.21): Int =
        when {
            ear > activeThresh -> 2
            ear > sleepThresh -> 1
            else -> 0
        }
}
