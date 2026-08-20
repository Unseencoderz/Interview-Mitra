package com.interviewmitra.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Service-to-session bridge: PCM is never persisted and has a small bounded buffer for latency. */
object AudioBus {
    val pcm = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
