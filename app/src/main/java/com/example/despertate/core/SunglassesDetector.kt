package com.example.despertate.core

import kotlin.math.max
import kotlin.math.min

/**
 * Port de detect_sunglasses_tdi: análisis radiométrico comparativo entre la región orbital
 * (donde iría el cristal) y la piel del pómulo. Con lentes oscuros el percentil 68 de la órbita
 * cae mucho respecto de la mediana del pómulo (ratio < 0.58 y diferencia > 26 niveles de gris).
 *
 * No depende de Android: la imagen se accede con [GrayRegion], que devuelve la luminancia (0..255)
 * de un rectángulo [x1,x2) x [y1,y2) ya recortado a los límites de la imagen.
 */
fun interface GrayRegion {
    fun read(x1: Int, y1: Int, x2: Int, y2: Int): IntArray
}

class SunglassesResult(val isDarkLens: Boolean, val ratio: Double)

object SunglassesDetector {

    /** Igual que numpy.percentile (interpolación lineal). */
    fun percentile(values: IntArray, p: Double): Double {
        val s = values.sortedArray()
        val pos = p / 100.0 * (s.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun detect(face: FaceLandmarks, yawRatio: Double, width: Int, height: Int, gray: GrayRegion): SunglassesResult {
        if (face.rightBrow.isEmpty() || face.leftBrow.isEmpty()) return SunglassesResult(false, 1.0)
        val noseTipY = face.noseTip.y.toInt()

        fun sideRatio(eye: Array<Pt>, brow: Array<Pt>): Pair<Double, Double> {
            val exMin = eye.minOf { it.x }.toInt()
            val exMax = eye.maxOf { it.x }.toInt()
            val eyMin = eye.minOf { it.y }.toInt()
            val eyMax = eye.maxOf { it.y }.toInt()
            val byMin = brow.minOf { it.y }.toInt()

            val eyeW = max(12, exMax - exMin)
            val padX = (0.15 * eyeW).toInt()

            val ox1 = max(0, exMin - padX)
            val ox2 = min(width, exMax + padX)
            val oy1 = max(0, (0.35 * byMin + 0.65 * eyMin).toInt())
            val oy2 = min(height, (eyMax + 0.30 * eyeW).toInt())

            val cx1 = max(0, exMin)
            val cx2 = min(width, exMax)
            val cy1 = min(height, (eyMax + 0.55 * eyeW).toInt())
            val cy2 = min(height, max(cy1 + 8, min(noseTipY, (eyMax + 1.25 * eyeW).toInt())))

            if (ox2 <= ox1 || oy2 <= oy1 || cx2 <= cx1 || cy2 <= cy1) return Pair(1.0, 0.0)
            val orb = gray.read(ox1, oy1, ox2, oy2)
            val cheek = gray.read(cx1, cy1, cx2, cy2)
            if (orb.size < 25 || cheek.size < 25) return Pair(1.0, 0.0)

            val orbP68 = percentile(orb, 68.0)
            val cheekP50 = percentile(cheek, 50.0)
            if (cheekP50 < 38.0) return Pair(1.0, 0.0)

            return Pair(orbP68 / max(cheekP50, 1.0), cheekP50 - orbP68)
        }

        val (rRatio, rDiff) = sideRatio(face.rightEye, face.rightBrow)
        val (lRatio, lDiff) = sideRatio(face.leftEye, face.leftBrow)

        val ratio: Double
        val diff: Double
        if (yawRatio > 0.18) {
            ratio = lRatio; diff = lDiff
        } else if (yawRatio < -0.18) {
            ratio = rRatio; diff = rDiff
        } else {
            ratio = 0.5 * (lRatio + rRatio); diff = 0.5 * (lDiff + rDiff)
        }
        return SunglassesResult(ratio < 0.58 && diff > 26.0, ratio)
    }
}
