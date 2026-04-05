package me.ash.reader.infrastructure.android

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VolumeKeyEventBusTest {

    @Before
    fun setUp() {
        // Clear shared state so each test starts fresh.
        // Note: activeConsumers is @Synchronized so this is safe.
        VolumeKeyEventBus.resetForTest()
    }

    @Test
    fun `emit delivers event to collector`() = runTest {
        val flow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)
        val collected = mutableListOf<VolumeKeyEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { collected.add(it) }
        }

        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        assertEquals(listOf(VolumeKeyEvent.NEXT), collected)

        job.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
    }

    @Test
    fun `multiple rapid emits are all collected in order`() = runTest {
        val flow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)
        val collected = mutableListOf<VolumeKeyEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { collected.add(it) }
        }

        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)

        assertEquals(
            listOf(
                VolumeKeyEvent.NEXT,
                VolumeKeyEvent.PREV,
                VolumeKeyEvent.NEXT,
                VolumeKeyEvent.NEXT,
            ),
            collected,
        )

        job.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
    }

    @Test
    fun `no debounce - back-to-back emits are not dropped`() = runTest {
        val flow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)
        val collected = mutableListOf<VolumeKeyEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { collected.add(it) }
        }

        // Emit the same event rapidly — none should be dropped
        repeat(5) { VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT) }

        assertEquals(5, collected.size)
        assertTrue(collected.all { it == VolumeKeyEvent.NEXT })

        job.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
    }

    @Test
    fun `HIGH priority consumer is active over LOW`() {
        VolumeKeyEventBus.register(VolumeKeyPriority.LOW)
        VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)

        assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))

        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
        VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW)
    }

    @Test
    fun `LOW becomes active after HIGH unregisters`() {
        VolumeKeyEventBus.register(VolumeKeyPriority.LOW)
        VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)

        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))

        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)

        assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))

        VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW)
    }

    @Test
    fun `isActiveConsumer returns false when no consumers registered`() {
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
    }

    @Test
    fun `buffer handles burst of events without dropping`() = runTest {
        // Collector starts first — all subsequent emits reach it via tryEmit
        // (replay=0 means pre-emitted events are NOT delivered to new collectors,
        // so we emit after the collector is active to test buffer burst handling)
        val flow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)
        val collected = mutableListOf<VolumeKeyEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { collected.add(it) }
        }

        // Emit several events in rapid succession — extraBufferCapacity=8 absorbs the burst
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)

        // All 5 should be delivered; the buffer never overflows at capacity 8
        assertEquals(5, collected.size)
        assertEquals(
            listOf(
                VolumeKeyEvent.NEXT,
                VolumeKeyEvent.PREV,
                VolumeKeyEvent.NEXT,
                VolumeKeyEvent.NEXT,
                VolumeKeyEvent.PREV,
            ),
            collected,
        )

        job.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
    }
}
