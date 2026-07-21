package com.leorad.reader.format

import android.util.Base64
import java.io.File
import java.nio.charset.Charset

/**
 * Parser de MOBI (PalmDB + PalmDOC). Suporta os arquivos MOBI não
 * criptografados com compressão PalmDOC ou sem compressão — a grande
 * maioria dos arquivos em circulação. Livros com DRM ou compressão
 * HUFF/CDIC geram erro amigável.
 */
class MobiBook(file: File) {

    val title: String
    /** HTML completo do livro, com imagens embutidas como data URIs. */
    val html: String
    /** Bytes da primeira imagem (útil como capa), se existir. */
    var firstImage: ByteArray? = null
        private set

    private val data: ByteArray = file.readBytes()

    init {
        require(data.size > 78 + 8) { "arquivo muito pequeno" }
        val typeCreator = String(data, 60, 8, Charsets.US_ASCII)
        require(typeCreator == "BOOKMOBI" || typeCreator == "TEXtREAd") {
            "não é um arquivo MOBI válido"
        }

        val numRecords = u16(76)
        val offsets = IntArray(numRecords + 1)
        for (i in 0 until numRecords) offsets[i] = u32(78 + i * 8)
        offsets[numRecords] = data.size

        fun record(i: Int): ByteArray = data.copyOfRange(offsets[i], offsets[i + 1])

        val r0 = record(0)
        val compression = u16be(r0, 0)
        val textLength = u32be(r0, 4)
        val textRecordCount = u16be(r0, 8)
        val encryption = u16be(r0, 12)

        if (encryption != 0) throw UnsupportedFormatException("Este MOBI tem DRM e não pode ser aberto.")
        if (compression == 17480) throw UnsupportedFormatException("Compressão HUFF/CDIC (AZW) não é suportada.")
        if (compression != 1 && compression != 2) throw UnsupportedFormatException("Compressão MOBI desconhecida: $compression")

        var encoding = Charsets.ISO_8859_1 as Charset
        var extraFlags = 0
        var firstImageIndex = -1
        var fullName: String? = null

        if (r0.size >= 24 && String(r0, 16, 4, Charsets.US_ASCII) == "MOBI") {
            val mobiHeaderLength = u32be(r0, 20)
            val textEncoding = u32be(r0, 28)
            encoding = when (textEncoding) {
                65001 -> Charsets.UTF_8
                1252 -> try { Charset.forName("windows-1252") } catch (_: Exception) { Charsets.ISO_8859_1 }
                else -> Charsets.UTF_8
            }
            if (r0.size >= 128) {
                firstImageIndex = u32be(r0, 124)
            }
            if (r0.size >= 108) {
                val nameOff = u32be(r0, 100)
                val nameLen = u32be(r0, 104)
                if (nameOff in 0 until r0.size && nameLen > 0 && nameOff + nameLen <= r0.size) {
                    fullName = String(r0, nameOff, nameLen, encoding).trim { it <= ' ' }
                }
            }
            if (mobiHeaderLength >= 0xE4 && r0.size >= 0xF4) {
                extraFlags = u16be(r0, 0xF2)
            }
        }

        title = fullName?.takeIf { it.isNotEmpty() }
            ?: String(data, 0, 32, Charsets.US_ASCII).takeWhile { it != '\u0000' }.trim()
                .ifEmpty { file.nameWithoutExtension }

        // decodifica os registros de texto
        val buf = GrowBuf(textLength.coerceAtLeast(65536))
        for (i in 1..textRecordCount) {
            if (i >= numRecords) break
            var rec = record(i)
            val trailing = trailingSize(rec, extraFlags)
            if (trailing in 1 until rec.size) rec = rec.copyOfRange(0, rec.size - trailing)
            when (compression) {
                1 -> buf.append(rec)
                2 -> palmdocDecompress(rec, buf)
            }
        }
        var text = String(buf.array(), 0, minOf(buf.length(), if (textLength > 0) textLength else buf.length()), encoding)

        // imagens: recindex é 1-based a partir de firstImageIndex
        if (firstImageIndex in 0 until numRecords) {
            for (i in firstImageIndex until numRecords) {
                val rec = record(i)
                if (imageMime(rec) != null) { firstImage = rec; break }
            }
            text = Regex("<img([^>]*?)recindex\\s*=\\s*[\"']?(\\d+)[\"']?([^>]*)>", RegexOption.IGNORE_CASE)
                .replace(text) { m ->
                    val recIndex = m.groupValues[2].toIntOrNull()
                    val recNum = if (recIndex != null) firstImageIndex + recIndex - 1 else -1
                    if (recNum in 0 until numRecords) {
                        val rec = record(recNum)
                        val mime = imageMime(rec)
                        if (mime != null && rec.size <= 4 * 1024 * 1024) {
                            val b64 = Base64.encodeToString(rec, Base64.NO_WRAP)
                            "<img${m.groupValues[1]} src=\"data:$mime;base64,$b64\" ${m.groupValues[3]}>"
                        } else "<!-- imagem -->"
                    } else "<!-- imagem -->"
                }
        }
        html = text
    }

