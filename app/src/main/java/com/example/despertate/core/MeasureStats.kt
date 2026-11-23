package com.example.despertate.core

import java.util.Locale

/**
 * Registro para calibrar umbrales en el celular: acumula EAR (relativo a tu base calibrada)
 * y MAR por situación ("hablar", "bostezo", ...) y resume percentiles y la racha continua
 * más larga por encima/debajo de cada umbral. Lógica pura, sin Android.
 */
class MeasureStats(
    private val marThreshold: Double,
    private val earClosedRatio: Double,
) {
    private class Series {
        val ear = ArrayList<Double>()
        val mar = ArrayList<Double>()
        var lastT = Double.NaN
        var marRunStart = Double.NaN
        var earRunStart = Double.NaN
        var maxMarRun = 0.0
        var maxEarRun = 0.0
    }

    private val series = LinkedHashMap<String, Series>()

    fun reset() = series.clear()

    /** earRatio = EAR / EAR base calibrado. tSec en segundos monótonos. */
    fun add(label: String, tSec: Double, earRatio: Double, mar: Double) {
        val s = series.getOrPut(label) { Series() }
        // Un hueco largo (rostro perdido o se cambió de etiqueta) corta las rachas.
        if (!s.lastT.isNaN() && tSec - s.lastT > 0.5) {
            s.marRunStart = Double.NaN
            s.earRunStart = Double.NaN
        }
        s.lastT = tSec
        s.ear.add(earRatio)
        s.mar.add(mar)

        if (mar > marThreshold) {
            if (s.marRunStart.isNaN()) s.marRunStart = tSec
            s.maxMarRun = maxOf(s.maxMarRun, tSec - s.marRunStart)
        } else {
            s.marRunStart = Double.NaN
        }
        if (earRatio < earClosedRatio) {
            if (s.earRunStart.isNaN()) s.earRunStart = tSec
            s.maxEarRun = maxOf(s.maxEarRun, tSec - s.earRunStart)
        } else {
            s.earRunStart = Double.NaN
        }
    }

    /** Cortar rachas al cambiar de situación. */
    fun breakRuns() {
        for (s in series.values) {
            s.marRunStart = Double.NaN
            s.earRunStart = Double.NaN
            s.lastT = Double.NaN
        }
    }

    fun summary(label: String? = null): String {
        val keys = if (label != null) listOf(label) else series.keys.toList()
        val sb = StringBuilder()
        for (k in keys) {
            val s = series[k] ?: continue
            if (s.ear.isEmpty()) continue
            sb.append(
                String.format(
                    Locale.US,
                    "%s (n=%d)\n  EAR/base  p10 %.2f  p50 %.2f  p90 %.2f  min %.2f\n" +
                        "  MAR       p10 %.2f  p50 %.2f  p90 %.2f  max %.2f\n" +
                        "  racha MAR>%.2f: %.1fs | racha EAR<%.2f: %.1fs\n",
                    k, s.ear.size,
                    pct(s.ear, 10.0), pct(s.ear, 50.0), pct(s.ear, 90.0), s.ear.min(),
                    pct(s.mar, 10.0), pct(s.mar, 50.0), pct(s.mar, 90.0), s.mar.max(),
                    marThreshold, s.maxMarRun, earClosedRatio, s.maxEarRun,
                ),
            )
        }
        return sb.toString().ifEmpty { "Sin datos todavía" }
    }

    companion object {
        /** Percentil con interpolación lineal (como numpy). */
        fun pct(values: List<Double>, p: Double): Double {
            val v = values.sorted()
            if (v.size == 1) return v[0]
            val pos = (p / 100.0) * (v.size - 1)
            val lo = pos.toInt()
            val hi = minOf(lo + 1, v.size - 1)
            return v[lo] + (v[hi] - v[lo]) * (pos - lo)
        }
    }
}
