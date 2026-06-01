package hivens.test

import hivens.core.time.Clock

/**
 * Manually-advanced [Clock] for deterministic TTL/staleness tests. Lives in test
 * fixtures (like `buildMockClient`) so both client-core and client-launcher
 * tests can drive cache age without sleeping. Thread-safe enough for the
 * single-writer/multi-reader use in cache tests.
 */
class TestClock(start: Long = 0L) : Clock {
    @Volatile
    private var now = start

    override fun nowMillis(): Long = now

    fun advance(ms: Long) {
        now += ms
    }

    fun set(ms: Long) {
        now = ms
    }
}
