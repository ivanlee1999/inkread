package me.ash.reader.ui.page.library

import java.io.File
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup

/** Stable slots include unavailable images from the very first rendered document. */
internal class SavedLibraryImageSlots(html: String, libraryDirectory: File, itemId: String) {
    private val prefix = "https://inkread.local/saved-library-assets/${URLEncoder.encode(itemId, "UTF-8")}/"
    private val images = mutableMapOf<String, File>()
    val html: String = Jsoup.parseBodyFragment(html).body().also { body ->
        val root = libraryDirectory.canonicalFile
        // Storage deliberately removes src for unavailable images. Filtering by
        // [src] would omit their slots and shift all retained images after them.
        body.select("img").forEachIndexed { index, image ->
            val source = image.attr("src")
            val file = runCatching {
                if (!source.startsWith("file:")) null else File(URI(source)).canonicalFile
            }.getOrNull()
            if (file != null && file.toPath().startsWith(root.toPath())) images["$prefix$index"] = file
            image.attr("src", "$prefix$index")
        }
    }.html()

    fun owns(url: String): Boolean = url.startsWith(prefix)
    fun fileFor(url: String): File? = images[url.substringBefore("?")]
}
