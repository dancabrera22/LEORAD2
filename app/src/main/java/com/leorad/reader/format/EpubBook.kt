package com.leorad.reader.format

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB = ZIP com XHTML. Lê container.xml -> OPF -> spine, e serve os
 * recursos direto do ZIP para o WebView.
 */
class EpubBook(file: File) : Closeable {

    private val zip = ZipFile(file)
    private val opfDir: String

    var title: String = file.nameWithoutExtension
        private set
    /** Caminhos (dentro do zip) dos capítulos, na ordem de leitura. */
    val spine = mutableListOf<String>()
    /** Caminho da imagem de capa dentro do zip, se houver. */
    var coverPath: String? = null
        private set

    init {
        val containerXml = entryStream("META-INF/container.xml")
            ?: throw IllegalArgumentException("EPUB inválido: sem container.xml")
        val opfPath = parseContainer(containerXml)
            ?: throw IllegalArgumentException("EPUB inválido: sem rootfile")
        opfDir = opfPath.substringBeforeLast('/', "")
        val opfStream = entryStream(opfPath)
            ?: throw IllegalArgumentException("EPUB inválido: OPF não encontrado")
        parseOpf(opfStream)
    }

    private fun findEntry(path: String): ZipEntry? {
        val decoded = try {
            URLDecoder.decode(path, "UTF-8")
        } catch (_: Exception) {
            path
        }
        return zip.getEntry(path) ?: zip.getEntry(decoded)
    }

    fun entryStream(path: String): InputStream? =
        findEntry(path)?.let { zip.getInputStream(it) }

    fun entryBytes(path: String): ByteArray? =
        entryStream(path)?.use { it.readBytes() }

    fun resolve(relative: String): String {
        val clean = relative.substringBefore('#')
        if (clean.startsWith("/")) return clean.removePrefix("/")
        val base = if (opfDir.isEmpty()) "" else "$opfDir/"
        val parts = ("$base$clean").split('/').toMutableList()
        val out = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                ".", "" -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        return parser
    }

    private fun parseContainer(input: InputStream): String? {
        input.use {
            val parser = newParser(it)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return parser.getAttributeValue(null, "full-path")
                }
                event = parser.next()
            }
        }
        return null
    }

    private fun parseOpf(input: InputStream) {
        val idToHref = mutableMapOf<String, String>()
        val idToMedia = mutableMapOf<String, String>()
        val spineIds = mutableListOf<String>()
        var coverId: String? = null
        var coverImageProperty: String? = null

        input.use {
            val parser = newParser(it)
            var event = parser.eventType
            var inMetadata = false
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "metadata" -> inMetadata = true
                        "title" -> if (inMetadata) {
                            val t = parser.nextText().trim()
                            if (t.isNotEmpty()) title = t
                        }
                        "meta" -> {
                            if (parser.getAttributeValue(null, "name") == "cover") {
                                coverId = parser.getAttributeValue(null, "content")
                            }
                        }
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val media = parser.getAttributeValue(null, "media-type") ?: ""
                            val props = parser.getAttributeValue(null, "properties") ?: ""
                            if (id != null && href != null) {
                                idToHref[id] = href
                                idToMedia[id] = media
                                if (props.contains("cover-image")) coverImageProperty = href
                            }
                        }
                        "itemref" -> {
                            parser.getAttributeValue(null, "idref")?.let { id -> spineIds.add(id) }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "metadata") {
                    inMetadata = false
                }
                event = parser.next()
            }
        }

        for (id in spineIds) {
            idToHref[id]?.let { spine.add(resolve(it)) }
        }
        coverPath = coverImageProperty?.let { resolve(it) }
            ?: coverId?.let { idToHref[it] }?.let { resolve(it) }
            ?: idToHref.entries.firstOrNull { (_, href) ->
                val n = href.lowercase()
                n.contains("cover") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))
            }?.value?.let { resolve(it) }
    }

    override fun close() = zip.close()

    companion object {
        fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "xhtml" -> "application/xhtml+xml"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
