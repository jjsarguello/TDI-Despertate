package com.example.despertate

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Port de preprocess_gray_clahe (TDI en el dominio espacial):
 *  1. escala de grises;
 *  2. luminancia de referencia medida SOLO en el rostro (40% media + 60% percentil 75);
 *  3. corrección gamma adaptativa (0.45..3.0) hacia luminancia objetivo 110, con compresión
 *     suave de altas luces si hay mucha luz;
 *  4. CLAHE (clip 1.5, grilla 8x8) para uniformizar sombras laterales.
 * El resultado se devuelve como bitmap gris (R=G=B) para dárselo a MediaPipe.
 */
class PreprocessResult(val bitmap: Bitmap, val gamma: Double, val refLum: Double)

object ImagePreprocessor {

    private const val TARGET_LUM = 110.0
    private const val CLIP_LIMIT = 1.5
    private const val TILES = 8

    /** faceBox = [x1, y1, x2, y2] del cuadro anterior, o null. */
    fun process(src: Bitmap, faceBox: IntArray?): PreprocessResult {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255) + 500) / 1000
        }

        // Luminancia de referencia en el rostro (o centro de la imagen si todavía no hay rostro)
        var x1 = 0; var y1 = 0; var x2 = w; var y2 = h
        var roiOk = false
        if (faceBox != null) {
            x1 = max(0, faceBox[0]); y1 = max(0, faceBox[1])
            x2 = min(w, faceBox[2]); y2 = min(h, faceBox[3])
            roiOk = (x2 - x1) * (y2 - y1) >= 100
        }
        if (!roiOk) {
            x1 = (w * 0.30).toInt(); x2 = (w * 0.70).toInt()
            y1 = (h * 0.25).toInt(); y2 = (h * 0.75).toInt()
        }
        val roi = IntArray((x2 - x1) * (y2 - y1))
        var k = 0
        var sum = 0.0
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val v = gray[y * w + x]
                roi[k++] = v
                sum += v
            }
        }
        val mean = sum / roi.size
        val p75 = com.example.despertate.core.SunglassesDetector.percentile(roi, 75.0)
        val refLum = 0.40 * mean + 0.60 * p75

        // Gamma adaptativo
        val normRef = (refLum / 255.0).coerceIn(0.05, 0.96)
        val normTarget = (TARGET_LUM / 255.0).coerceIn(0.05, 0.96)
        val gamma = (ln(normTarget) / ln(normRef)).coerceIn(0.45, 3.00)
        val lut = IntArray(256) { i ->
            var x = i / 255.0
            if (gamma > 1.15 && x > 0.75) x = 0.75 + (x - 0.75) * 0.55
            floor((x.pow(gamma) * 255.0).coerceIn(0.0, 255.0)).toInt()
        }
        for (i in gray.indices) gray[i] = lut[gray[i]]

        clahe(gray, w, h)

        for (i in gray.indices) {
            val g = gray[i]
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return PreprocessResult(out, gamma, refLum)
    }

    /** CLAHE al estilo OpenCV (8 bits): histograma recortado por tile + interpolación bilineal. */
    private fun clahe(g: IntArray, w: Int, h: Int) {
        val tw = (w + TILES - 1) / TILES
        val th = (h + TILES - 1) / TILES
        val tileArea = tw * th
        val clip = max((CLIP_LIMIT * tileArea / 256.0).toInt(), 1)
        val scale = 255.0 / tileArea

        val luts = Array(TILES * TILES) { IntArray(256) }
        val hist = IntArray(256)
        for (ty in 0 until TILES) {
            for (tx in 0 until TILES) {
                hist.fill(0)
                val xs = tx * tw
                val ys = ty * th
                val xe = min(w, xs + tw)
                val ye = min(h, ys + th)
                for (y in ys until ye) {
                    val row = y * w
                    for (x in xs until xe) hist[g[row + x]]++
                }
                var clipped = 0
                for (i in 0 until 256) {
                    if (hist[i] > clip) {
                        clipped += hist[i] - clip
                        hist[i] = clip
                    }
                }
                val batch = clipped / 256
                var residual = clipped - batch * 256
                for (i in 0 until 256) hist[i] += batch
                if (residual > 0) {
                    val step = max(256 / residual, 1)
                    var i = 0
                    while (i < 256 && residual > 0) {
                        hist[i]++
                        residual--
                        i += step
                    }
                }
                val lut = luts[ty * TILES + tx]
                var acc = 0
                for (i in 0 until 256) {
                    acc += hist[i]
                    lut[i] = min(255, (acc * scale + 0.5).toInt())
                }
            }
        }

        // Interpolación bilineal entre los centros de los tiles
        val cx1 = IntArray(w)
        val cx2 = IntArray(w)
        val cxa = FloatArray(w)
        for (x in 0 until w) {
            val f = x / tw.toFloat() - 0.5f
            val a = floor(f).toInt()
            cxa[x] = f - a
            cx1[x] = max(a, 0)
            cx2[x] = min(a + 1, TILES - 1)
        }
        for (y in 0 until h) {
            val f = y / th.toFloat() - 0.5f
            val a = floor(f).toInt()
            val ya = f - a
            val ty1 = max(a, 0)
            val ty2 = min(a + 1, TILES - 1)
            val row = y * w
            for (x in 0 until w) {
                val v = g[row + x]
                val xa = cxa[x]
                val l11 = luts[ty1 * TILES + cx1[x]][v]
                val l12 = luts[ty1 * TILES + cx2[x]][v]
                val l21 = luts[ty2 * TILES + cx1[x]][v]
                val l22 = luts[ty2 * TILES + cx2[x]][v]
                val top = l11 * (1 - xa) + l12 * xa
                val bot = l21 * (1 - xa) + l22 * xa
                g[row + x] = min(255, (top * (1 - ya) + bot * ya + 0.5f).toInt())
            }
        }
    }
}
