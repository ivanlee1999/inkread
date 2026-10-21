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
    private val activeConsumers = mutableMapOf<VolumeKeyPriority, LinkedHashMap<Any, Int>>()
    private val ownerEvents = mutableMapOf<Pair<VolumeKeyPriority, Any>, MutableSharedFlow<VolumeKeyEvent>>()

    private fun eventFlow(priority: VolumeKeyPriority, owner: Any) =
        ownerEvents.getOrPut(priority to owner) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    /**
     * Register a consumer at the given [priority]. While a higher-priority
     * consumer is registered, lower-priority consumers will ignore events.
     *
     * Returns a [SharedFlow] that the caller should collect. The caller must
     * call [unregister] when it leaves composition / is disposed.
     */
    fun register(priority: VolumeKeyPriority, owner: Any = priority): SharedFlow<VolumeKeyEvent> {
        synchronized(activeConsumers) {
            val owners = activeConsumers.getOrPut(priority) { linkedMapOf() }
            owners[owner] = (owners[owner] ?: 0) + 1
        }
        return events(priority, owner)
    }

    /**
     * Unregister a previously registered consumer.
     */
    fun unregister(priority: VolumeKeyPriority, owner: Any = priority) {
        synchronized(activeConsumers) {
            val owners = activeConsumers[priority] ?: return
            val remaining = (owners[owner] ?: 0) - 1
            if (remaining > 0) owners[owner] = remaining
            else {
                owners.remove(owner)
                ownerEvents.remove(priority to owner)
            }
            if (owners.isEmpty()) activeConsumers.remove(priority)
        }
    }

    /** Leave volume controls alone on feeds, settings, or any screen without a pager. */
    fun hasActiveConsumer(): Boolean = synchronized(activeConsumers) { activeConsumers.isNotEmpty() }

    /** Collect without owning focus when a containing reader already owns it. */
    fun events(priority: VolumeKeyPriority, owner: Any = priority): SharedFlow<VolumeKeyEvent> =
        synchronized(activeConsumers) { eventFlow(priority, owner).asSharedFlow() }

    /**
     * Returns true if [priority] is currently the highest active priority,
     * meaning it should handle events.
     */
    fun isActiveConsumer(priority: VolumeKeyPriority, owner: Any? = null): Boolean {
        synchronized(activeConsumers) {
            if (activeConsumers.isEmpty()) return false
            return activeConsumers.keys.max() == priority &&
                (owner == null || activeConsumers.getValue(priority).keys.lastOrNull() === owner)
        }
    }

    /**
     * Route at emission, not collection: a reader key queued just before closing
     * the reader must never become a list turn. No debounce — every valid
     * press is delivered. The buffer (capacity 8) absorbs short bursts during
     * e-ink refresh without building an unbounded queue.
     */
    fun emit(event: VolumeKeyEvent) {
        synchronized(activeConsumers) {
            activeConsumers.keys.maxOrNull()?.let { priority ->
                activeConsumers.getValue(priority).keys.lastOrNull()?.let { owner ->
                    eventFlow(priority, owner).tryEmit(event)
                }
            }
        }
    }

    /**
     * Reset all internal state. Intended for use in tests only.
     */
    fun resetForTest() {
        synchronized(activeConsumers) { activeConsumers.clear(); ownerEvents.clear() }
    }
}
