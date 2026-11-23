package com.example.despertate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureStatsTest {

    @Test
    fun percentilesMatchNumpy() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, MeasureStats.pct(v, 50.0), 1e-9)
        assertEquals(1.4, MeasureStats.pct(v, 10.0), 1e-9)
        assertEquals(4.6, MeasureStats.pct(v, 90.0), 1e-9)
    }

    @Test
    fun longestMarRunIsMeasuredInSeconds() {
        val m = MeasureStats(marThreshold = 0.62, earClosedRatio = 0.68)
        // 0.5 s con la boca abierta, pausa, 2.0 s con la boca abierta
        var t = 0.0
        repeat(6) { m.add("bostezo", t, 1.0, 0.8); t += 0.1 }
        m.add("bostezo", t, 1.0, 0.3); t += 0.1
        repeat(21) { m.add("bostezo", t, 1.0, 0.8); t += 0.1 }
        assertTrue(m.summary("bostezo").contains("racha MAR>0.62: 2.0s"))
    }

    @Test
    fun gapBreaksRun() {
        val m = MeasureStats(0.62, 0.68)
        m.add("x", 0.0, 0.5, 0.1)
        m.add("x", 0.1, 0.5, 0.1)
        m.add("x", 5.0, 0.5, 0.1) // hueco: no cuenta como racha continua de 5 s
        m.add("x", 5.1, 0.5, 0.1)
        assertTrue(m.summary("x").contains("racha EAR<0.68: 0.1s"))
    }
}
