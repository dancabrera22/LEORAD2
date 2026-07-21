package com.just4fun2u.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Contêiner de zoom por pinça para o modo de rolagem: amplia o conteúdo
 * em volta do ponto da pinça, mantém a rolagem vertical do RecyclerView
 * funcionando e trata o arrasto horizontal como deslocamento quando
 * ampliado.
 */
class PinchZoomFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var scale = 1f
    private var panning = false
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val child = getChildAt(0) ?: return false
                val old = scale
                scale = (scale * detector.scaleFactor).coerceIn(1f, 3f)
                if (scale == old) return true

                child.pivotX = 0f
                child.pivotY = 0f
                child.scaleX = scale
                child.scaleY = scale

                // mantém o ponto entre os dedos parado na tela
                val fx = detector.focusX
                var tx = fx - (fx - child.translationX) * (scale / old)
                tx = tx.coerceIn(width * (1f - scale), 0f)
                child.translationX = tx

                val fy = detector.focusY
                val dyChild = fy * (1f / old - 1f / scale)
                (child as? RecyclerView)?.scrollBy(0, dyChild.roundToInt())

                if (scale <= 1.001f) {
                    scale = 1f
                    child.translationX = 0f
                    child.scaleX = 1f
                    child.scaleY = 1f
                }
                return true
            }
        })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                panning = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // pinça: assume o gesto
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scale > 1.02f && ev.pointerCount == 1) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!panning && abs(dx) > 24f && abs(dx) > abs(dy) * 1.2f) {
                        panning = true
                        lastX = ev.x
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (panning && !scaleDetector.isInProgress && ev.pointerCount == 1) {
                    val child = getChildAt(0) ?: return true
                    val dx = ev.x - lastX
                    lastX = ev.x
                    child.translationX =
                        (child.translationX + dx).coerceIn(width * (1f - scale), 0f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }
}
