package me.ash.reader.ui.page.home.reading

/**
 * Chromium replaces floating ActionMode after range/classification changes.
 * Destroying its old menu is not necessarily the end of its text selection.
 */
internal class EInkSelectionActionModeLifecycle<Mode : Any> {
    var activeMode: Mode? = null
        private set
    var ownsSelection: Boolean = false
        private set
    private var revision = 0L

    fun start(mode: Mode) {
        revision++
        activeMode = mode
        ownsSelection = true
    }

    /** Retain input ownership while the provider may replace its floating menu. */
    fun destroyed(mode: Mode): Long? {
        if (activeMode !== mode) return null
        activeMode = null
        return ++revision
    }

    /** Called on the next UI queue turn, after a synchronous replacement can start. */
    fun completeDismissal(token: Long, sameReader: Boolean, attached: Boolean): Boolean {
        if (!sameReader || !attached || token != revision || activeMode != null || !ownsSelection) return false
        finish()
        return true
    }

    /** Explicit dismissal or document replacement invalidates queued completions. */
    fun finish() {
        revision++
        activeMode = null
        ownsSelection = false
    }
}
