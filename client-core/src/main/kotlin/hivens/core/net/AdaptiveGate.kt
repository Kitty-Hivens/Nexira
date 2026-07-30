package hivens.core.net

import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

/**
 * How many transfers may be in flight at once, decided while they run.
 *
 * Two signals, and their order is the whole point. **Errors outrank speed.** A
 * transient failure halves the pool immediately and backs off probing; only a
 * quiet period earns a probe for more. The failure this exists for is a route
 * where a middlebox cuts connections, and on such a route low throughput and
 * high error rate arrive together -- a controller that reads only the throughput
 * would add sockets exactly where sockets are being killed, and call the
 * resulting collapse a reason to add more.
 *
 * Growth is measured, not assumed. Before widening the pool the gate records how
 * much moved in a window, widens by one, and measures the same window again; a
 * gain under [MIN_GAIN] means the extra connection is dividing the same
 * bandwidth rather than finding more, so it is given back. Without that test a
 * pool grows until something breaks whenever the bottleneck is the far end or
 * the disk, neither of which cares how many sockets we opened.
 *
 * [nowMs] is injected so the controller is testable without waiting in real time.
 */
class AdaptiveGate(
    initial: Int = DEFAULT_INITIAL,
    private val min: Int = 1,
    private val max: Int = DEFAULT_MAX,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AdaptiveGate::class.java)

    /**
     * One element per allowed slot. UNLIMITED because the channel is only ever a
     * counter here: sends come from [release] and [grow], and there are never
     * more tokens outstanding than [max].
     */
    private val tokens = Channel<Unit>(Channel.UNLIMITED)

    private val lock = Any()
    private var target: Int = initial.coerceIn(min, max)
    private var inFlight: Int = 0

    /**
     * Slots removed from [target] that are still held by a running transfer.
     * Shrinking cannot take a permit back from work in progress, so the debt is
     * paid as those transfers release.
     */
    private var debt: Int = 0

    private var cumulativeBytes: Long = 0L
    private var phase: Phase = Phase.Steady
    private var windowStartAt: Long = 0L
    private var windowStartBytes: Long = 0L
    private var baselineRate: Long = 0L
    private var probeIntervalMs: Long = PROBE_INTERVAL_MS
    private var lastDecisionAt: Long = 0L
    private var errorSinceDecision: Boolean = false

    init {
        repeat(target) { tokens.trySend(Unit) }
        lastDecisionAt = nowMs()
    }

    /** Current ceiling, for logs and tests. */
    val permits: Int get() = synchronized(lock) { target }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        tokens.receive()
        synchronized(lock) { inFlight++ }
    }

    private fun release() {
        synchronized(lock) {
            inFlight--
            if (debt > 0) {
                debt--
                return
            }
        }
        tokens.trySend(Unit)
    }

    /**
     * Bytes that arrived, from any transfer. Drives the growth probe; called on
     * every read, so it must stay cheap -- the work is two long adds and a clock
     * comparison until a window actually closes.
     */
    fun onBytes(n: Long) {
        if (n <= 0L) return
        val decision: String?
        synchronized(lock) {
            cumulativeBytes += n
            decision = advance(nowMs())
        }
        if (decision != null) log.debug("transfer gate: {}", decision)
    }

    /**
     * A transient failure happened. Halves the pool and stops probing for a
     * while: on a route that resets connections, fewer of them is the only
     * lever we have.
     */
    fun onTransientError() {
        val message: String
        synchronized(lock) {
            // The phase is left alone deliberately: a probe already under way ends
            // by being abandoned when its window closes, so the growth it was
            // measuring for cannot land on the back of a failing route.
            errorSinceDecision = true
            probeIntervalMs = (probeIntervalMs * 2).coerceAtMost(MAX_PROBE_INTERVAL_MS)
            lastDecisionAt = nowMs()
            val before = target
            shrinkTo(maxOf(min, target / 2))
            message = "transient error, $before -> $target permits, probing paused for ${probeIntervalMs}ms"
        }
        log.info("transfer gate: {}", message)
    }

    /** Must hold [lock]. Returns a line to log when a decision was taken. */
    private fun advance(now: Long): String? {
        when (phase) {
            Phase.Steady -> {
                // Only probe while every permit is busy. Widening a pool that is
                // not even full measures nothing.
                if (target >= max || inFlight < target) return null
                if (now - lastDecisionAt < probeIntervalMs) return null
                startWindow(now)
                phase = Phase.Baseline
                errorSinceDecision = false
                return null
            }
            Phase.Baseline -> {
                if (now - windowStartAt < WINDOW_MS) return null
                if (errorSinceDecision) return abandon(now)
                baselineRate = rateOverWindow(now)
                grow()
                startWindow(now)
                phase = Phase.Probe
                return null
            }
            Phase.Probe -> {
                if (now - windowStartAt < WINDOW_MS) return null
                phase = Phase.Steady
                lastDecisionAt = now
                if (errorSinceDecision) return abandon(now)
                val probeRate = rateOverWindow(now)
                val worthIt = probeRate >= baselineRate + (baselineRate * MIN_GAIN_PERCENT / 100)
                return if (worthIt) {
                    probeIntervalMs = PROBE_INTERVAL_MS
                    "kept $target permits ($baselineRate -> $probeRate B/s)"
                } else {
                    shrinkTo(target - 1)
                    probeIntervalMs = (probeIntervalMs * 2).coerceAtMost(MAX_PROBE_INTERVAL_MS)
                    "gave the extra permit back at $target ($baselineRate -> $probeRate B/s)"
                }
            }
        }
    }

    private fun abandon(now: Long): String? {
        phase = Phase.Steady
        lastDecisionAt = now
        return null
    }

    private fun startWindow(now: Long) {
        windowStartAt = now
        windowStartBytes = cumulativeBytes
    }

    private fun rateOverWindow(now: Long): Long {
        val elapsed = (now - windowStartAt).coerceAtLeast(1L)
        return (cumulativeBytes - windowStartBytes) * 1000 / elapsed
    }

    private fun grow() {
        if (target >= max) return
        target++
        // A slot owed back cancels against the new one instead of opening a
        // connection the shrink just decided against.
        if (debt > 0) debt-- else tokens.trySend(Unit)
    }

    /** Must hold [lock]. Takes idle tokens first, and books the rest as debt. */
    private fun shrinkTo(newTarget: Int) {
        val bounded = newTarget.coerceIn(min, max)
        var toRemove = target - bounded
        if (toRemove <= 0) return
        target = bounded
        while (toRemove > 0 && tokens.tryReceive().isSuccess) toRemove--
        debt += toRemove
    }

    private enum class Phase { Steady, Baseline, Probe }

    private companion object {
        /**
         * Four at rest. OkHttp's dispatcher allows five requests per host by
         * default, so a ceiling above that needs the dispatcher raised in step or
         * the extra permits queue inside the client and measure as no gain.
         */
        const val DEFAULT_INITIAL = 4
        const val DEFAULT_MAX = 8
        const val WINDOW_MS = 3_000L
        const val PROBE_INTERVAL_MS = 10_000L
        const val MAX_PROBE_INTERVAL_MS = 120_000L
        const val MIN_GAIN_PERCENT = 15
    }
}
