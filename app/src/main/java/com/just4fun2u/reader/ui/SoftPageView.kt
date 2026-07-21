package com.just4fun2u.reader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paginador com efeito de página "mole": ao virar, a folha se deforma
 * como papel flexível (malha deformada com drawBitmapMesh), acompanha o
 * dedo e projeta sombra sobre a página de baixo — como no Apple Livros.
 *
 * Trabalha com bitmaps fornecidos de forma assíncrona por um [PageSource],
 * o que permite usá-lo tanto para páginas de quadrinhos quanto para
 * capturas de página de um WebView.
 */
class SoftPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface PageSource {
        val count: Int
        /** Chamado na thread de UI; o callback também deve voltar na UI. */
        fun requestPage(index: Int, cb: (Bitmap?) -> Unit)
    }

    var onPageChanged: ((Int) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onOverscrollForward: (() -> Unit)? = null
    var onOverscrollBackward: (() -> Unit)? = null

    /** Habilita zoom por pinça/duplo toque (útil para quadrinhos). */
    var zoomEnabled = true

    var pageColorFilter: ColorFilter? = null
        set(value) {
            field = value
            pagePaint.colorFilter = value
            invalidate()
        }

    var currentIndex = 0
        private set

    private var source: PageSource? = null
    private val cache = LinkedHashMap<Int, Bitmap>()
    private val pending = mutableSetOf<Int>()
    private var generation = 0

    private enum class State { IDLE, TURNING }
    private var state = State.IDLE
    private var turningBack = false
    /** progresso visual da folha da frente: 0 = plana cobrindo, 1 = totalmente virada. */
    private var t = 0f
    private var animator: ValueAnimator? = null

    private var downX = 0f
    private var dragging = false
    private var attemptedOverscroll = 0

    // zoom
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    private val shadowPaint = Paint()
    private val bgPaint = Paint().apply { color = Color.BLACK }

    private val meshCols = 24
    private val meshRows = 16
    private val verts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)
    private val fitRect = RectF()

    // ---------- API ----------

    fun setSource(src: PageSource, startIndex: Int) {
        animator?.cancel()
        generation++
        source = src
        cache.clear()
        pending.clear()
        currentIndex = startIndex.coerceIn(0, max(0, src.count - 1))
        state = State.IDLE
        t = 0f
        resetZoom()
        ensurePages()
        invalidate()
        onPageChanged?.invoke(currentIndex)
    }

    fun jumpTo(index: Int) {
        val src = source ?: return
        val target = index.coerceIn(0, max(0, src.count - 1))
        if (target == currentIndex) return
        animator?.cancel()
        state = State.IDLE
        t = 0f
        currentIndex = target
        resetZoom()
        ensurePages()
        invalidate()
        onPageChanged?.invoke(currentIndex)
    }

    fun turnNext() = startAnimatedTurn(forward = true)
    fun turnPrev() = startAnimatedTurn(forward = false)

    // ---------- carregamento ----------

    private fun ensurePages() {
        val src = source ?: return
        val gen = generation
        intArrayOf(currentIndex, currentIndex + 1, currentIndex - 1).forEach { i ->
            if (i in 0 until src.count && !cache.containsKey(i) && !pending.contains(i)) {
                pending.add(i)
                src.requestPage(i) { bmp ->
                    if (gen != generation) return@requestPage
                    pending.remove(i)
                    if (bmp != null) {
                        cache[i] = bmp
                        trimCache()
                        invalidate()
                    }
                }
            }
        }
    }

    private fun trimCache() {
        val keep = (currentIndex - 1)..(currentIndex + 1)
        val it = cache.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in keep) it.remove()
        }
    }

    // ---------- gestos ----------

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled || state != State.IDLE) return false
                scale = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                clampPan()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                downX = e.x
                dragging = false
                attemptedOverscroll = 0
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (scale > 1.02f) {
                    panX -= dx
                    panY -= dy
                    clampPan()
                    invalidate()
                    return true
                }
                if (animator?.isRunning == true) return true
                val total = downX - e2.x
                val src = source ?: return true
                if (!dragging && abs(total) > 12f) {
                    val forward = total > 0
                    if (forward && currentIndex + 1 >= src.count) {
                        attemptedOverscroll = 1
                    } else if (!forward && currentIndex <= 0) {
                        attemptedOverscroll = -1
                    } else {
                        dragging = true
                        state = State.TURNING
                        turningBack = !forward
                        ensurePages()
                    }
                }
                if (dragging) {
                    val w = width * 0.85f
                    t = if (!turningBack) {
                        (total / w).coerceIn(0f, 1f)
                    } else {
                        (1f - (-total / w)).coerceIn(0f, 1f)
                    }
                    invalidate()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!zoomEnabled || state != State.IDLE) return false
                if (scale > 1.02f) {
                    resetZoom()
                } else {
                    scale = 2.4f
                    panX = (width / 2f - e.x) * (scale - 1f)
                    panY = (height / 2f - e.y) * (scale - 1f)
                    clampPan()
                }
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (state != State.IDLE || scale > 1.02f) {
                    onSingleTap?.invoke()
                    return true
                }
                when {
                    e.x > width * 0.72f -> startAnimatedTurn(forward = true)
                    e.x < width * 0.28f -> startAnimatedTurn(forward = false)
                    else -> onSingleTap?.invoke()
                }
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            onGestureEnd()
        }
        return true
    }

    private fun onGestureEnd() {
        if (dragging && state == State.TURNING) {
            dragging = false
            val complete = if (!turningBack) t > 0.28f else t < 0.72f
            animateTo(complete)
        } else if (attemptedOverscroll != 0 && abs(downX) >= 0f) {
            if (attemptedOverscroll > 0) onOverscrollForward?.invoke()
            else onOverscrollBackward?.invoke()
            attemptedOverscroll = 0
        }
    }

    private fun startAnimatedTurn(forward: Boolean) {
        val src = source ?: return
        if (state != State.IDLE || animator?.isRunning == true) return
        if (forward && currentIndex + 1 >= src.count) {
            onOverscrollForward?.invoke()
            return
        }
        if (!forward && currentIndex <= 0) {
            onOverscrollBackward?.invoke()
            return
        }
        state = State.TURNING
        turningBack = !forward
        t = if (!turningBack) 0f else 1f
        ensurePages()
        animateTo(complete = true)
    }

    private fun animateTo(complete: Boolean) {
        val target = if (!turningBack) {
            if (complete) 1f else 0f
        } else {
            if (complete) 0f else 1f
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(t, target).apply {
            duration = (240 * abs(t - target)).toLong().coerceAtLeast(90)
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val turned = if (!turningBack) t > 0.5f else t < 0.5f
                    if (turned) {
                        currentIndex += if (!turningBack) 1 else -1
                        onPageChanged?.invoke(currentIndex)
                    }
                    state = State.IDLE
                    t = 0f
                    ensurePages()
                    trimCache()
                    invalidate()
                }
            })
            start()
        }
    }

    // ---------- zoom ----------

    private fun resetZoom() {
        scale = 1f
        panX = 0f
        panY = 0f
    }

    private fun clampPan() {
        val maxX = (scale - 1f) * width / 2f
        val maxY = (scale - 1f) * height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    // ---------- desenho ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val src = source ?: return
        if (src.count == 0) return

        if (state == State.IDLE) {
            val bmp = cache[currentIndex] ?: return
            canvas.save()
            if (scale > 1f) {
                canvas.translate(panX, panY)
                canvas.scale(scale, scale, width / 2f, height / 2f)
            }
            computeFit(bmp)
            canvas.drawBitmap(bmp, null, fitRect, pagePaint)
            canvas.restore()
            return
        }

        // virando: define quem é a folha da frente e a de baixo
        val frontIdx = if (!turningBack) currentIndex else currentIndex - 1
        val underIdx = if (!turningBack) currentIndex + 1 else currentIndex

        cache[underIdx]?.let { under ->
            computeFit(under)
            canvas.drawBitmap(under, null, fitRect, pagePaint)
        }
        // sombra da folha sobre a página de baixo
        scrimPaint.color = Color.BLACK
        scrimPaint.alpha = (90 * (1f - t)).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        cache[frontIdx]?.let { front -> drawSoftPage(canvas, front, t) }
    }

    /**
     * Desenha a folha deformada: quanto mais vira, mais a borda direita
     * "lidera" o movimento e a página se curva verticalmente perto da
     * borda que está sendo puxada — papel mole, não placa rígida.
     */
    private fun drawSoftPage(canvas: Canvas, bmp: Bitmap, t: Float) {
        computeFit(bmp)
        val lift = sin(PI * t).toFloat()
        val cy = fitRect.centerY()
        val travel = width * 1.25f
        var k = 0
        for (j in 0..meshRows) {
            val py = fitRect.top + fitRect.height() * j / meshRows
            for (i in 0..meshCols) {
                val p = i.toFloat() / meshCols
                val px = fitRect.left + fitRect.width() * p
                // a borda direita (p=1) anda mais rápido que a esquerda:
                // a folha estica e dobra em vez de deslizar rígida
                val shift = t * travel * (0.55f + 0.45f * p * p)
                // leve ondulação no meio da folha durante a virada
                val wave = sin(p * PI).toFloat() * lift * width * 0.015f
                val x = px - shift - wave
                // pinça vertical perto da borda puxada: curvatura de papel
                val y = py + (py - cy) * (-0.16f * lift * p * p)
                verts[k++] = x
                verts[k++] = y
            }
        }
        canvas.drawBitmapMesh(bmp, meshCols, meshRows, verts, 0, null, 0, pagePaint)

        // sombra projetada à frente da borda que levanta
        val edgeX = fitRect.right - t * travel
        if (lift > 0.02f && edgeX > -80f && edgeX < width + 80f) {
            val strength = (110 * lift).toInt().coerceIn(0, 255)
            shadowPaint.shader = LinearGradient(
                edgeX, 0f, edgeX + 90f, 0f,
                Color.argb(strength, 0, 0, 0), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(edgeX, 0f, min(edgeX + 90f, width.toFloat()), height.toFloat(), shadowPaint)
        }
    }

    private fun computeFit(bmp: Bitmap) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val s = min(vw / bmp.width, vh / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        fitRect.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
    }
}
