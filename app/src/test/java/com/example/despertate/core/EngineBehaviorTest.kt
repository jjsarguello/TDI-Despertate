package com.example.despertate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escenarios sintéticos a 30 fps: calibración, ojos cerrados, bostezo, cabeceo y rostro ausente. */
class EngineBehaviorTest {

    private val dt = 1.0 / 30.0

    /** Rostro sintético: ear = apertura de ojos, gap = apertura de boca (MAR = gap/20), pitch = razón de cabeceo. */
    private fun face(ear: Double = 0.30, gap: Double = 2.0, pitch: Double = 0.7): FaceLandmarks {
        fun eye(x0: Double): Array<Pt> {
            val h = 2.0 * ear
            return arrayOf(
                Pt(x0, 0.0), Pt(x0 + 1, -h), Pt(x0 + 3, -h),
                Pt(x0 + 4, 0.0), Pt(x0 + 3, h), Pt(x0 + 1, h),
            )
        }
        val g = gap / 2.0
        val mouth = arrayOf(
            Pt(-10.0, 60.0), Pt(-5.0, 60.0 - g), Pt(0.0, 60.0 - g), Pt(5.0, 60.0 - g),
            Pt(10.0, 60.0), Pt(5.0, 60.0 + g), Pt(0.0, 60.0 + g), Pt(-5.0, 60.0 + g),
        )
        return FaceLandmarks(
            rightEye = eye(-24.0),
            leftEye = eye(20.0),
            innerMouth = mouth,
            noseBridge = Pt(0.0, 0.0),
            noseTip = Pt(0.0, 35.0),
            chin = Pt(0.0, 35.0 + 35.0 / pitch),
            jawRight = Pt(-50.0, 30.0),
            jawLeft = Pt(50.0, 30.0),
        )
    }

    private class Sim(val engine: DrowsinessEngine, val dt: Double) {
        var t = 0.0
        var last: EngineOutput? = null
        fun run(frames: Int, f: FaceLandmarks?): EngineOutput {
            repeat(frames) { last = engine.process(f, t); t += dt }
            return last!!
        }
    }

    private fun calibrated(): Sim {
        val sim = Sim(DrowsinessEngine(), dt)
        val out = sim.run(120, face())
        assertFalse(out.calibrating)
        assertEquals(DriverState.ACTIVO, out.state)
        assertFalse(out.alarm)
        return sim
    }

    @Test
    fun calibrationThenActive() {
        val sim = Sim(DrowsinessEngine(), dt)
        val early = sim.run(30, face())
        assertTrue(early.calibrating)
        assertEquals(DriverState.CALIBRANDO, early.state)
        calibrated()
    }

    @Test
    fun closedEyesTriggerAlarmAfterAbout1s() {
        val sim = calibrated()
        assertFalse(sim.run(27, face(ear = 0.10)).alarm)          // 0.9 s: todavía no
        val out = sim.run(15, face(ear = 0.10))                    // 1.4 s: alarma
        assertTrue(out.alarm)
        assertEquals(DriverState.PELIGRO, out.state)
        assertFalse(sim.run(9, face(ear = 0.30)).alarm)            // abre los ojos: se corta
    }

    @Test
    fun yawnIsSymptomButNeverAlarm() {
        val sim = calibrated()
        var anyAlarm = false
        repeat(90) {
            val out = sim.run(1, face(gap = 14.0))
            if (out.alarm) anyAlarm = true
        }
        assertFalse(anyAlarm)
        val out = sim.run(1, face(gap = 14.0))
        assertEquals(1, out.totalYawns)
        assertEquals(DriverState.SOMNOLIENTO, out.state)
    }

    @Test
    fun headNodTriggersAlarm() {
        val sim = calibrated()
        assertFalse(sim.run(15, face(pitch = 1.2)).alarm)          // 0.5 s: todavía no
        val out = sim.run(60, face(pitch = 1.2))                   // 2.5 s en total
        assertTrue(out.alarm)
        assertEquals(1, out.totalNods)
    }

    @Test
    fun noFaceStopsAlarm() {
        val sim = calibrated()
        assertTrue(sim.run(42, face(ear = 0.10)).alarm)
        val out = sim.run(1, null)
        assertFalse(out.alarm)
        assertEquals(DriverState.SIN_ROSTRO, out.state)
    }
}
