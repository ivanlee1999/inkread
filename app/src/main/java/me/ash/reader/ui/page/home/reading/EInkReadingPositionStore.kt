package me.ash.reader.ui.page.home.reading

/**
 * Remembers how far into each article the reader got, so returning to an
 * article — or repaginating it after a font size change, rotation or
 * full-content re-parse — resumes where reading left off instead of jumping
 * back to page 1.
 *
 * Positions are stored as a fraction of the article rather than a page index
 * so they stay meaningful when the page count changes.
 *
 * In-memory only: positions live for the lifetime of the process. That covers
 * article switching and restyling, which is where losing your place is most
 * jarring; it does not survive an app restart.
 */
object EInkReadingPositionStore {

    /** Bounded so a long browsing session can't grow this without limit. */
    internal const val MAX_ENTRIES = 200

    private val positions = object : LinkedHashMap<String, Float>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun save(articleId: String, fraction: Float) {
        if (fraction <= 0f) {
            positions.remove(articleId)
        } else {
            positions[articleId] = fraction.coerceIn(0f, 1f)
        }
    }

    @Synchronized
    fun get(articleId: String): Float? = positions[articleId]

    @Synchronized
    fun clear() = positions.clear()
}
