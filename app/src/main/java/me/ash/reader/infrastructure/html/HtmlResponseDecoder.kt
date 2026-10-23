package me.ash.reader.infrastructure.html

import java.io.IOException
import java.nio.charset.Charset
import okhttp3.ResponseBody
import okio.Buffer
import org.jsoup.Jsoup

private const val DEFAULT_HTML_MAX_BYTES = 2L * 1024L * 1024L
private const val CHARSET_SNIFF_BYTES = 64 * 1024
private const val READ_BUFFER_BYTES = 8L * 1024L
private val contentCharset = Regex("(?:^|;)\\s*charset\\s*=\\s*[\"']?([^\\s;\"']+)", RegexOption.IGNORE_CASE)

/**
 * Decode bounded article HTML consistently for both RSS and Saved Library downloads.
 * A Unicode BOM wins over HTTP metadata, then a supported HTTP charset wins over HTML meta.
 * Invalid charset labels are ignored instead of dropping an otherwise readable article.
 */
internal fun ResponseBody.readHtmlLimited(maxBytes: Long = DEFAULT_HTML_MAX_BYTES): String {
    require(maxBytes in 0..Int.MAX_VALUE.toLong()) { "Invalid HTML byte limit" }
    return source().use { source ->
        if (contentLength() > maxBytes) throw IOException("Article HTML exceeds byte limit")
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, minOf(READ_BUFFER_BYTES, maxBytes + 1L - buffer.size))
            if (read == -1L) break
            if (buffer.size > maxBytes) throw IOException("Article HTML exceeds byte limit")
        }
        val bytes = buffer.readByteArray()
        val bom = detectBom(bytes)
        val charset = bom?.first
            ?: contentType()?.charset()
            ?: charsetFromMeta(bytes)
            ?: Charsets.UTF_8
        val offset = bom?.second ?: 0
        String(bytes, offset, bytes.size - offset, charset)
    }
}

private fun detectBom(bytes: ByteArray): Pair<Charset, Int>? {
    fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size &&
        prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
    // Test four-byte markers before UTF-16LE, whose marker prefixes UTF-32LE.
    return when {
        startsWith(0x00, 0x00, 0xfe, 0xff) -> Charset.forName("UTF-32BE") to 4
        startsWith(0xff, 0xfe, 0x00, 0x00) -> Charset.forName("UTF-32LE") to 4
        startsWith(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
        startsWith(0xfe, 0xff) -> Charsets.UTF_16BE to 2
        startsWith(0xff, 0xfe) -> Charsets.UTF_16LE to 2
        else -> null
    }
}

private fun charsetFromMeta(bytes: ByteArray): Charset? {
    // HTML's charset labels are ASCII. Latin-1 preserves them without first assuming UTF-8.
    val prefix = String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), Charsets.ISO_8859_1)
    val document = Jsoup.parse(prefix)
    for (meta in document.select("meta[charset], meta[http-equiv]")) {
        val label = when {
            meta.hasAttr("charset") -> meta.attr("charset")
            meta.attr("http-equiv").equals("content-type", ignoreCase = true) ->
                contentCharset.find(meta.attr("content"))?.groupValues?.get(1)
            else -> null
        }
        if (!label.isNullOrBlank()) {
            runCatching { Charset.forName(label.trim()) }.getOrNull()?.let { return it }
        }
    }
    return null
}
