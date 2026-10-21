package me.ash.reader.ui.page.home.flow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed

/**
 * An edition outlives the reader route. Read-status invalidations cannot remove a story from a
 * printed page or shift later page identities. An explicit refresh starts a new edition.
 */
internal class NewspaperViewportState {
    // Layout data only: one current edition/geometry, never Compose nodes or image resources.
    // Reader disposal must not discard the extra fitted lines of previously printed pages.
    val pageCache = NewspaperViewportPageCache()
    private var pager: Any? = null
    private var layoutKey: Any? = null
    private var rowColumns = 0
    private var rowItemCount = 0
    private var retainedRows: List<NewspaperRow> = emptyList()
    private var lockedPageEnds: Set<Int> = emptySet()
    private val observedRead = mutableMapOf<String, Boolean>()
    private val observedStarred = mutableMapOf<String, Boolean>()
    private val readStatus = mutableStateMapOf<String, Boolean>()
    private val starredStatus = mutableStateMapOf<String, Boolean>()
    // A logical reading position, not the first article exposed by an automatic wider reflow.
    private var logicalAnchorId: String? = null
    private var presentedPage = -1
    private var presentedIndices: List<Int> = emptyList()
    var items: List<ArticleFlowItem> by mutableStateOf(emptyList())
        private set
    var pages: List<NewspaperViewportPage> by mutableStateOf(emptyList())
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var layoutRevision by mutableIntStateOf(0)
        private set
    var revision by mutableIntStateOf(0)
        private set
    val displayedIndices: List<Int> get() = pages.getOrNull(currentPage)?.indices.orEmpty()
    val displayedArticles: List<ArticleWithFeed> get() = displayedIndices.mapNotNull {
        (items.getOrNull(it) as? ArticleFlowItem.Article)?.articleWithFeed?.let(::currentArticle)
    }

    fun rows(columns: Int): List<NewspaperRow> {
        if (rowColumns != columns || rowItemCount > items.size) {
            rowColumns = columns
            rowItemCount = 0
            retainedRows = emptyList()
        }
        if (rowItemCount < items.size) {
            retainedRows = retainedRows + newspaperRows(items.size, columns, rowItemCount) { items[it] is ArticleFlowItem.Date }
            rowItemCount = items.size
        }
        return retainedRows
    }

    fun pageBreaksFor(geometry: Any): Set<Int> = if (layoutKey == geometry) lockedPageEnds else emptySet()

    fun recordReadStatus(id: String, isUnread: Boolean) { readStatus[id] = isUnread }
    fun recordStarredStatus(id: String, isStarred: Boolean) { starredStatus[id] = isStarred }
    fun currentArticle(frozen: ArticleWithFeed): ArticleWithFeed {
        val original = frozen.article
        val article = original.copy(isUnread = readStatus[original.id] ?: original.isUnread,
            isStarred = starredStatus[original.id] ?: original.isStarred)
        article.dateString = original.dateString
        return frozen.copy(article = article)
    }

    fun ownsPager(identity: Any): Boolean = pager === identity

    fun reset() {
        pageCache.clear()
        rowItemCount = 0; rowColumns = 0; retainedRows = emptyList(); lockedPageEnds = emptySet()
        observedRead.clear(); observedStarred.clear(); readStatus.clear(); starredStatus.clear()
        items = emptyList()
        pages = emptyList()
        currentPage = 0
        layoutKey = null
        logicalAnchorId = null
        presentedPage = -1
        presentedIndices = emptyList()
        revision++
    }

    fun acceptSnapshot(pagerIdentity: Any, incoming: List<ArticleFlowItem>, ascending: Boolean = false) {
        if (pager !== pagerIdentity) {
            pager = pagerIdentity
            reset()
        }
        incoming.filterIsInstance<ArticleFlowItem.Article>().forEach { entry ->
            val article = entry.articleWithFeed.article
            if (observedRead.put(article.id, article.isUnread) != article.isUnread) readStatus[article.id] = article.isUnread
            if (observedStarred.put(article.id, article.isStarred) != article.isStarred) starredStatus[article.id] = article.isStarred
        }
        val knownIds = items.mapNotNull { (it as? ArticleFlowItem.Article)?.articleWithFeed?.article?.id }.toHashSet()
        val hasOverlap = incoming.any { (it as? ArticleFlowItem.Article)?.articleWithFeed?.article?.id in knownIds }
        val additions = mutableListOf<ArticleFlowItem>()
        var pendingDate: ArticleFlowItem.Date? = null
        var lastDate = items.filterIsInstance<ArticleFlowItem.Date>().lastOrNull()?.date
        val editions = items.filterIsInstance<ArticleFlowItem.Article>()
        val tailDate = editions.lastOrNull()?.articleWithFeed?.article?.date?.time
        val hadItems = knownIds.isNotEmpty()
        val tailId = editions.lastOrNull()?.articleWithFeed?.article?.id
        var reachedTail = !hadItems
        incoming.forEach { item ->
            when (item) {
                is ArticleFlowItem.Date -> pendingDate = item
                is ArticleFlowItem.Article -> if (item.articleWithFeed.article.id in knownIds) {
                    if (item.articleWithFeed.article.id == tailId) reachedTail = true
                } else if ((reachedTail || tailDate != null &&
                        (if (ascending) item.articleWithFeed.article.date.time > tailDate ||
                            !hasOverlap && item.articleWithFeed.article.date.time == tailDate
                         else item.articleWithFeed.article.date.time < tailDate ||
                            !hasOverlap && item.articleWithFeed.article.date.time == tailDate)) &&
                    knownIds.add(item.articleWithFeed.article.id)) {
                    pendingDate?.takeIf { it.date != lastDate }?.let { additions += it; lastDate = it.date }
                    // Freeze content and emphasis as well as identity while unread data changes.
                    val original = item.articleWithFeed
                    val article = original.article.copy().also { it.dateString = original.article.dateString }
                    additions += ArticleFlowItem.Article(original.copy(article = article, feed = original.feed.copy()))
                }
            }
        }
        if (additions.isNotEmpty()) {
            // Appending a paging batch cannot fill/reform an already printed last row/page.
            lockedPageEnds = lockedPageEnds + pages.mapNotNull { it.indices.lastOrNull() }
            items = items + additions
        }
    }

