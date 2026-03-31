package me.ash.reader.infrastructure.android

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class VolumeKeyEvent {
    NEXT,
    PREV,
}

/**
 * Priority levels for volume key event consumers.
 * Higher priority consumers receive events exclusively when active.
 * ReadingPage (article view) uses [HIGH]; FlowPage (list) uses [LOW].
 */
enum class VolumeKeyPriority {
    LOW,
    HIGH,
}

object VolumeKeyEventBus {
    private var _events = MutableSharedFlow<VolumeKeyEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Tracks active consumers by priority. Events are delivered only to the
     * highest-priority active consumer, preventing duplicate handling in
     * two-pane layouts where both FlowPage and ReadingPage collect simultaneously.
     */
    private val activeConsumers = mutableSetOf<VolumeKeyPriority>()

    /**
     * Register a consumer at the given [priority]. While a higher-priority
     * consumer is registered, lower-priority consumers will ignore events.
     *
     * Returns a [SharedFlow] that the caller should collect. The caller must
     * call [unregister] when it leaves composition / is disposed.
     */
    fun register(priority: VolumeKeyPriority): SharedFlow<VolumeKeyEvent> {
        synchronized(activeConsumers) { activeConsumers.add(priority) }
        return _events.asSharedFlow()
    }

    /**
     * Unregister a previously registered consumer.
     */
    fun unregister(priority: VolumeKeyPriority) {
        synchronized(activeConsumers) { activeConsumers.remove(priority) }
    }

    /**
     * Returns true if [priority] is currently the highest active priority,
     * meaning it should handle events.
     */
    fun isActiveConsumer(priority: VolumeKeyPriority): Boolean {
        synchronized(activeConsumers) {
            if (activeConsumers.isEmpty()) return false
            return activeConsumers.max() == priority
        }
    }

    /**
     * Emit a volume key event to all collectors. No debounce — every valid
     * press is delivered. The buffer (capacity 8) absorbs short bursts during
     * e-ink refresh without building an unbounded queue.
     */
    fun emit(event: VolumeKeyEvent) {
        _events.tryEmit(event)
    }

    /**
     * Reset all internal state. Intended for use in tests only.
     */
    fun resetForTest() {
        synchronized(activeConsumers) { activeConsumers.clear() }
        _events = MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }
}
