package me.ash.reader.ui.page.home.reading

import java.io.File

/** Only the explicitly selected font can be served; no URL-to-filesystem translation. */
internal class EInkFontResource(val file: File) {
    val url = "https://inkread.local/fonts/${file.name}?v=${file.lastModified()}"
    val mimeType = if (file.extension.equals("otf", ignoreCase = true)) "font/otf" else "font/ttf"

    fun open(requestUrl: String) =
        if (requestUrl == url && file.isFile) file.inputStream() else null
}
