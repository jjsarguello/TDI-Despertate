package com.example.despertate.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los valores esperados salen de ejecutar las funciones originales de
 * EspecialistaEnDescanso.py sobre los mismos datos (paridad Python <-> Kotlin).
 */
class CoreParityTest {

    private val right = arrayOf(Pt(210.0, 200.0), Pt(218.0, 193.0), Pt(228.0, 192.0), Pt(238.0, 200.0), Pt(228.0, 207.0), Pt(218.0, 208.0))
    private val left = arrayOf(Pt(262.0, 201.0), Pt(272.0, 194.0), Pt(282.0, 195.0), Pt(290.0, 201.0), Pt(281.0, 209.0), Pt(271.0, 208.0))
    private val mouth = arrayOf(Pt(232.0, 300.0), Pt(240.0, 296.0), Pt(250.0, 294.0), Pt(260.0, 296.0), Pt(268.0, 300.0), Pt(260.0, 306.0), Pt(250.0, 308.0), Pt(240.0, 306.0))

    private val face = FaceLandmarks(
        rightEye = right,
        leftEye = left,
        innerMouth = mouth,
        noseBridge = Pt(250.0, 205.0),
        noseTip = Pt(251.0, 262.0),
        chin = Pt(252.0, 340.0),
        jawRight = Pt(170.0, 240.0),
        jawLeft = Pt(338.0, 236.0),
    )

    private val eps = 1e-9

    @Test
    fun ear() {
        assertEquals(0.5357142857142857, FaceMetrics.eyeAspectRatio(right), eps)
        assertEquals(0.5012738874149357, FaceMetrics.eyeAspectRatio(left), eps)
    }

    @Test
    fun mar() {
        assertEquals(0.3148148148148148, FaceMetrics.mouthAspectRatio(mouth), eps)
    }

    @Test
    fun headPose() {
        val hp = FaceMetrics.headPose(face)
        assertEquals(1.4410992921221555, hp.rollDeg, eps)
        assertEquals(0.730821624274902, hp.pitchRatio, eps)
        assertEquals(0.03571428571428571, hp.yawRatio, eps)
    }

    @Test
    fun perspectiveCompensatedEar() {
        assertEquals(0.28, FaceMetrics.perspectiveCompensatedEar(0.30, 0.26, 0.05), eps)
        assertEquals(0.28084821428571427, FaceMetrics.perspectiveCompensatedEar(0.30, 0.26, 0.30, 0.32, 0.28), eps)
        assertEquals(0.2789732142857143, FaceMetrics.perspectiveCompensatedEar(0.30, 0.26, -0.30, 0.32, 0.28), eps)
        assertEquals(0.2799107142857143, FaceMetrics.perspectiveCompensatedEar(0.30, 0.26, 0.0, 0.32, 0.28), eps)
    }

    @Test
    fun ema() {
        assertEquals(0.5, FaceMetrics.emaFilter(0.5, null, 0.6, 0.033), eps)
        assertEquals(0.40608618380177564, FaceMetrics.emaFilter(0.5, 0.3, 0.6, 0.033), eps)
        assertEquals(0.43187392202687724, FaceMetrics.emaFilter(0.5, 0.3, 0.35, 0.1), eps)
        assertEquals(0.37, FaceMetrics.emaFilter(0.5, 0.3, 0.35, 0.0), eps)
    }

    @Test
    fun eyeState() {
        assertEquals(2, FaceMetrics.classifyEyeState(0.30, 0.25, 0.21))
        assertEquals(1, FaceMetrics.classifyEyeState(0.23, 0.25, 0.21))
        assertEquals(0, FaceMetrics.classifyEyeState(0.20, 0.25, 0.21))
    }

    @Test
    fun perclos() {
        // 40 s a 25 fps: ojos cerrados t=10..16 (peso 1.0), cabeceo t=20..22 (peso 0.55), bostezos en t=5 y t=12
        val tracker = PerclosTracker()
        tracker.yawnTimestamps.addLast(5.0)
        tracker.yawnTimestamps.addLast(12.0)
        val samples = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        while (t <= 40.0 + 1e-9) {
            val w = when {
                t >= 10.0 && t < 16.0 -> 1.0
                t >= 20.0 && t < 22.0 -> 0.55
                else -> 0.0
            }
            val p = tracker.update(t, w, windowSec = 30.0, minObsSec = 5.0, yawnBonusPct = 10.0, yawnMaxBonusPct = 20.0)
            samples.add(Pair(t, p))
            t += 0.04
        }
        val expected = mapOf(
            2.0 to 20.0,
            4.0 to 20.0,
            8.0 to 20.0,
            12.0 to 36.66666666666642,
            16.0 to 57.49999999999959,
            22.0 to 52.27272727272697,
            30.0 to 43.6666666666665,
            40.0 to 33.666666666666636,
        )
        for ((tc, exp) in expected) {
            val best = samples.minByOrNull { Math.abs(it.first - tc) }!!
            assertEquals("PERCLOS en t=$tc", exp, best.second, 1e-6)
        }
    }

    @Test
    fun landmarkMapper() {
        val n = 478
        val xs = FloatArray(n) { it / 1000f }
        val ys = FloatArray(n) { 0.5f }
        val f = LandmarkMapper.map(xs, ys, 640, 480)!!
        assertEquals(1 / 1000.0 * 640, f.noseTip.x, 1e-3)
        assertEquals(240.0, f.noseTip.y, 1e-3)
        assertEquals(33 / 1000.0 * 640, f.rightEye[0].x, 1e-3)
        assertEquals(263 / 1000.0 * 640, f.leftEye[3].x, 1e-3)
        assertEquals(14 / 1000.0 * 640, f.innerMouth[6].x, 1e-3)
    }
}
