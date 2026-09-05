package hivens.core.net

import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every transfer in a set reports from its own thread, and what the consumer is
 * told last is what it goes on showing. The counters being atomic is not enough
 * for that: a reporter descheduled between reading them and handing them over
 * delivers an older picture after a newer one, and at the end of a set the older
 * one is final -- a bar stopped at four of five files that never moves again.
 */
class SetProgressTest {

    private fun transfer(i: Int) = Transfer("https://example.invalid/$i", Path.of("/tmp/nx-set-$i"), size = 1L)

    /** Runs [work] on [threads] threads that all start together. */
    private fun racing(threads: Int, work: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val barrier = CyclicBarrier(threads)
            (0 until threads)
                .map { i -> pool.submit { barrier.await(); work(i) } }
                .forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `reports reach the consumer one at a time`() {
        val files = 8
        val inside = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val tracker = SetProgress(files.toLong(), files, {
            if (inside.incrementAndGet() > 1) overlaps.incrementAndGet()
            Thread.sleep(2)
            inside.decrementAndGet()
        })

        racing(files) { i -> tracker.finished(transfer(i)) }

        assertEquals(
            0,
            overlaps.get(),
            "two transfers reported at once, so which report lands last is up to the scheduler",
        )
    }

    @Test
    fun `the last report of a set accounts for every file`() {
        val files = 5
        var stale = 0
        repeat(2_000) {
            var last: TransferProgress? = null
            val tracker = SetProgress(files.toLong(), files, { last = it })

            racing(files) { i -> tracker.finished(transfer(i)) }

            if (last?.filesDone != files) stale++
        }
        assertEquals(0, stale, "$stale of 2000 sets ended on a report that had not counted every file")
    }
}
