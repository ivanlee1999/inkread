package me.ash.reader.infrastructure.android

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class EInkHardwareKeyPolicyTest {
    private fun action(
        key: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0,
        enabled: Boolean = true, reader: Boolean = true, list: Boolean = false, editing: Boolean = false,
    ) = EInkHardwareKeyPolicy.action(key, action, repeat, enabled, reader, list, editing)

    @Test fun `reader volume and page keys use the same directions`() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)) {
            assertEquals(EInkKeyAction.NEXT, action(key))
        }
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP)) {
            assertEquals(EInkKeyAction.PREV, action(key))
        }
    }

    @Test fun `held keys and releases do not generate extra page turns`() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_UP)) {
            assertEquals(EInkKeyAction.CONSUME, action(key, repeat = 1))
            assertEquals(EInkKeyAction.CONSUME, action(key, repeat = 20))
            assertEquals(EInkKeyAction.CONSUME, action(key, action = KeyEvent.ACTION_UP))
            assertEquals(EInkKeyAction.NEXT, action(KeyEvent.KEYCODE_VOLUME_DOWN))
        }
    }

    @Test fun `text input and non eink mode retain native key behavior`() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            assertEquals(EInkKeyAction.PASS_THROUGH, action(key, editing = true))
            assertEquals(EInkKeyAction.PASS_THROUGH, action(key, enabled = false))
        }
    }

    @Test fun `unowned screens retain volume and page key behavior`() {
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            assertEquals(EInkKeyAction.PASS_THROUGH, action(key, reader = false))
        }
    }

    @Test fun `library retains volume paging but page keys are reader only`() {
        assertEquals(EInkKeyAction.NEXT, action(KeyEvent.KEYCODE_VOLUME_DOWN, reader = false, list = true))
        assertEquals(EInkKeyAction.PREV, action(KeyEvent.KEYCODE_VOLUME_UP, reader = false, list = true))
        assertEquals(EInkKeyAction.PASS_THROUGH, action(KeyEvent.KEYCODE_PAGE_DOWN, reader = false, list = true))
    }

    @Test fun `unrelated shortcuts always pass through`() {
        assertEquals(EInkKeyAction.PASS_THROUGH, action(KeyEvent.KEYCODE_A))
        assertEquals(EInkKeyAction.PASS_THROUGH, action(KeyEvent.KEYCODE_BACK))
    }
}
