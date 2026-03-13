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

    private var lastEmitTime = 0L
    private const val COOLDOWN_MS = 400L

    fun emit(event: VolumeKeyEvent) {
        val now = System.currentTimeMillis()
        if (now - lastEmitTime >= COOLDOWN_MS) {
            lastEmitTime = now
            channel.trySend(event)
        }
    }
}
