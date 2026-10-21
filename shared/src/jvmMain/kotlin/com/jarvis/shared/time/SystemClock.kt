package com.jarvis.shared.time

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime

/** The real wall clock in the phone's time zone. */
object SystemClock : JarvisClock {
    override fun now(): LocalDateTime = java.time.LocalDateTime.now().toKotlinLocalDateTime()
}
