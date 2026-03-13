package me.ash.reader.infrastructure.android

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class VolumeKeyEvent {
    VOLUME_UP,
    VOLUME_DOWN,
}

object VolumeKeyEventBus {
    private val _events = MutableSharedFlow<VolumeKeyEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    fun emit(event: VolumeKeyEvent) {
        _events.tryEmit(event)
    }
}
