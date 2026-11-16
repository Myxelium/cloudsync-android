package dev.cloudsync.core.retry

import dev.cloudsync.core.error.isRetryable
import dev.cloudsync.core.error.isRetryableStatus
import kotlin.math.min
import kotlin.math.pow

object RetryPolicy {
    const val MAX_ATTEMPTS = 8

    fun nextDelayMillis(attempt: Int, initialMillis: Long = 2_000, maxMillis: Long = 15 * 60_000): Long? {
        if (attempt >= MAX_ATTEMPTS) return null
        val multiplier = 2.0.pow(attempt.coerceAtLeast(0).toDouble())
        return min(maxMillis, (initialMillis * multiplier).toLong())
    }

    fun shouldRetry(error: Throwable, attempt: Int): Boolean = isRetryable(error) && nextDelayMillis(attempt) != null

    fun shouldRetryStatus(code: Int, attempt: Int): Boolean = isRetryableStatus(code) && nextDelayMillis(attempt) != null
}
