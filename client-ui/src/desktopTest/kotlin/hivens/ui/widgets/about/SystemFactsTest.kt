package hivens.ui.widgets.about

import hivens.core.jvm.SystemHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The About card's facts are read off the thread that asks for them, which is the
 * one laying the page out. Reading /proc there stalled it, and on a phone the read
 * of the swap table is denied every time.
 */
class SystemFactsTest {

    @Test
    fun `the facts start unknown and are read on another thread`() {
        val scope = CoroutineScope(SupervisorJob())
        val asking = Thread.currentThread()
        var readOn: Thread? = null
        val answer = CountDownLatch(1)
        try {
            val facts = systemFactsReadOff(scope, Dispatchers.IO) {
                readOn = Thread.currentThread()
                // Bounded, so a read run on the asking thread fails the test instead of hanging it.
                answer.await(5, TimeUnit.SECONDS)
                SystemFacts(ramMb = 8192, swapMb = 4096, cpu = SystemHardware.CpuInfo(4, 8, null, null))
            }
            assertEquals(0, facts.value.ramMb, "nothing is known before the read answers")
            assertNull(facts.value.swapMb)

            answer.countDown()
            val read = runBlocking { withTimeout(5.seconds) { facts.first { it.ramMb > 0 } } }
            assertEquals(4096, read.swapMb)
            assertNotSame(asking, readOn, "the read ran on the thread that asked")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a read that fails leaves the facts unknown`() {
        val scope = CoroutineScope(SupervisorJob())
        val done = CountDownLatch(1)
        try {
            val facts = systemFactsReadOff(scope, Dispatchers.IO) {
                done.countDown()
                throw IOException("denied")
            }
            done.await()
            Thread.sleep(50)
            assertEquals(0, facts.value.ramMb)
            assertEquals(Runtime.getRuntime().availableProcessors(), facts.value.cpu.logicalThreads)
        } finally {
            scope.cancel()
        }
    }
}
