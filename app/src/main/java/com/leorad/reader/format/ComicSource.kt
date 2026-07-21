package com.leorad.reader.format

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.leorad.reader.data.BookType
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Fonte de páginas de um quadrinho (CBZ ou CBR), extraídas sob demanda. */
interface ComicSource : Closeable {
    val pageCount: Int
    fun pageBytes(index: Int): ByteArray

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

        fun isImage(name: String): Boolean {
            val n = name.substringAfterLast('.', "").lowercase()
            return IMAGE_EXT.contains(n) && !name.substringAfterLast('/').startsWith(".")
        }

        /** Ordenação natural: "page2" antes de "page10". */
        val naturalOrder = Comparator<String> { a, b ->
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var x = 0L
                    var y = 0L
                    while (i < a.length && a[i].isDigit()) { x = x * 10 + (a[i] - '0'); i++ }
                    while (j < b.length && b[j].isDigit()) { y = y * 10 + (b[j] - '0'); j++ }
                    if (x != y) return@Comparator if (x < y) -1 else 1
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return@Comparator c
                    i++
                    j++
                }
            }
            (a.length - i) - (b.length - j)
        }

        fun open(file: File, type: BookType): ComicSource = when (type) {
            BookType.CBZ -> ZipComicSource(file)
            BookType.CBR -> RarComicSource(file)
            else -> throw IllegalArgumentException("não é quadrinho: $type")
        }
    }
}

class ZipComicSource(file: File) : ComicSource {
    private val zip = ZipFile(file)
    private val entries = zip.entries().asSequence()
        .filter { !it.isDirectory && ComicSource.isImage(it.name) }
        .sortedWith(compareBy(ComicSource.naturalOrder) { it.name })
        .toList()

    override val pageCount get() = entries.size

    override fun pageBytes(index: Int): ByteArray =
        zip.getInputStream(entries[index]).use { it.readBytes() }

    override fun close() = zip.close()
}

class RarComicSource(file: File) : ComicSource {
    private val archive = Archive(file)
    private val headers: List<FileHeader> = archive.fileHeaders
        .filter { !it.isDirectory && ComicSource.isImage(it.fileName.replace('\\', '/')) }
        .sortedWith(compareBy(ComicSource.naturalOrder) { it.fileName.replace('\\', '/') })

    override val pageCount get() = headers.size

    @Synchronized
    override fun pageBytes(index: Int): ByteArray {
        val out = ByteArrayOutputStream()
        archive.extractFile(headers[index], out)
        return out.toByteArray()
    }

    override fun close() = archive.close()
}
