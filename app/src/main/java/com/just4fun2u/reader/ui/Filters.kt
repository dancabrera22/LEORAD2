package com.just4fun2u.reader.ui

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.just4fun2u.reader.R
import com.just4fun2u.reader.data.ReadFilter

/** Filtros de leitura: matriz de cores para imagens e CSS para o WebView. */
object Filters {

    fun label(context: Context, f: ReadFilter): String = context.getString(
        when (f) {
            ReadFilter.NONE -> R.string.filter_none
            ReadFilter.PAPER -> R.string.filter_paper
            ReadFilter.VINTAGE -> R.string.filter_vintage
            ReadFilter.SEPIA -> R.string.filter_sepia
            ReadFilter.MONO -> R.string.filter_mono
            ReadFilter.DALTONIC -> R.string.filter_daltonic
        }
    )

    fun colorFilter(f: ReadFilter): ColorFilter? = when (f) {
        ReadFilter.NONE -> null

        // tom levemente creme, como papel de revista antiga
        ReadFilter.PAPER -> ColorMatrixColorFilter(
            floatArrayOf(
                0.96f, 0f, 0f, 0f, 8f,
                0f, 0.93f, 0f, 0f, 6f,
                0f, 0f, 0.84f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // saturação reduzida + tom quente desbotado
        ReadFilter.VINTAGE -> ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0.68f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.08f, 0f, 0f, 0f, 12f,
                            0f, 1.0f, 0f, 0f, 6f,
                            0f, 0f, 0.86f, 0f, -6f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
        )

        ReadFilter.SEPIA -> ColorMatrixColorFilter(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        ReadFilter.MONO -> ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0f) }
        )

        // realce da separação vermelho-verde (deuteranomalia)
        ReadFilter.DALTONIC -> ColorMatrixColorFilter(
            floatArrayOf(
                1.3f, -0.3f, 0f, 0f, 0f,
                -0.25f, 1.25f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** CSS equivalente para os leitores de EPUB/MOBI. */
    fun css(f: ReadFilter): String = when (f) {
        ReadFilter.NONE -> ""
        ReadFilter.PAPER ->
            "html{background:#F6EFE3 !important;filter:sepia(0.12);}"
        ReadFilter.VINTAGE ->
            "html{background:#F3EADA !important;filter:sepia(0.35) saturate(0.75) contrast(0.95);}"
        ReadFilter.SEPIA ->
            "html{background:#F3E6CE !important;filter:sepia(0.85);}"
        ReadFilter.MONO ->
            "html{filter:grayscale(1);}"
        ReadFilter.DALTONIC ->
            "html{filter:saturate(1.35) contrast(1.05);}"
    }
}
