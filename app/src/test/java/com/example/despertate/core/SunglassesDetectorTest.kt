package com.example.despertate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SunglassesDetectorTest {

    private fun pts(vararg xy: Double) = Array(xy.size / 2) { Pt(xy[2 * it], xy[2 * it + 1]) }

    private val face = FaceLandmarks(
        rightEye = pts(100.0, 100.0, 110.0, 96.0, 120.0, 96.0, 130.0, 100.0, 120.0, 104.0, 110.0, 104.0),
        leftEye = pts(180.0, 100.0, 190.0, 96.0, 200.0, 96.0, 210.0, 100.0, 200.0, 104.0, 190.0, 104.0),
        innerMouth = pts(140.0, 200.0, 150.0, 198.0, 160.0, 198.0, 170.0, 200.0, 160.0, 204.0, 150.0, 204.0, 145.0, 202.0, 165.0, 202.0),
        noseBridge = Pt(155.0, 100.0),
        noseTip = Pt(155.0, 160.0),
        chin = Pt(155.0, 240.0),
        jawRight = Pt(80.0, 170.0),
        jawLeft = Pt(230.0, 170.0),
        rightBrow = pts(100.0, 80.0, 110.0, 76.0, 120.0, 76.0, 125.0, 78.0, 130.0, 80.0),
        leftBrow = pts(180.0, 80.0, 190.0, 76.0, 200.0, 76.0, 205.0, 78.0, 210.0, 80.0),
    )

    /** Órbita (y < 125) con luminancia 'orbit' y pómulo (y >= 125) con 'cheek'. */
    private fun image(orbit: Int, cheek: Int) = GrayRegion { x1, y1, x2, y2 ->
        val ww = x2 - x1
        IntArray(ww * (y2 - y1)) { i -> if (y1 + i / ww < 125) orbit else cheek }
    }

    @Test
    fun percentileMatchesNumpy() {
        assertEquals(7.12, SunglassesDetector.percentile(IntArray(10) { it + 1 }, 68.0), 1e-9)
        assertEquals(5.5, SunglassesDetector.percentile(IntArray(10) { it + 1 }, 50.0), 1e-9)
    }

    @Test
    fun darkLensDetected() {
        val r = SunglassesDetector.detect(face, 0.0, 320, 480, image(orbit = 40, cheek = 150))
        assertTrue(r.isDarkLens)
        assertTrue(r.ratio < 0.58)
    }

    @Test
    fun bareEyesNotDetected() {
        val r = SunglassesDetector.detect(face, 0.0, 320, 480, image(orbit = 135, cheek = 150))
        assertFalse(r.isDarkLens)
    }

    @Test
    fun engineAppliesHysteresisAndModes() {
        val e = DrowsinessEngine()
        var t = 0.0
        val f = face
        // 0.3 s de lentes crudos: todavía no se activa (histéresis 0.4 s)
        repeat(8) { e.process(f, t, { true }); t += 0.04 }
        assertFalse(e.sunglassesActive)
        repeat(6) { e.process(f, t, { true }); t += 0.04 }
        assertTrue(e.sunglassesActive)
        e.sunglassesMode = SunglassesMode.OFF
        e.process(f, t, { true })
        assertFalse(e.sunglassesActive)
    }
}
