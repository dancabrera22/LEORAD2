package com.leorad.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.leorad.reader.format.ComicSource
import com.leorad.reader.format.EpubBook
import com.leorad.reader.format.MobiBook
import java.io.File
import java.io.FileOutputStream

/** Copia o arquivo escolhido para o armazenamento do app e gera a capa. */
object BookImporter {

    class ImportException(message: String) : Exception(message)

    fun import(context: Context, uri: Uri): Book {
        val displayName = queryName(context, uri) ?: "arquivo"
        val ext = displayName.substringAfterLast('.', "").lowercase()

        val id = LibraryStore.newId()
        val tmp = File(LibraryStore.booksDir(), "$id.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw ImportException("Não foi possível ler o arquivo")

        val type = detectType(tmp, ext) ?: run {
            tmp.delete()
            throw ImportException("Formato não suportado: $displayName")
        }

        val fileName = "$id.${type.name.lowercase()}"
        val dest = File(LibraryStore.booksDir(), fileName)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw ImportException("Falha ao salvar o arquivo")
        }

        var title = displayName.substringBeforeLast('.')
        var coverName: String? = null
        try {
            val coverBytes: ByteArray? = when (type) {
                BookType.CBZ, BookType.CBR ->
                    ComicSource.open(dest, type).use { src ->
                        if (src.pageCount > 0) src.pageBytes(0) else null
                    }
                BookType.EPUB -> EpubBook(dest).use { epub ->
                    if (epub.title.isNotBlank()) title = epub.title
                    epub.coverPath?.let { epub.entryBytes(it) }
                }
                BookType.MOBI -> {
                    val mobi = MobiBook(dest)
                    if (mobi.title.isNotBlank()) title = mobi.title
                    mobi.firstImage
                }
            }
            if (coverBytes != null) {
                coverName = saveCover(id, coverBytes)
            }
        } catch (_: Exception) {
            // capa é opcional; erros reais aparecem ao abrir o livro
        }

        val book = Book(
            id = id,
            title = title,
            fileName = fileName,
            type = type,
            coverName = coverName,
            addedAt = System.currentTimeMillis()
        )
        LibraryStore.addBook(book)
        return book
    }

    private fun detectType(file: File, ext: String): BookType? {
        val head = ByteArray(68)
        val read = file.inputStream().use { it.read(head) }
        if (read < 8) return null

        val isRar = head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
            head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte()
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val isPdb = read >= 68 && String(head, 60, 8, Charsets.US_ASCII).let {
            it == "BOOKMOBI" || it == "TEXtREAd"
        }

        return when {
            isRar -> BookType.CBR
            isPdb -> BookType.MOBI
            isZip -> when (ext) {
                "epub" -> BookType.EPUB
                "cbz" -> BookType.CBZ
                else -> if (zipHasContainer(file)) BookType.EPUB else BookType.CBZ
            }
            else -> when (ext) {
                "cbr" -> BookType.CBR
                "cbz" -> BookType.CBZ
                "epub" -> BookType.EPUB
                "mobi", "azw", "prc" -> BookType.MOBI
                else -> null
            }
        }
    }

    private fun zipHasContainer(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
    } catch (_: Exception) {
        false
    }

    private fun saveCover(id: String, bytes: ByteArray): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 400) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        val name = "$id.jpg"
        FileOutputStream(File(LibraryStore.coversDir(), name)).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        bmp.recycle()
        return name
    }

    private fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }
}
