package com.jarvis.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Only one part of Jarvis can use the microphone at a time.
 *
 * Conversations (HUD, calls, voice enrollment) "hold" the mic; while anyone
 * holds it, the background wake-word listener pauses. Holds are counted, so
 * nested holds work and the listener resumes only when everyone is done.
 */
class MicArbiter {
    private val holders = MutableStateFlow(0)

    val busy: StateFlow<Int> get() = holders

    val isBusy: Boolean get() = holders.value > 0

    fun busyFlow() = holders.map { it > 0 }

    suspend fun <T> hold(block: suspend () -> T): T {
        holders.update { it + 1 }
        try {
            return block()
        } finally {
            holders.update { it - 1 }
        }
    }
}
