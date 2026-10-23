package me.ash.reader.ui.page.home.reading

/** Owner-thread-only JS cleanup, fenced against a newer gesture or document. */
internal class EInkAnnotationScriptDispatch(
    private val isOwnerThread: () -> Boolean,
    private val postToOwner: (() -> Unit) -> Unit,
    private val currentDocument: () -> Any,
    private val currentRevision: () -> Long,
    private val currentSessionRevision: () -> Long,
    private val isAttached: () -> Boolean,
) {
    fun dispatch(document: Any, revision: Long, sessionReset: Boolean = false, command: () -> Unit) {
        val guarded = {
            // A cancellation must not erase a pending session-end reset: JS
            // still needs to clear its consumed latch. A newer DOWN does revoke
            // that reset, so it cannot clear the newer live selection session.
            val current = if (sessionReset) currentSessionRevision() else currentRevision()
            if (document == currentDocument() && revision == current && isAttached()) command()
        }
        if (isOwnerThread()) guarded() else postToOwner(guarded)
    }
}
