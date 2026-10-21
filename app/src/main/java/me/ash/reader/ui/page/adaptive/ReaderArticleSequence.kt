package me.ash.reader.ui.page.adaptive

/** A reader session owns identities, not positions in a changing unread query. */
internal class ReaderArticleSequence<T>(private val identity: (T) -> String) {
    private var pager: Any? = null
    private val articles = linkedMapOf<String, T>()

    fun update(pagerIdentity: Any, snapshot: List<T>): List<T> {
        if (pager !== pagerIdentity) {
            clear()
            pager = pagerIdentity
        }
        // Preserve order and ignore newly synced stories before the known session.
        // A disjoint unread suffix is still a continuation after every old item became read.
        val known = articles.keys.toSet()
        var reachedKnown = snapshot.none { identity(it) in known }
        snapshot.forEach {
            val id = identity(it)
            if (id in known) reachedKnown = true
            if (id in known || reachedKnown) articles[id] = it
        }
        return articles.values.toList()
    }

    fun find(pagerIdentity: Any, articleId: String): T? =
        if (pager === pagerIdentity) articles[articleId] else null

    fun clear() {
        pager = null
        articles.clear()
    }
}
