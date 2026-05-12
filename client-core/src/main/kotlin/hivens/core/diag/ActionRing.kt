package hivens.core.diag

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Bounded thread-safe ring buffer of the last [capacity] user-visible / lifecycle
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

    // ConcurrentLinkedDeque is wait-free for the common record path and we
    // only trim from the head when over-capacity, also lock-free. The size()
    // call is O(n) but we only check it on each record, which is rare
    // compared to other launcher hot paths.
    private val ring = ConcurrentLinkedDeque<Entry>()

    fun record(text: String) {
        ring.add(Entry(Instant.now(), text))
        while (ring.size > CAPACITY) {
            ring.pollFirst()
        }
    }

    /** Returns the current snapshot oldest-first. Cheap defensive copy. */
    fun snapshot(): List<Entry> = ring.toList()

    /**
     * Convenience for the most recent entry — covers the legacy
     * `CrashReporter.lastAction` use case while richer crash reports
     * adopt [snapshot].
     */
    fun mostRecent(): Entry? = ring.peekLast()

    /** Test-only — never used in production. */
    internal fun clear() {
        ring.clear()
    }
}
