package me.ash.reader.ui.page.adaptive

/**
 * UI-thread latch for the content actually presented to a reader, not merely fetched by it.
 * Native callbacks pin synchronously, before recomposition can replace the document. Finishing
 * an interaction does not unpin it: only a new explicit source choice or reader identity does.
 * Network/cache work can continue independently, and same-body status updates remain visible.
 */
internal class ReaderContentPresentationGate<Identity, Content : Any>(
    private val body: (Content) -> String?,
) {
    private var identity: Identity? = null
    private var presented: Content? = null
    private var pinned = false

    fun present(identity: Identity, incoming: Content): Content {
        val previous = presented
        if (previous == null || this.identity != identity) {
            this.identity = identity
            presented = incoming
            pinned = false
            return incoming
        }
        if (!pinned || body(previous) == body(incoming)) presented = incoming
        return requireNotNull(presented)
    }

    fun onInteractionStarted() {
        if (presented?.let(body) != null) pinned = true
    }

    /** Recheck live native/capture/editor/format ownership at the choice, not just dialog open. */
    fun onExplicitSourceChoice(canReplaceDocument: () -> Boolean): Boolean {
        if (!canReplaceDocument()) return false
        pinned = false
        return true
    }
}
