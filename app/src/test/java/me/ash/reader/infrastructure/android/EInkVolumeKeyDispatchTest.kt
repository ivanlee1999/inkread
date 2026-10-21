package me.ash.reader.infrastructure.android

import android.view.KeyEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EInkVolumeKeyDispatchTest {
    @Before fun resetBefore() = VolumeKeyEventBus.resetForTest()
    @After fun resetAfter() = VolumeKeyEventBus.resetForTest()

    @Test fun onePhysicalPressEmitsOnceWhileRepeatAndReleaseAreConsumed() = runTest {
        val owner = Any()
        val events = VolumeKeyEventBus.register(VolumeKeyPriority.LOW, owner)
        val received = mutableListOf<VolumeKeyEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { events.collect { received += it } }
        try {
            for ((key, direction) in listOf(KeyEvent.KEYCODE_VOLUME_DOWN to VolumeKeyEvent.NEXT,
                KeyEvent.KEYCODE_VOLUME_UP to VolumeKeyEvent.PREV)) {
                assertTrue(dispatchEInkVolumeKey(true, key, KeyEvent.ACTION_DOWN, 0))
                assertEquals(direction, received.last())
                val count = received.size
                for (repeat in 1..4) assertTrue(dispatchEInkVolumeKey(true, key, KeyEvent.ACTION_DOWN, repeat))
                assertTrue(dispatchEInkVolumeKey(true, key, KeyEvent.ACTION_UP, 0))
                assertEquals("Only the initial down event turns a page", count, received.size)
            }
            assertEquals(listOf(VolumeKeyEvent.NEXT, VolumeKeyEvent.PREV), received)
        } finally {
            job.cancel()
            VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW, owner)
        }
    }

    @Test fun noActivePagerEInkOffAndUnrelatedKeysKeepSystemBehavior() = runTest {
        assertFalse(dispatchEInkVolumeKey(true, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
        assertFalse(dispatchEInkVolumeKey(true, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0))
        val owner = Any()
        val events = VolumeKeyEventBus.register(VolumeKeyPriority.LOW, owner)
        val received = mutableListOf<VolumeKeyEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { events.collect { received += it } }
        try {
            assertFalse(dispatchEInkVolumeKey(false, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
            for (key in listOf(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_A)) {
                assertFalse(dispatchEInkVolumeKey(true, key, KeyEvent.ACTION_DOWN, 0))
            }
            assertTrue(received.isEmpty())
        } finally {
            job.cancel()
            VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW, owner)
        }
        assertFalse(dispatchEInkVolumeKey(true, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
    }
}
