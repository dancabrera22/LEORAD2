package com.just4fun2u.reader.ui

import android.graphics.Rect
import android.os.Build
import android.view.View

/**
 * Reserva as bordas laterais para o app durante a leitura, impedindo que
 * o deslize de virar página dispare os gestos de navegação do sistema
 * (voltar/avançar). Disponível a partir do Android 10.
 */
object Gestures {

    fun blockEdgeGestures(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val edge = (48 * v.resources.displayMetrics.density).toInt()
            v.systemGestureExclusionRects = listOf(
                Rect(0, 0, edge, v.height),
                Rect(v.width - edge, 0, v.width, v.height)
            )
        }
    }
}
