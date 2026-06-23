package com.wuyi.libraryauto.core.ble

class BleScanThrottler(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val attemptsByBookingId = mutableMapOf<String, ArrayDeque<Long>>()

    init {
        require(windowMillis > 0) { "windowMillis must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun tryAcquire(bookingId: String): Boolean {
        require(bookingId.isNotBlank()) { "bookingId must not be blank" }

        val now = nowMillis()
        val attempts = attemptsByBookingId.getOrPut(bookingId) { ArrayDeque() }
        while (attempts.isNotEmpty() && now - attempts.first() >= windowMillis) {
            attempts.removeFirst()
        }
        if (attempts.size >= maxAttempts) {
            return false
        }
        attempts.addLast(now)
        return true
    }

    fun reset(bookingId: String? = null) {
        if (bookingId == null) {
            attemptsByBookingId.clear()
        } else {
            attemptsByBookingId.remove(bookingId)
        }
    }

    private companion object {
        private const val DEFAULT_WINDOW_MILLIS = 30_000L
        private const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
