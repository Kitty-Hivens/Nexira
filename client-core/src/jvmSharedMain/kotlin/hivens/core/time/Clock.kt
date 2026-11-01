package hivens.core.time

/**
 * Injectable wall clock. Exists so TTL / staleness logic reads time through a
 * seam instead of calling [System.currentTimeMillis] directly -- a [Clock] can
 * be advanced deterministically in tests (see `TestClock` in test fixtures)
 * rather than relying on real time passing.
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** Production clock backed by the system wall clock. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
