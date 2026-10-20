package me.ash.reader.infrastructure.android

import android.view.KeyEvent

/** A physical press turns one page; held-key repeats never accumulate navigation. */
internal enum class EInkKeyAction { PASS_THROUGH, CONSUME, NEXT, PREV }

internal object EInkHardwareKeyPolicy {
    fun isNavigationKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP -> true
        else -> false
    }

    fun action(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        einkEnabled: Boolean,
        readerActive: Boolean,
        listActive: Boolean,
        textInputActive: Boolean,
    ): EInkKeyAction {
        if (!einkEnabled || textInputActive) return EInkKeyAction.PASS_THROUGH
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> if (readerActive) EInkKeyAction.NEXT else EInkKeyAction.PASS_THROUGH
            KeyEvent.KEYCODE_PAGE_UP -> if (readerActive) EInkKeyAction.PREV else EInkKeyAction.PASS_THROUGH
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (readerActive || listActive) EInkKeyAction.NEXT else EInkKeyAction.PASS_THROUGH
            KeyEvent.KEYCODE_VOLUME_UP -> if (readerActive || listActive) EInkKeyAction.PREV else EInkKeyAction.PASS_THROUGH
            KeyEvent.KEYCODE_VOLUME_MUTE -> if (readerActive || listActive) EInkKeyAction.CONSUME else EInkKeyAction.PASS_THROUGH
            else -> EInkKeyAction.PASS_THROUGH
        }
        if (direction == EInkKeyAction.PASS_THROUGH) return direction
        return if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) direction else EInkKeyAction.CONSUME
    }
}
