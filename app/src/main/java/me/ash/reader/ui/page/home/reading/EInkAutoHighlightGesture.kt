package me.ash.reader.ui.page.home.reading

/**
 * Authorizes at most one automatic snapshot per native selection session.
 * A selectionchange or ActionMode replacement alone is not a completed gesture.
 * Native handle PopupWindows do not send their motion stream through WebView;
 * subsequent handle changes deliberately cannot create more automatic records.
 * Layout invalidation can arrive off the owner thread. Synchronized revocation
 * rejects a queued bridge callback immediately, before JS cleanup can run.
 */
internal class EInkAutoHighlightGesture {
    private var serial = 0L
    private var revision = 0L
    private var sessionRevision = 0L
    val currentSessionRevision: Long @Synchronized get() = sessionRevision
    val currentRevision: Long @Synchronized get() = revision
    private var token: Long? = null
    private var pointerDown = false
    private var released = false
    private var selectionSeen = false
    private var dispatched = false
    private var sessionActive = false
    private var sessionSaved = false

    @Synchronized fun down(enabled: Boolean, selectionAlreadyOwned: Boolean): Long? {
        sessionRevision++
        cancelGesture()
        if (!enabled || selectionAlreadyOwned || sessionActive) return null
        pointerDown = true
        token = ++serial
        return token
    }

    /** May arrive before UP, or just after UP if Chromium completes asynchronously. */
    @Synchronized fun selectionStarted(): Long? {
        if (!sessionActive) { sessionActive = true; sessionSaved = false }
        selectionSeen = token != null
        return dispatchIfCompleted()
    }

    @Synchronized fun up(): Long? {
        if (token == null) return null
        pointerDown = false
        released = true
        return dispatchIfCompleted()
    }

    private fun dispatchIfCompleted(): Long? {
        if (pointerDown || !released || !selectionSeen || dispatched || sessionSaved) return null
        dispatched = true
        return token
    }

    @Synchronized fun consume(expected: Long): Boolean {
        if (token != expected || pointerDown || !released || !selectionSeen || !dispatched || sessionSaved) return false
        sessionSaved = true
        token = null
        return true
    }

    @Synchronized fun cancelGesture(): Long {
        revision++
        token = null
        pointerDown = false
        released = false
        selectionSeen = false
        dispatched = false
        return revision
    }

    @Synchronized fun finishSession(): Long {
        sessionRevision++
        cancelGesture()
        sessionActive = false
        sessionSaved = false
        return sessionRevision
    }
}