    fun isCurrentSnapshot(source: List<ArticleFlowItem>, sourceRevision: Int): Boolean =
        items === source && revision == sourceRevision

    fun updatePages(
        measured: List<NewspaperViewportPage>,
        newLayoutKey: Any,
        source: List<ArticleFlowItem> = items,
        sourceRevision: Int = revision,
    ): Boolean {
        if (!isCurrentSnapshot(source, sourceRevision)) return false
        val anchor = logicalAnchorId
        val changedLayout = layoutKey != newLayoutKey
        if (changedLayout) { layoutRevision++; suspendPresentation(); lockedPageEnds = emptySet() }
        if (pages != measured) pages = measured
        if (changedLayout && anchor != null) {
            currentPage = pages.indexOfFirst { page -> page.indices.any {
                (items.getOrNull(it) as? ArticleFlowItem.Article)?.articleWithFeed?.article?.id == anchor
            } }.coerceAtLeast(0)
        } else currentPage = currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        layoutKey = newLayoutKey
        return true
    }

    fun suspendPresentation() { presentedPage = -1; presentedIndices = emptyList() }

    fun presented(
        indices: List<Int>,
        source: List<ArticleFlowItem> = items,
        sourceRevision: Int = revision,
    ): Boolean {
        if (!isCurrentSnapshot(source, sourceRevision)) return false
        presentedPage = currentPage
        presentedIndices = indices.toList()
        if (logicalAnchorId == null && indices == displayedIndices) {
            logicalAnchorId = displayedArticles.firstOrNull()?.article?.id
        }
        return true
    }

    /** The one entry point for buttons, swipe, accessibility, and hardware keys. */
    fun turn(forward: Boolean, markReadEnabled: Boolean, onMarkRead: (List<ArticleWithFeed>) -> Unit): Boolean {
        // A second hardware event before the new frame must not mark a never-displayed page.
        if (presentedPage != currentPage || presentedIndices != displayedIndices) return false
        val target = currentPage + if (forward) 1 else -1
        if (target !in pages.indices) return false
        val outgoing = if (forward && markReadEnabled) displayedArticles.toList() else emptyList()
        currentPage = target
        logicalAnchorId = displayedArticles.firstOrNull()?.article?.id
        if (outgoing.isNotEmpty()) {
            outgoing.forEach { recordReadStatus(it.article.id, false) }
            onMarkRead(outgoing)
        }
        return true
    }
}

/** Bound to one edition and geometry; measured row/Compose caches remain composition-local. */
internal class NewspaperViewportPageCache {
    private var rows: List<NewspaperRow>? = null
    private var geometry: Any? = null
    private var breaks: Set<Int> = emptySet()
    private var pages: List<NewspaperViewportPage> = emptyList()
    val enriched = mutableMapOf<NewspaperViewportPage, NewspaperViewportPage>()

    fun partition(rows: List<NewspaperRow>, geometry: Any, breaks: Set<Int>): List<NewspaperViewportPage>? =
        if (this.rows === rows && this.geometry == geometry && this.breaks == breaks) pages else null

    fun retainPartition(rows: List<NewspaperRow>, geometry: Any, breaks: Set<Int>,
        pages: List<NewspaperViewportPage>) {
        if (this.geometry != geometry) enriched.clear()
        enriched.keys.retainAll(pages.toSet())
        this.rows = rows
        this.geometry = geometry
        this.breaks = breaks
        this.pages = pages
    }

    fun clear() {
        rows = null
        geometry = null
        breaks = emptySet()
        pages = emptyList()
        enriched.clear()
    }
}
