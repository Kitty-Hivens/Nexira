package hivens.core.diag

import java.time.Instant

/**
 * Bounded thread-safe ring buffer of the last [CAPACITY] user-visible /
 * lifecycle actions with timestamps. [snapshot] is folded into crash
 * reports and the diagnostic bundle so a support reader can reconstruct
 * what the user did just before things broke.
 *
 * Usage: `ActionRing.record("Launching: Industrial")` at every action
 * boundary (Play click, auth result, manifest sync start, game exit).
 * Safe to call from any thread.
 *
 * Implementation: `ArrayDeque` under a `synchronized` block (not
 * `ConcurrentLinkedDeque`). The JDK documents that deque's `size()` is
 * not a constant-time accurate snapshot under concurrent modification
 * -- racing `record()` calls could leave the ring above CAPACITY,
 * breaking the bounded contract. `record` runs maybe a few hundred
 * times per process lifetime; lock contention is irrelevant.
 */
object ActionRing {

    /** Last 64 actions: covers one launch attempt plus earlier failed attempts. */
    const val CAPACITY: Int = 64

    data class Entry(
        val timestamp: Instant,
        val text: String,
    )

    private val lock = Any()
    private val ring = ArrayDeque<Entry>(CAPACITY)

    fun record(text: String) {
        val entry = Entry(Instant.now(), text)
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(entry)
        }
    }

    /** Current snapshot, oldest-first. Cheap defensive copy. */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    /** Most recent entry, or null when empty. */
    fun mostRecent(): Entry? = synchronized(lock) { ring.lastOrNull() }

    /**
     * Test-only. Public (not `internal`) because tests live in
     * `:client-launcher` while this class lives in `:client-core`, and
     * `internal` does not span module boundaries. Calling this in
     * production erases the diagnostic trail when it is most useful.
     */
    fun clear() = synchronized(lock) { ring.clear() }
}
