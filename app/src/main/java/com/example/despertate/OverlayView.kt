package com.example.despertate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.despertate.core.FaceLandmarks
import com.example.despertate.core.Pt
import kotlin.math.max

/**
 * Dibuja el cuadro analizado (FILL_CENTER, espejado como cámara frontal) y encima
 * ojos, labio interno y eje facial.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var frame: Bitmap? = null
    private var face: FaceLandmarks? = null
    private var imgW = 0
    private var imgH = 0
    private var eyeColor = Color.GREEN
    private var mouthColor = Color.rgb(0, 120, 255)
    private var axisColor = Color.CYAN

    var mirror: Boolean = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val path = Path()

    fun update(
        frame: Bitmap?,
        face: FaceLandmarks?,
        imageWidth: Int,
        imageHeight: Int,
        eyeColor: Int,
        mouthColor: Int,
        axisColor: Int,
    ) {
        this.frame = frame
        this.face = face
        this.imgW = imageWidth
        this.imgH = imageHeight
        this.eyeColor = eyeColor
        this.mouthColor = mouthColor
        this.axisColor = axisColor
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imgW <= 0 || imgH <= 0 || width == 0 || height == 0) return

        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f

        // El cuadro que se analizó (no la vista en vivo): imagen y landmarks quedan siempre sincronizados.
        frame?.let { bmp ->
            canvas.save()
            if (mirror) canvas.scale(-1f, 1f, width / 2f, height / 2f)
            canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + imgW * scale, dy + imgH * scale), null)
            canvas.restore()
        }

        val f = face ?: return

        fun px(p: Pt): Float {
            val x = (p.x * scale + dx).toFloat()
            return if (mirror) width - x else x
        }
        fun py(p: Pt): Float = (p.y * scale + dy).toFloat()

        paint.color = eyeColor
        drawClosed(canvas, f.leftEye, ::px, ::py)
        drawClosed(canvas, f.rightEye, ::px, ::py)

        paint.color = mouthColor
        drawClosed(canvas, f.innerMouth, ::px, ::py)

        paint.color = axisColor
        paint.strokeWidth = 5f
        canvas.drawLine(px(f.noseBridge), py(f.noseBridge), px(f.noseTip), py(f.noseTip), paint)
        canvas.drawLine(px(f.noseTip), py(f.noseTip), px(f.chin), py(f.chin), paint)
        paint.strokeWidth = 3f
        canvas.drawCircle(px(f.noseTip), py(f.noseTip), 6f, dotPaint)
    }

    private fun drawClosed(
        canvas: Canvas,
        pts: Array<Pt>,
        px: (Pt) -> Float,
        py: (Pt) -> Float,
    ) {
        path.reset()
        pts.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(px(p), py(p)) else path.lineTo(px(p), py(p))
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
