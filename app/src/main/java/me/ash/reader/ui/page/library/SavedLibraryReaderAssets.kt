package me.ash.reader.ui.page.library

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.util.UUID

/** Resolve only image files referenced by this saved document, with file access still disabled. */
internal class SavedLibraryReaderAssets(html: String, private val libraryDirectory: File, itemId: String) {
    val revision: String = UUID.randomUUID().toString()
    private val slots = SavedLibraryImageSlots(html, libraryDirectory, itemId)
    val html: String = slots.html

    fun intercept(url: String): WebResourceResponse? {
        if (!slots.owns(url)) return null
        val file = slots.fileFor(url)
        val stream = runCatching { file?.takeIf { it.isFile }?.inputStream()?.buffered() }.getOrNull()
        return if (stream != null) {
            WebResourceResponse(URLConnection.guessContentTypeFromStream(stream) ?: URLConnection.guessContentTypeFromName(file!!.name) ?: "application/octet-stream", null, stream)
        } else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

}
