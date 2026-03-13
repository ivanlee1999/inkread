package me.ash.reader.infrastructure.android

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

enum class VolumeKeyEvent {
    NEXT,
    PREV,
}

object VolumeKeyEventBus {
    private val channel = Channel<VolumeKeyEvent>(Channel.CONFLATED)
    val events = channel.receiveAsFlow()

    fun emit(event: VolumeKeyEvent) {
        channel.trySend(event)
    }
}
