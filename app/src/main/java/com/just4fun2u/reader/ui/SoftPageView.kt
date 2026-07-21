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
import kotlin.math.cos
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

    private val meshCols = 32
    private val meshRows = 16
    private val verts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)
    private val colors = IntArray((meshCols + 1) * (meshRows + 1))
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
     * Desenha a folha sendo puxada e dobrada PARA FRENTE (em direção ao
     * leitor): a página enrola em volta de um cilindro virtual na linha da
     * dobra — a parte levantada cresce (aproxima-se do olho), passa por
     * cima da própria página e mostra o verso, com o conteúdo esmaecido
     * como papel visto por trás. A dobra varre a tela acompanhando o dedo.
     */
    private fun drawSoftPage(canvas: Canvas, bmp: Bitmap, t: Float) {
        computeFit(bmp)
        val cy = fitRect.centerY()
        // raio do rolo: menor no início/fim da virada, cheio no meio
        val radius = min(width, height) * (0.09f + 0.06f * sin(PI * t).toFloat())
        val piR = (PI * radius).toFloat()
        // a linha da dobra varre da borda direita até tudo sair pela esquerda
        val aStart = fitRect.right
        val aEnd = (-width - piR) / 2f - 48f
        val a = aStart + (aEnd - aStart) * t

        var k = 0
        var ci = 0
        for (j in 0..meshRows) {
            val py = fitRect.top + fitRect.height() * j / meshRows
            for (i in 0..meshCols) {
                val px = fitRect.left + fitRect.width() * i / meshCols
                val d = px - a
                val x: Float
                val lift: Float   // 0 = plano na página, 1 = altura máxima (2R, dobrado)
                val shade: Float
                when {
                    d <= 0f -> {
                        // ainda plano, antes da dobra
                        x = px
                        lift = 0f
                        shade = 1f
                    }
                    d < piR -> {
                        // enrolando no cilindro: sobe em direção ao leitor
                        val theta = d / radius
                        x = a + radius * sin(theta)
                        lift = (1f - cos(theta)) / 2f
                        // mais escuro quando a superfície fica de perfil (topo do rolo)
                        shade = 0.78f + 0.22f * abs(cos(theta))
                    }
                    else -> {
                        // já dobrado: volta por cima da página, mostrando o verso
                        x = a - (d - piR)
                        lift = 1f
                        shade = 0.90f
                    }
                }
                // o que levanta fica mais perto do olho: cresce verticalmente
                val grow = 1f + 0.14f * lift
                verts[k++] = x
                verts[k++] = cy + (py - cy) * grow
                val v = (255f * shade).toInt().coerceIn(0, 255)
                colors[ci++] = Color.rgb(v, v, v)
            }
        }
        canvas.drawBitmapMesh(bmp, meshCols, meshRows, verts, 0, colors, 0, pagePaint)

        val dMax = fitRect.right - a
        // sombra da aba dobrada sobre a parte plana da própria página
        if (dMax > piR) {
            val leadX = a - (dMax - piR)
            if (leadX > -80f && leadX < width + 80f) {
                shadowPaint.shader = LinearGradient(
                    leadX, 0f, leadX - 64f, 0f,
                    Color.argb(80, 0, 0, 0), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(max(leadX - 64f, 0f), 0f, leadX, height.toFloat(), shadowPaint)
            }
        }
        // sombra do rolo sobre a página de baixo, à direita da dobra
        if (dMax > 0f) {
            val theta = min(dMax / radius, (PI / 2).toFloat())
            val rollX = a + radius * sin(theta)
            if (rollX > -80f && rollX < width + 80f) {
                shadowPaint.shader = LinearGradient(
                    rollX, 0f, rollX + 80f, 0f,
                    Color.argb(70, 0, 0, 0), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rollX, 0f, min(rollX + 80f, width.toFloat()), height.toFloat(), shadowPaint)
            }
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
