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
        assertFalse(VolumeKeyEventBus.hasActiveConsumer())
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
    }

    @Test fun `loading reader reservation survives ready content disposal`() {
        VolumeKeyEventBus.register(VolumeKeyPriority.LOW)
        VolumeKeyEventBus.register(VolumeKeyPriority.HIGH) // visible reader reservation
        VolumeKeyEventBus.register(VolumeKeyPriority.HIGH) // ready content collector
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH) // content starts loading again
        assertTrue(VolumeKeyEventBus.hasActiveConsumer())
        assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH))
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH) // exit reader
        assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.LOW))
        VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW)
        assertFalse(VolumeKeyEventBus.hasActiveConsumer())
    }

    @Test fun `key emitted during reader loading cannot leak to list after reader closes`() = runTest {
        val low = VolumeKeyEventBus.register(VolumeKeyPriority.LOW)
        val received = mutableListOf<VolumeKeyEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { low.collect { received += it } }
        VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
        assertTrue(received.isEmpty())
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)
        assertEquals(listOf(VolumeKeyEvent.PREV), received)
        job.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.LOW)
    }

    @Test fun `overlapping reader owners receive each key once and keep their own queues`() = runTest {
        val first = Any()
        val second = Any()
        val firstFlow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH, first)
        val a = mutableListOf<VolumeKeyEvent>()
        val b = mutableListOf<VolumeKeyEvent>()
        val firstJob = launch(UnconfinedTestDispatcher(testScheduler)) { firstFlow.collect { a += it } }
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)
        val secondFlow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH, second)
        assertFalse(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH, first))
        assertTrue(VolumeKeyEventBus.isActiveConsumer(VolumeKeyPriority.HIGH, second))
        val secondJob = launch(UnconfinedTestDispatcher(testScheduler)) { secondFlow.collect { b += it } }
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        assertEquals(listOf(VolumeKeyEvent.PREV), a)
        assertEquals(listOf(VolumeKeyEvent.NEXT), b)
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH, second)
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)
        assertEquals(listOf(VolumeKeyEvent.PREV, VolumeKeyEvent.PREV), a)
        assertEquals(listOf(VolumeKeyEvent.NEXT), b)
        firstJob.cancel()
        secondJob.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH, first)
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
