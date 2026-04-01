package me.ash.reader.infrastructure.android

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VolumeKeyEventBusTest {

    @Before
    fun setUp() {
        VolumeKeyEventBus.resetForTest()
    }

    @After
    fun tearDown() {
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
    fun `buffer survives burst larger than 1 event`() = runTest {
        // Don't start collecting yet — events should buffer
        val flow = VolumeKeyEventBus.register(VolumeKeyPriority.HIGH)

        // Emit several events before any collector is active
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)
        VolumeKeyEventBus.emit(VolumeKeyEvent.PREV)
        VolumeKeyEventBus.emit(VolumeKeyEvent.NEXT)

        // Now start collecting — should get buffered events
        val collected = mutableListOf<VolumeKeyEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { collected.add(it) }
        }

        // With extraBufferCapacity=8, all 3 should be buffered and delivered
        assertEquals(3, collected.size)
        assertEquals(VolumeKeyEvent.NEXT, collected[0])
        assertEquals(VolumeKeyEvent.PREV, collected[1])
        assertEquals(VolumeKeyEvent.NEXT, collected[2])

        job.cancel()
        VolumeKeyEventBus.unregister(VolumeKeyPriority.HIGH)
    }
}
