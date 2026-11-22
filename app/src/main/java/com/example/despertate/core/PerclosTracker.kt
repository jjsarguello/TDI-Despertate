package com.example.despertate.core

/**
 * Port de update_perclos(): ventana deslizante en segundos reales, ponderada por la duración
 * de cada muestra, con bono por bostezos.
 */
class PerclosTracker {
    // cada muestra: [timestamp, peso]
    private val history = ArrayDeque<DoubleArray>()
    val yawnTimestamps = ArrayDeque<Double>()

    fun clear() {
        history.clear()
        yawnTimestamps.clear()
    }

    fun update(
        nowTs: Double,
        weight: Double,
        windowSec: Double,
        minObsSec: Double,
        yawnBonusPct: Double,
        yawnMaxBonusPct: Double,
        maxFrameDt: Double = 0.5,
    ): Double {
        history.addLast(doubleArrayOf(nowTs, weight.coerceIn(0.0, 1.0)))
        while (history.isNotEmpty() && (nowTs - history.first()[0]) > windowSec) {
            history.removeFirst()
        }

        var yawnBonus = 0.0
        if (yawnBonusPct > 0.0) {
            while (yawnTimestamps.isNotEmpty() && (nowTs - yawnTimestamps.first()) > windowSec) {
                yawnTimestamps.removeFirst()
            }
            yawnBonus = minOf(yawnTimestamps.size * yawnBonusPct, yawnMaxBonusPct)
        }

        if (history.size < 2) return 0.0

        var closed = 0.0
        var total = 0.0
        for (i in 1 until history.size) {
            val dt = history[i][0] - history[i - 1][0]
            if (dt <= 0.0) continue
            val dtc = minOf(dt, maxFrameDt)
            total += dtc
            val w = history[i][1]
            if (w > 0.0) closed += dtc * w
        }

        if (total < minObsSec) return minOf(100.0, yawnBonus)

        val base = (closed / total) * 100.0
        return minOf(100.0, base + yawnBonus)
    }
}
