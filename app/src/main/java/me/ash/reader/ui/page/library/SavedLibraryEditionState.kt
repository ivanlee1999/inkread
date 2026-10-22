package me.ash.reader.ui.page.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.ash.reader.domain.library.SavedItem
import me.ash.reader.ui.page.home.flow.NewspaperViewportPage

/** The route owns an edition, so reader return does not reset its filter, page or identity. */
internal class SavedLibraryEditionState(
    private var anchorId: String? = null,
    initialPage: Int = 0,
    private val onPosition: (String?, Int) -> Unit = { _, _ -> },
) {
    var currentPage by mutableIntStateOf(initialPage)
        private set
    var pages: List<NewspaperViewportPage> by mutableStateOf(emptyList())
        private set
    var items: List<SavedItem> = emptyList()
        private set
    private val unreadEmphasis = mutableMapOf<String, Boolean>()
    private val imagePresence = SavedLibraryImagePresence()
    private var presentationReady = false
    private var layoutKey: Any? = null
    private var plannedItems: List<SavedItem> = emptyList()

    fun adopt(incoming: List<SavedItem>, nextPages: List<NewspaperViewportPage>, geometry: Any) {
        presentationReady = nextPages.isNotEmpty()
        if (plannedItems == incoming && layoutKey == geometry) return
        val oldIndex = items.indexOfFirst { it.id == anchorId }.coerceAtLeast(0)
        val nextAnchor = anchorId?.takeIf { id -> incoming.any { it.id == id } }
            ?: incoming.getOrNull(oldIndex.coerceAtMost((incoming.size - 1).coerceAtLeast(0)))?.id
        currentPage = savedPageForAnchor(nextPages.map { page -> page.indices.map { incoming[it].id } }, nextAnchor, currentPage)
        pages = nextPages
        items = incoming
        plannedItems = incoming
        layoutKey = geometry
        anchorId = nextAnchor ?: anchorId
        onPosition(anchorId, currentPage)
    }

    fun emphasizeUnread(item: SavedItem): Boolean = unreadEmphasis.getOrPut(item.id) { !item.isRead }
    fun reserveImage(item: SavedItem): Boolean = imagePresence.reserveImage(item.id, item.thumbnailPath)

    fun suspendPresentation() { presentationReady = false }

    fun turn(forward: Boolean): Boolean {
        if (!presentationReady) return false
        val next = currentPage + if (forward) 1 else -1
        if (next !in pages.indices) return false
        currentPage = next
        anchorId = pages[next].indices.firstOrNull()?.let { items.getOrNull(it)?.id }
        onPosition(anchorId, currentPage)
        return true
    }
}