    private fun u16(off: Int) = ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
    private fun u32(off: Int) =
        ((data[off].toInt() and 0xff) shl 24) or ((data[off + 1].toInt() and 0xff) shl 16) or
            ((data[off + 2].toInt() and 0xff) shl 8) or (data[off + 3].toInt() and 0xff)

    companion object {
        fun u16be(b: ByteArray, off: Int) =
            ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

        fun u32be(b: ByteArray, off: Int) =
            ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
                ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

        fun imageMime(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
            b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
            b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
            else -> null
        }

        /** Tamanho dos dados extras no fim de cada registro de texto (extra data flags). */
        fun trailingSize(rec: ByteArray, extraFlags: Int): Int {
            var num = 0
            var flags = extraFlags shr 1
            while (flags != 0) {
                if (flags and 1 != 0) num += backwardVarint(rec, rec.size - num)
                flags = flags shr 1
            }
            if (extraFlags and 1 != 0) {
                if (rec.size - num - 1 >= 0) {
                    num += (rec[rec.size - num - 1].toInt() and 0x3) + 1
                }
            }
            return num
        }

        private fun backwardVarint(rec: ByteArray, endExclusive: Int): Int {
            var size = endExclusive
            var bitpos = 0
            var result = 0
            if (size <= 0) return 0
            while (true) {
                val v = rec[size - 1].toInt() and 0xff
                result = result or ((v and 0x7f) shl bitpos)
                bitpos += 7
                size -= 1
                if ((v and 0x80) != 0 || bitpos >= 28 || size == 0) return result
            }
        }

        /** Descompressão LZ77 do PalmDOC. */
        fun palmdocDecompress(src: ByteArray, out: GrowBuf) {
            var i = 0
            while (i < src.size) {
                val c = src[i++].toInt() and 0xff
                when {
                    c == 0 -> out.add(0)
                    c <= 8 -> {
                        var n = c
                        while (n-- > 0 && i < src.size) out.add(src[i++].toInt())
                    }
                    c <= 0x7f -> out.add(c)
                    c >= 0xc0 -> {
                        out.add(' '.code)
                        out.add(c xor 0x80)
                    }
                    else -> {
                        if (i >= src.size) break
                        val cc = (c shl 8) or (src[i++].toInt() and 0xff)
                        val dist = (cc ushr 3) and 0x7ff
                        val len = (cc and 7) + 3
                        out.copyBack(dist, len)
                    }
                }
            }
        }
    }

    class GrowBuf(initial: Int) {
        private var buf = ByteArray(initial.coerceAtLeast(16))
        private var len = 0

        fun length() = len
        fun array() = buf

        private fun ensure(extra: Int) {
            if (len + extra > buf.size) {
                var n = buf.size * 2
                while (n < len + extra) n *= 2
                buf = buf.copyOf(n)
            }
        }

        fun add(b: Int) {
            ensure(1)
            buf[len++] = b.toByte()
        }

        fun append(bytes: ByteArray) {
            ensure(bytes.size)
            System.arraycopy(bytes, 0, buf, len, bytes.size)
            len += bytes.size
        }

        fun copyBack(dist: Int, count: Int) {
            if (dist <= 0) return
            var pos = len - dist
            repeat(count) {
                val v = if (pos >= 0 && pos < len) buf[pos].toInt() else 0
                add(v)
                pos++
            }
        }
    }
}

class UnsupportedFormatException(message: String) : Exception(message)
