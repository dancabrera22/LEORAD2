package com.just4fun2u.reader.data

import android.content.Context
import android.content.SharedPreferences

enum class LibraryView { GRID, LIST, CIRCLE }
enum class ComicMode { FLIP, SLIDE, SCROLL }
enum class ReadFilter { NONE, PAPER, VINTAGE, SEPIA, MONO, DALTONIC }

/** Preferências do usuário (modo de leitura, filtros, visualização da biblioteca). */
object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    var libraryView: LibraryView
        get() = enum(sp.getString("libraryView", null), LibraryView.GRID)
        set(v) = sp.edit().putString("libraryView", v.name).apply()

    var comicMode: ComicMode
        get() = enum(sp.getString("comicMode", null), ComicMode.FLIP)
        set(v) = sp.edit().putString("comicMode", v.name).apply()

    var comicFilter: ReadFilter
        get() = enum(sp.getString("comicFilter", null), ReadFilter.NONE)
        set(v) = sp.edit().putString("comicFilter", v.name).apply()

    var bookFilter: ReadFilter
        get() = enum(sp.getString("bookFilter", null), ReadFilter.NONE)
        set(v) = sp.edit().putString("bookFilter", v.name).apply()

    private inline fun <reified T : Enum<T>> enum(name: String?, fallback: T): T =
        try {
            if (name == null) fallback else enumValueOf(name)
        } catch (_: Exception) {
            fallback
        }
}
