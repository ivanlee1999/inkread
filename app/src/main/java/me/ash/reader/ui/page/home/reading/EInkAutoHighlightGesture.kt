package me.ash.reader.ui.page.home.reading

/**
 * Authorizes at most one automatic snapshot per native selection session.
 * A selectionchange or ActionMode replacement alone is not a completed gesture.
 * Native handle PopupWindows do not send their motion stream through WebView;
 * subsequent handle changes deliberately cannot create more automatic records.
 */
internal class EInkAutoHighlightGesture {
    private var serial = 0L
    private var token: Long? = null
    private var pointerDown = false
    private var released = false
    private var selectionSeen = false
    private var dispatched = false
    private var sessionActive = false
    private var sessionSaved = false

    fun down(enabled: Boolean, selectionAlreadyOwned: Boolean): Long? {
        cancelGesture()
        if (!enabled || selectionAlreadyOwned || sessionActive) return null
        pointerDown = true
        token = ++serial
        return token
    }

    /** May arrive before UP, or just after UP if Chromium completes asynchronously. */
    fun selectionStarted(): Long? {
        if (!sessionActive) { sessionActive = true; sessionSaved = false }
        selectionSeen = token != null
        return dispatchIfCompleted()
    }

    fun up(): Long? {
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

    fun consume(expected: Long): Boolean {
        if (token != expected || pointerDown || !released || !selectionSeen || !dispatched || sessionSaved) return false
        sessionSaved = true
        token = null
        return true
    }

    fun cancelGesture() {
        token = null
        pointerDown = false
        released = false
        selectionSeen = false
        dispatched = false
    }

    fun finishSession() {
        cancelGesture()
        sessionActive = false
        sessionSaved = false
    }
}
