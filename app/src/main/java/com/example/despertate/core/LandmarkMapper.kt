package com.example.despertate.core

/**
 * Convierte los 478 landmarks de MediaPipe Face Landmarker al subconjunto estilo dlib-68
 * que usa el sistema original. Las coordenadas de entrada son normalizadas [0,1] y se pasan
 * a píxeles con el ancho/alto de la imagen analizada (ya rotada), para que las razones
 * (EAR, MAR, pitch) se calculen igual que en el script de Python.
 *
 * Correspondencia (dlib -> MediaPipe):
 *   ojo derecho 36..41  -> 33, 160, 158, 133, 153, 144
 *   ojo izquierdo 42..47 -> 362, 385, 387, 263, 373, 380
 *   labio interno 60..67 -> 78, 81, 13, 311, 308, 402, 14, 178
 *   entrecejo 27 -> 168 | punta de nariz 30 -> 1 | mentón 8 -> 152
 *   borde mandíbula 0 -> 234 | borde mandíbula 16 -> 454
 *   ceja derecha 17..21 -> 70, 63, 105, 66, 107 | ceja izquierda 22..26 -> 336, 296, 334, 293, 300
 */
object LandmarkMapper {
    private val RIGHT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
    private val LEFT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)
    private val INNER_MOUTH = intArrayOf(78, 81, 13, 311, 308, 402, 14, 178)
    private val RIGHT_BROW = intArrayOf(70, 63, 105, 66, 107)
    private val LEFT_BROW = intArrayOf(336, 296, 334, 293, 300)
    private const val NOSE_BRIDGE = 168
    private const val NOSE_TIP = 1
    private const val CHIN = 152
    private const val JAW_RIGHT = 234
    private const val JAW_LEFT = 454

    const val MIN_LANDMARKS = 455

    fun map(xs: FloatArray, ys: FloatArray, width: Int, height: Int): FaceLandmarks? {
        if (xs.size < MIN_LANDMARKS || ys.size < MIN_LANDMARKS) return null
        val w = width.toDouble()
        val h = height.toDouble()
        fun p(i: Int) = Pt(xs[i] * w, ys[i] * h)
        return FaceLandmarks(
            rightEye = Array(RIGHT_EYE.size) { p(RIGHT_EYE[it]) },
            leftEye = Array(LEFT_EYE.size) { p(LEFT_EYE[it]) },
            innerMouth = Array(INNER_MOUTH.size) { p(INNER_MOUTH[it]) },
            noseBridge = p(NOSE_BRIDGE),
            noseTip = p(NOSE_TIP),
            chin = p(CHIN),
            jawRight = p(JAW_RIGHT),
            jawLeft = p(JAW_LEFT),
            rightBrow = Array(RIGHT_BROW.size) { p(RIGHT_BROW[it]) },
            leftBrow = Array(LEFT_BROW.size) { p(LEFT_BROW[it]) },
        )
    }
}
