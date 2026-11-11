package hivens.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveGateTest {

    private var now = 0L

    private fun gate(initial: Int, max: Int) =
        AdaptiveGate(initial = initial, min = 1, max = max, nowMs = { now })

    @Test
    fun `permits survive cancellation racing the handover`() = runBlocking {
        // The loss window needs a receiver actually PARKED on an empty channel and
        // an element handed to it as it is cancelled. So drain the pool first, queue
        // waiters behind it, then cancel and release at the same moment.
        val gate = AdaptiveGate(initial = 4, min = 1, max = 4, nowMs = { 0L })

        repeat(300) {
            val holdersIn = CountDownLatch(4)
            val letHoldersGo = CompletableDeferred<Unit>()
            val holders = List(4) {
                launch(Dispatchers.IO) { gate.withPermit { holdersIn.countDown(); letHoldersGo.await() } }
            }
            withContext(Dispatchers.IO) { holdersIn.await(5, TimeUnit.SECONDS) }

            // Pool is empty; these park inside acquire().
            val waiters = List(8) { launch(Dispatchers.IO) { gate.withPermit { } } }
            withContext(Dispatchers.IO) { Thread.sleep(1) }

            // Cancel the parked waiters while the holders hand their tokens back.
            letHoldersGo.complete(Unit)
            waiters.forEach { it.cancel() }
            holders.forEach { it.join() }
            waiters.forEach { it.join() }
        }

        // If any permit leaked, fewer than four holders fit at once and this times out.
        val allFour = withTimeoutOrNull(5_000) {
            val entered = CountDownLatch(4)
            val release = CompletableDeferred<Unit>()
            val jobs = List(4) { launch(Dispatchers.IO) { gate.withPermit { entered.countDown(); release.await() } } }
            val ok = withContext(Dispatchers.IO) { entered.await(4, TimeUnit.SECONDS) }
            release.complete(Unit)
            jobs.forEach { it.join() }
            ok
        }
        assertEquals(true, allFour, "the pool drained -- downloads would hang with no error")
    }

    @Test
    fun `an error halves the pool and keeps halving it down to one`() {
        val gate = gate(initial = 8, max = 8)

        gate.onTransientError()
        assertEquals(4, gate.permits)
        gate.onTransientError()
        assertEquals(2, gate.permits)
        gate.onTransientError()
        assertEquals(1, gate.permits)
        gate.onTransientError()
        assertEquals(1, gate.permits, "the pool went below one and would have stalled")
    }

    @Test
    fun `a permit that does not earn its bandwidth is given back`() = runTest {
        val gate = gate(initial = 1, max = 4)
        val holder = holdOnePermit(gate)

        // Past the probe interval and saturated, so the gate takes a baseline.
        now = 10_000
        gate.onBytes(1_000)
        assertEquals(1, gate.permits, "the pool grew before it had measured anything")

        // Baseline window closes at 1000 B/s, and the gate widens to find out
        // whether there is more bandwidth to be had.
        now = 13_000
        gate.onBytes(3_000)
        assertEquals(2, gate.permits, "the gate never probed for more")

        // The probe window moves the same bytes, so the second connection is
        // dividing the same bandwidth rather than finding any.
        now = 16_000
        gate.onBytes(3_000)
        assertEquals(1, gate.permits, "a permit that bought nothing was kept")

        holder.release()
    }

    @Test
    fun `a permit that does earn its bandwidth is kept`() = runTest {
        val gate = gate(initial = 1, max = 4)
        val holder = holdOnePermit(gate)

        now = 10_000
        gate.onBytes(1_000)
        now = 13_000
        gate.onBytes(3_000)
        assertEquals(2, gate.permits)

        // Twice the throughput over the same window: the extra connection found
        // bandwidth that was there to find.
        now = 16_000
        gate.onBytes(6_000)
        assertEquals(2, gate.permits, "a permit that paid for itself was given back")

        holder.release()
    }

    @Test
    fun `an error mid-probe abandons the probe instead of growing on a failing route`() = runTest {
        val gate = gate(initial = 2, max = 4)
        val holder = holdOnePermit(gate)
        val second = holdOnePermit(gate)

        now = 10_000
        gate.onBytes(1_000)

        // The route starts resetting connections while the baseline is being taken.
        gate.onTransientError()
        assertEquals(1, gate.permits)

        // The window closes on what was going to be a growth decision.
        now = 13_000
        gate.onBytes(3_000)
        assertEquals(1, gate.permits, "the gate grew on the back of a failing route")

        holder.release()
        second.release()
    }

    /**
     * Holds one permit for the rest of the test, because the gate only probes while
     * every permit is busy -- widening a pool that is not even full would measure
     * nothing.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.holdOnePermit(gate: AdaptiveGate): Holder {
        val release = CompletableDeferred<Unit>()
        val job = launch { gate.withPermit { release.await() } }
        // Let it get as far as holding the permit and suspending on the release.
        yield()
        return Holder(release, job)
    }

    private class Holder(private val release: CompletableDeferred<Unit>, private val job: kotlinx.coroutines.Job) {
        suspend fun release() {
            release.complete(Unit)
            job.join()
        }
    }
}
