package me.ash.reader.infrastructure.android

import android.view.KeyEvent

/** One physical up/down press emits one page request. Key-up and repeat never emit again. */
internal fun dispatchEInkVolumeKey(
    eInkEnabled: Boolean,
    keyCode: Int,
    action: Int,
    repeatCount: Int,
): Boolean {
    if (!eInkEnabled || !VolumeKeyEventBus.hasActiveConsumer()) return false
    val direction = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKeyEvent.NEXT
        KeyEvent.KEYCODE_VOLUME_UP -> VolumeKeyEvent.PREV
        // Mute and all unrelated hardware keys keep their system behavior.
        else -> return false
    }
    if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) VolumeKeyEventBus.emit(direction)
    return true
}
