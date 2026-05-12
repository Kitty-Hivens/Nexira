package hivens.core.diag

import java.time.Instant

/**
 * Bounded thread-safe ring buffer of the last [CAPACITY] user-visible / lifecycle
 * actions, with timestamps. Replaces the prior single-mutable-string
 * `CrashReporter.lastAction` which only remembered the most recent action and
 * therefore lost the sequence leading up to a crash.
 *
 * Usage: `ActionRing.record("Launching: Industrial")` at every action boundary
 * (Play click, auth result, manifest sync start, game exit, etc.). On crash,
 * [snapshot] is folded into the crash report and the diagnostic bundle so a
 * support reader can reconstruct what the user did just before things broke.
 *
 * Concurrent reads/writes are safe — actions can be recorded from any thread
 * (UI, IO, game-output piping) without external synchronisation.
 *
 * Implementation note: previous version used `ConcurrentLinkedDeque` and
 * `ring.size > CAPACITY` to trim. JDK documents that deque's `size()` is NOT
 * a constant-time, accurate snapshot under concurrent modification — racing
 * `record()` calls could leave the ring above CAPACITY, breaking the bounded
 * contract. Switched to a plain `ArrayDeque` guarded by a `synchronized`
 * block. `record` is invoked maybe a few hundred times per process lifetime;
 * lock contention is irrelevant compared to the correctness gain.
 */
object ActionRing {

    /**
     * Last 64 actions: enough to span one full launch attempt plus several
     * mid-flow events (auth, manifest, sync, JVM prepare, launch, exit) plus
     * earlier failed attempts that often provide the context the user
     * forgot to mention in the bug report.
     */
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

    /** Returns the current snapshot oldest-first. Cheap defensive copy. */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    /**
     * Convenience for the most recent entry — covers the legacy
     * `CrashReporter.lastAction` use case while richer crash reports
     * adopt [snapshot].
     */
    fun mostRecent(): Entry? = synchronized(lock) { ring.lastOrNull() }

    /**
     * Test-only — never used in production. Public (rather than `internal`)
     * because tests live in `:client-launcher` while this class lives in
     * `:client-core`, and `internal` doesn't span module boundaries. Calling
     * this in production would erase the diagnostic trail right when it's
     * most useful, so don't.
     */
    fun clear() = synchronized(lock) { ring.clear() }
}
