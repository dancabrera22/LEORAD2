package com.just4fun2u.reader.ui

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.just4fun2u.reader.R
import kotlin.math.abs

/**
 * Efeito de virada de página estilo revista: a página atual gira em 3D
 * em torno da lombada (borda esquerda) por cima da próxima, com
 * sombreamento progressivo na página de baixo.
 */
class FlipPageTransformer : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val scrim = page.findViewById<View>(R.id.pageScrim)
        page.cameraDistance = page.width * 12f
        // cancela o deslize padrão: todas as páginas ficam empilhadas
        page.translationX = -position * page.width

        when {
            position <= -1f || position >= 1f -> {
                page.alpha = 0f
                page.rotationY = 0f
                scrim?.alpha = 0f
            }
            position < 0f -> {
                // página sendo virada: pivô na lombada, gira por cima
                page.alpha = 1f
                page.pivotX = 0f
                page.pivotY = page.height / 2f
                page.rotationY = 90f * position
                page.translationZ = 1f
                // leve escurecimento da própria página conforme vira
                scrim?.alpha = 0.35f * -position
            }
            else -> {
                // página de baixo: parada, sombreada enquanto a de cima vira
                page.alpha = 1f
                page.rotationY = 0f
                page.translationZ = 0f
                scrim?.alpha = 0.45f * abs(position)
            }
        }
    }
}
