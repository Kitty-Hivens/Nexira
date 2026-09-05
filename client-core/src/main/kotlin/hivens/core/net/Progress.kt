package hivens.core.net

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Byte counter for one transfer that is being fetched in blocks.
 *
 * [rewind] exists because a block that ends early has to give its bytes back:
 * the block is not marked done, the retry asks for the whole range again, and a
 * progress bar that kept the partial count would drift past 100% on a bad line.
 */
internal class BlockProgress(
    base: Long,
    private val total: Long,
    private val onProgress: (Long, Long) -> Unit,
) {
    private val done = AtomicLong(base.coerceAtMost(total))

    fun advance(n: Long) {
        onProgress(done.addAndGet(n).coerceAtMost(total), total)
    }

    fun rewind(n: Long) {
        if (n > 0) done.addAndGet(-n)
    }

    fun report() {
        onProgress(done.get().coerceAtMost(total), total)
    }
}

/**
 * Aggregates progress across a whole set of transfers into one number pair plus
 * a file count.
 *
 * Emission is throttled: a set of two thousand asset objects reporting every
 * 64 KiB would push tens of thousands of state updates a second into the UI,
 * which is how a download makes a Compose window drop frames while it works.
 * The terminal state is always emitted, so a caller that only draws on change
 * still ends at the true total.
 *
 * The per-file last-known count is kept so the aggregate can be moved by a delta
 * instead of summing the map on every chunk. One coroutine owns one file's entry,
 * so the read-modify-write on it needs no lock of its own.
 *
 * Emission itself IS serialized, and that is not about the counters -- they are
 * atomic. It is about the order the consumer is told things in. Every transfer in
 * a set reports from its own thread, so a reporter descheduled between reading
 * the counters and handing them over delivers a stale picture after a fresher
 * one. At the end of a set that is the last word the consumer gets, and a bar
 * that stops at four of five files never moves again.
 */
internal class SetProgress(
    private val totalBytes: Long,
    private val filesTotal: Int,
    private val onProgress: (TransferProgress) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val perFile = ConcurrentHashMap<Path, Long>()
    private val doneBytes = AtomicLong(0)
    private val doneFiles = AtomicLong(0)
    private val lastEmitAt = AtomicLong(0)
    private val emitLock = Any()

    @Volatile
    private var current: String = ""

    // Windowed rate: what the last stretch actually moved, not the average since
    // the start, which sags for the whole download after one stall.
    private val windowStartAt = AtomicLong(nowMs())
    private val windowStartBytes = AtomicLong(0)

    @Volatile
    private var rate: Long = 0

    fun starting(t: Transfer) {
        current = t.dest.fileName.toString()
    }

    fun fileAt(t: Transfer, done: Long) {
        val key = t.dest
        val previous = perFile.put(key, done) ?: 0L
        val delta = done - previous
        if (delta != 0L) doneBytes.addAndGet(delta)
        emitMaybe(force = false)
    }

    fun finished(t: Transfer) {
        // A transfer that was skipped or whose size was unknown never reported
        // bytes; count it at its declared size so the bar reaches the end.
        if (t.size > 0L) {
            val previous = perFile.put(t.dest, t.size) ?: 0L
            doneBytes.addAndGet(t.size - previous)
        }
        doneFiles.incrementAndGet()
        emitMaybe(force = true)
    }

    private fun emitMaybe(force: Boolean) {
        val now = nowMs()
        val last = lastEmitAt.get()
        if (!force && now - last < EMIT_INTERVAL_MS) return
        if (!force && !lastEmitAt.compareAndSet(last, now)) return
        if (force) lastEmitAt.set(now)

        // Reading the counters and delivering them is one step, or the delivery
        // order stops matching the order the counts were taken in. The throttle
        // above means this is entered rarely; the forced emissions at the end of
        // a set are the ones that have to arrive in the right order.
        synchronized(emitLock) {
            val bytes = doneBytes.get()
            val windowMs = now - windowStartAt.get()
            if (windowMs >= RATE_WINDOW_MS) {
                rate = (bytes - windowStartBytes.get()) * 1000 / windowMs
                windowStartAt.set(now)
                windowStartBytes.set(bytes)
            }
            onProgress(
                TransferProgress(
                    done = bytes,
                    total = totalBytes,
                    bytesPerSecond = rate,
                    filesDone = doneFiles.get().toInt(),
                    filesTotal = filesTotal,
                    current = current,
                )
            )
        }
    }

    private companion object {
        const val EMIT_INTERVAL_MS = 100L
        const val RATE_WINDOW_MS = 1_000L
    }
}
