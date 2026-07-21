package com.just4fun2u.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ImageView com pinça para zoom, pan e duplo toque, compatível com
 * ViewPager2: só deixa o pager interceptar quando a imagem está na borda.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onSingleTap: (() -> Unit)? = null

    private val matrixValues = FloatArray(9)
    private val imageMatrixInternal = Matrix()
    private var baseScale = 1f
    private val maxScale get() = baseScale * 5f

    private var drawableWidth = 0f
    private var drawableHeight = 0f

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                var factor = detector.scaleFactor
                if (current * factor > maxScale) factor = maxScale / current
                if (current * factor < baseScale) factor = baseScale / current
                imageMatrixInternal.postScale(factor, factor, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = imageMatrixInternal
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (currentScale() > baseScale * 1.01f) {
                    imageMatrixInternal.postTranslate(-dx, -dy)
                    fixTranslation()
                    imageMatrix = imageMatrixInternal
                    // se ainda dá para arrastar horizontalmente, segura o pager
                    parent?.requestDisallowInterceptTouchEvent(canPanHorizontally(dx))
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() > baseScale * 1.5f) baseScale else baseScale * 2.5f
                val factor = target / currentScale()
                imageMatrixInternal.postScale(factor, factor, e.x, e.y)
                fixTranslation()
                imageMatrix = imageMatrixInternal
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }
        })

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        drawableWidth = drawable?.intrinsicWidth?.toFloat() ?: 0f
        drawableHeight = drawable?.intrinsicHeight?.toFloat() ?: 0f
        fitImage()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImage()
    }

    private fun fitImage() {
        if (drawableWidth <= 0 || drawableHeight <= 0 || width == 0 || height == 0) return
        baseScale = min(width / drawableWidth, height / drawableHeight)
        imageMatrixInternal.reset()
        imageMatrixInternal.postScale(baseScale, baseScale)
        imageMatrixInternal.postTranslate(
            (width - drawableWidth * baseScale) / 2f,
            (height - drawableHeight * baseScale) / 2f
        )
        imageMatrix = imageMatrixInternal
    }

    private fun currentScale(): Float {
        imageMatrixInternal.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun fixTranslation() {
        imageMatrixInternal.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val imgW = drawableWidth * scale
        val imgH = drawableHeight * scale

        val fixedX = clampTrans(transX, width.toFloat(), imgW)
        val fixedY = clampTrans(transY, height.toFloat(), imgH)
        imageMatrixInternal.postTranslate(fixedX - transX, fixedY - transY)
    }

    private fun clampTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            (viewSize - contentSize) / 2f
        } else {
            max(viewSize - contentSize, min(0f, trans))
        }
    }

    private fun canPanHorizontally(dx: Float): Boolean {
        imageMatrixInternal.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val imgW = drawableWidth * scale
        if (imgW <= width + 1f) return false
        val atLeftEdge = transX >= -1f
        val atRightEdge = transX <= width - imgW + 1f
        // dx > 0 = dedo indo para a esquerda (avançar conteúdo à direita)
        return if (dx > 0) !atRightEdge else !atLeftEdge
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && currentScale() > baseScale * 1.01f) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
