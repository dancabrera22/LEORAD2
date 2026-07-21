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
import android.graphics.Path
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paginador com dobra de papel realista: o ponto onde o dedo segura a
 * folha (canto de cima, canto de baixo ou meio da borda) fica ancorado ao
 * dedo, e a página enrola em volta de um cilindro virtual cuja linha de
 * dobra é calculada a partir da posição do dedo — arraste em qualquer
 * direção e a dobra acompanha, como a ponta de uma página de livro de
 * verdade. O verso da folha aparece como papel creme fosco e opaco. A
 * virada solta tem peso e inércia: começa com a velocidade do gesto e
 * assenta devagar.
 *
 * Trabalha com bitmaps assíncronos de um [PageSource] (páginas de
 * quadrinhos ou capturas de WebView).
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
    private var animator: ValueAnimator? = null

    // ponto virtual do dedo (Fv) e ponto da folha que ele segura (P0)
    private var fx = 0f
    private var fy = 0f
    private var grabX = 0f
    private var grabY = 0f
    // âncora do dedo virtual no início do arrasto
    private var anchorX = 0f
    private var anchorY = 0f

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var attemptedOverscroll = 0
    private var dragSpeed = 0f

    // zoom
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint()
    private val creamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val creamPath = Path()

    private val meshCols = 32
    private val meshRows = 16
    private val verts = FloatArray((meshCols + 1) * (meshRows + 1) * 2)
    private val colors = IntArray((meshCols + 1) * (meshRows + 1))
    private val fitRect = RectF()

    companion object {
        /** quanto a folha "gruda" no dedo por quadro (perto de literal) */
        private const val FOLLOW = 0.8f
        private const val CREAM_LIGHT = 0xFFF7F3E6.toInt()
        private const val CREAM_DARK = 0xFFE6DFCB.toInt()
    }

    // ---------- API ----------

    fun setSource(src: PageSource, startIndex: Int) {
        animator?.cancel()
        generation++
        source = src
        cache.clear()
        pending.clear()
        currentIndex = startIndex.coerceIn(0, max(0, src.count - 1))
        state = State.IDLE
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
                downY = e.y
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
                val src = source ?: return true
                val totalX = downX - e2.x
                if (!dragging && abs(totalX) > 12f) {
                    val forward = totalX > 0
                    if (forward && currentIndex + 1 >= src.count) {
                        attemptedOverscroll = 1
                    } else if (!forward && currentIndex <= 0) {
                        attemptedOverscroll = -1
                    } else {
                        beginDrag(forward)
                    }
                }
                if (dragging) {
                    // o ponto seguro pela mão segue o dedo (quase) literalmente
                    val targetX = anchorX + (e2.x - downX)
                    val targetY = anchorY + (e2.y - downY)
                    fx += (targetX - fx) * FOLLOW
                    fy += (targetY - fy) * FOLLOW
                    dragSpeed += (abs(dx) - dragSpeed) * 0.3f
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
                    e.x > width * 0.72f -> startAnimatedTurn(forward = true, tapY = e.y)
                    e.x < width * 0.28f -> startAnimatedTurn(forward = false, tapY = e.y)
                    else -> onSingleTap?.invoke()
                }
                return true
            }
        })

    /** Escolhe o ponto da folha que a mão segura, conforme a altura do toque. */
    private fun pickGrabPoint(touchY: Float) {
        val bmp = cache[if (!turningBack) currentIndex else currentIndex - 1]
        if (bmp != null) computeFit(bmp) else fitRect.set(0f, 0f, width.toFloat(), height.toFloat())
        grabX = fitRect.right
        grabY = when {
            touchY < height / 3f -> fitRect.top + 2f          // ponta de cima
            touchY > height * 2f / 3f -> fitRect.bottom - 2f  // ponta de baixo
            else -> touchY.coerceIn(fitRect.top, fitRect.bottom) // meio da borda
        }
    }

    private fun beginDrag(forward: Boolean) {
        dragging = true
        state = State.TURNING
        turningBack = !forward
        pickGrabPoint(downY)
        if (forward) {
            // folha plana: o dedo começa segurando o ponto da folha
            anchorX = grabX
            anchorY = grabY
        } else {
            // folha anterior já virada: começa dobrada, fora da tela à esquerda
            anchorX = grabX - width * 1.45f
            anchorY = grabY
        }
        fx = anchorX
        fy = anchorY
        ensurePages()
    }

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
            val dist = hypot(fx - grabX, fy - grabY)
            val complete = if (!turningBack) {
                dist > width * 0.38f
            } else {
                dist < width * 0.55f
            }
            animateRelease(complete)
        } else if (attemptedOverscroll != 0) {
            if (attemptedOverscroll > 0) onOverscrollForward?.invoke()
            else onOverscrollBackward?.invoke()
            attemptedOverscroll = 0
        }
    }

    private fun startAnimatedTurn(forward: Boolean, tapY: Float? = null) {
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
        pickGrabPoint(tapY ?: (height / 2f))
        if (forward) {
            fx = grabX - 4f
            fy = grabY
        } else {
            fx = grabX - width * 1.45f
            fy = grabY
        }
        ensurePages()
        animateRelease(complete = true)
    }

    /**
     * Solta a folha: ela continua na direção em que ia (inércia) e assenta
     * devagar, como papel com peso.
     */
    private fun animateRelease(complete: Boolean) {
        // a folha "aberta" fica com o dedo virtual em P0; a folha "virada",
        // longe de P0, na direção em que estava sendo puxada
        val flatten = (!turningBack && !complete) || (turningBack && complete)
        val endX: Float
        val endY: Float
        if (flatten) {
            endX = grabX
            endY = grabY
        } else {
            var dirX = fx - grabX
            var dirY = fy - grabY
            val len = hypot(dirX, dirY)
            if (len < 1f) {
                dirX = -1f; dirY = 0f
            } else {
                dirX /= len; dirY /= len
            }
            // garante que a folha saia pela esquerda
            if (dirX > -0.35f) {
                dirX = -0.5f
                val n = hypot(dirX, dirY)
                dirX /= n; dirY /= n
            }
            val far = width * 2.3f
            endX = grabX + dirX * far
            endY = grabY + dirY * far
        }

        val startX = fx
        val startY = fy
        val travel = hypot(endX - startX, endY - startY)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            // página pesada: virada longa, começa com o embalo do gesto
            duration = (480 + 380 * (travel / width)).toLong().coerceIn(450L, 980L)
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener {
                val u = it.animatedValue as Float
                fx = startX + (endX - startX) * u
                fy = startY + (endY - startY) * u
                dragSpeed *= 0.94f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val turned = complete
                    if (turned) {
                        currentIndex += if (!turningBack) 1 else -1
                        onPageChanged?.invoke(currentIndex)
                    }
                    state = State.IDLE
                    dragSpeed = 0f
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

        val frontIdx = if (!turningBack) currentIndex else currentIndex - 1
        val underIdx = if (!turningBack) currentIndex + 1 else currentIndex

        val front = cache[frontIdx]
        val under = cache[underIdx]

        val dist = hypot(fx - grabX, fy - grabY)

        under?.let {
            computeFit(it)
            canvas.drawBitmap(it, null, fitRect, pagePaint)
        }

        if (front == null) return
        computeFit(front)

        if (dist < 2f) {
            // folha praticamente plana
            canvas.drawBitmap(front, null, fitRect, pagePaint)
            return
        }

        // ---- geometria da dobra a partir do dedo ----
        // normal da linha da dobra: aponta do dedo para o ponto seguro
        var nx = (grabX - fx) / dist
        var ny = (grabY - fy) / dist

        // raio do rolo: cresce conforme a folha é puxada; puxão rápido aperta
        val speedNorm = (dragSpeed / (width * 0.045f)).coerceIn(0f, 1f)
        val grip = (dist / (min(width, height) * 0.30f)).coerceIn(0f, 1f)
        val radius = (min(width, height) * 0.13f * grip * (1f - 0.35f * speedNorm))
            .coerceAtLeast(1f)
        val piR = (PI * radius).toFloat()

        // linha da dobra: perpendicular a n, posicionada para que o ponto
        // seguro (P0) caia exatamente no dedo depois de dobrado
        val d0 = (dist + piR) / 2f
        val ax = grabX - nx * d0
        val ay = grabY - ny * d0

        var dMax = 0f
        floatArrayOf(fitRect.left, fitRect.right).forEach { cx ->
            floatArrayOf(fitRect.top, fitRect.bottom).forEach { cyy ->
                val d = (cx - ax) * nx + (cyy - ay) * ny
                if (d > dMax) dMax = d
            }
        }
        // ---- malha da folha ----
        val cy = fitRect.centerY()
        var k = 0
        var ci = 0
        for (j in 0..meshRows) {
            val py = fitRect.top + fitRect.height() * j / meshRows
            for (i in 0..meshCols) {
                val px = fitRect.left + fitRect.width() * i / meshCols
                val d = (px - ax) * nx + (py - ay) * ny
                val disp: Float
                val lift: Float
                val shade: Float
                when {
                    d <= 0f -> {
                        disp = 0f; lift = 0f; shade = 1f
                    }
                    d < piR -> {
                        val theta = d / radius
                        disp = radius * sin(theta) - d
                        lift = (1f - cos(theta)) / 2f
                        shade = 0.78f + 0.22f * abs(cos(theta))
                    }
                    else -> {
                        disp = piR - 2f * d
                        lift = 1f
                        shade = 1f // o verso será coberto pelo papel creme
                    }
                }
                val x = px + nx * disp
                val y = py + ny * disp
                // papel bem mole: o que levanta cresce mais em direção ao olho
                val grow = 1f + 0.18f * lift
                verts[k++] = x
                verts[k++] = cy + (y - cy) * grow
                val v = (255f * shade).toInt().coerceIn(0, 255)
                colors[ci++] = Color.rgb(v, v, v)
            }
        }
        canvas.drawBitmapMesh(front, meshCols, meshRows, verts, 0, colors, 0, pagePaint)

        // ---- verso da folha: papel creme fosco, opaco ----
        drawBackFlap(canvas, nx, ny, ax, ay, radius, piR, cy)

        // ---- sombras no referencial da dobra ----
        canvas.save()
        canvas.rotate(
            Math.toDegrees(atan2(ny.toDouble(), nx.toDouble())).toFloat(),
            ax, ay
        )
        val yTop = -height * 1.5f
        val yBot = height * 2.5f
        if (dMax > piR) {
            val leadX = ax - (dMax - piR)
            shadowPaint.shader = LinearGradient(
                leadX, 0f, leadX - 64f, 0f,
                Color.argb(80, 0, 0, 0), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(leadX - 64f, yTop, leadX, yBot, shadowPaint)
        }
        if (dMax > 0f) {
            val theta = min(dMax / radius, (PI / 2).toFloat())
            val rollX = ax + radius * sin(theta)
            shadowPaint.shader = LinearGradient(
                rollX, 0f, rollX + 80f, 0f,
                Color.argb(70, 0, 0, 0), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(rollX, yTop, rollX + 80f, yBot, shadowPaint)
        }
        canvas.restore()
    }

    /**
     * Pinta o verso visível da folha (da crista do rolo até a ponta
     * dobrada) com papel creme fosco e opaco.
     */
    private fun drawBackFlap(
        canvas: Canvas,
        nx: Float, ny: Float, ax: Float, ay: Float,
        radius: Float, piR: Float, cy: Float
    ) {
        // recorta o retângulo da página pelo semiplano d >= piR/2
        // (a partir da crista do rolo o que se vê é o verso do papel)
        val poly = clipRectByHalfPlane(nx, ny, ax, ay, piR / 2f)
        if (poly.size < 6) return
        creamPath.reset()
        // o mapeamento da dobra é curvo: amostra cada aresta do polígono em
        // vários pontos para o contorno seguir a curva real, sem "vazar"
        // creme sobre áreas da página que não fazem parte do verso
        val samples = 14
        val n = poly.size / 2
        var started = false
        for (edge in 0 until n) {
            val x1 = poly[edge * 2]
            val y1 = poly[edge * 2 + 1]
            val x2 = poly[((edge + 1) % n) * 2]
            val y2 = poly[((edge + 1) % n) * 2 + 1]
            for (s in 0 until samples) {
                val f = s.toFloat() / samples
                val px = x1 + (x2 - x1) * f
                val py = y1 + (y2 - y1) * f
                val d = (px - ax) * nx + (py - ay) * ny
                val disp: Float
                val lift: Float
                if (d < piR) {
                    val theta = (d / radius).coerceAtLeast(0f)
                    disp = radius * sin(theta) - d
                    lift = (1f - cos(theta)) / 2f
                } else {
                    disp = piR - 2f * d
                    lift = 1f
                }
                val x = px + nx * disp
                val yRaw = py + ny * disp
                val grow = 1f + 0.18f * lift
                val y = cy + (yRaw - cy) * grow
                if (!started) {
                    creamPath.moveTo(x, y)
                    started = true
                } else {
                    creamPath.lineTo(x, y)
                }
            }
        }
        creamPath.close()
        // leve sombreado: mais escuro junto à crista, clareando na ponta
        val crestX = ax + nx * radius
        val crestY = ay + ny * radius
        creamPaint.shader = LinearGradient(
            crestX, crestY,
            crestX - nx * 180f, crestY - ny * 180f,
            CREAM_DARK, CREAM_LIGHT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(creamPath, creamPaint)
    }

    /** Recorta o retângulo da página pelo semiplano d >= minD (Sutherland–Hodgman). */
    private fun clipRectByHalfPlane(
        nx: Float, ny: Float, ax: Float, ay: Float, minD: Float
    ): FloatArray {
        val xs = floatArrayOf(fitRect.left, fitRect.right, fitRect.right, fitRect.left)
        val ys = floatArrayOf(fitRect.top, fitRect.top, fitRect.bottom, fitRect.bottom)
        val out = ArrayList<Float>(12)
        for (i in 0 until 4) {
            val x1 = xs[i]; val y1 = ys[i]
            val x2 = xs[(i + 1) % 4]; val y2 = ys[(i + 1) % 4]
            val d1 = (x1 - ax) * nx + (y1 - ay) * ny - minD
            val d2 = (x2 - ax) * nx + (y2 - ay) * ny - minD
            if (d1 >= 0f) {
                out.add(x1); out.add(y1)
                if (d2 < 0f) {
                    val f = d1 / (d1 - d2)
                    out.add(x1 + (x2 - x1) * f); out.add(y1 + (y2 - y1) * f)
                }
            } else if (d2 >= 0f) {
                val f = d1 / (d1 - d2)
                out.add(x1 + (x2 - x1) * f); out.add(y1 + (y2 - y1) * f)
            }
        }
        return out.toFloatArray()
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
