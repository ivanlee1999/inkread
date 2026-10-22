package me.ash.reader.ui.page.library

/**
 * Freeze photo presence when a story first enters a measured edition. A pending download does
 * not reserve an empty photo box, and a later thumbnail does not push already printed stories
 * onto different pages. A newly opened edition can use the latest retained thumbnail.
 */
internal class SavedLibraryImagePresence {
    private val presence = mutableMapOf<String, Boolean>()

    fun reserveImage(itemId: String, thumbnailPath: String?): Boolean =
        presence.getOrPut(itemId) { !thumbnailPath.isNullOrBlank() }
}
