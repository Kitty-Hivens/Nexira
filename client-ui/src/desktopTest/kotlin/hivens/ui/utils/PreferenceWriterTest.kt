package hivens.ui.utils

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PreferenceWriterTest {

    /** Two flips of one switch landing the other way round leave the first one on disk. */
    @Test
    fun `writes land in the order they were asked for, off the caller's thread`() {
        val writer = PreferenceWriter()
        val landed = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        // Held until everything is queued, so the order is the queue's and not the
        // order in which the first few happened to finish.
        val gate = CountDownLatch(1)
        writer.write("held") { gate.await(5, TimeUnit.SECONDS) }
        repeat(50) { i -> writer.write("write $i") { threads += Thread.currentThread(); landed += i } }
        gate.countDown()

        writer.drain(5.seconds)

        assertEquals((0 until 50).toList(), landed)
        assertTrue(Thread.currentThread() !in threads, "nothing was written on the thread that asked")
    }

    @Test
    fun `a write that fails does not stop the ones after it`() {
        val writer = PreferenceWriter()
        val landed = Collections.synchronizedList(mutableListOf<String>())
        writer.write("broken") { error("disk full") }
        writer.write("next") { landed += "next" }

        writer.drain(5.seconds)

        assertEquals(listOf("next"), landed)
    }

    /** A choice made just before quitting must still reach the disk. */
    @Test
    fun `draining lets the queued writes land, and a later one runs where it was asked`() {
        val writer = PreferenceWriter()
        val gate = CountDownLatch(1)
        var queued = false
        writer.write("slow") { gate.await(5, TimeUnit.SECONDS) }
        writer.write("queued") { queued = true }
        Thread { Thread.sleep(100); gate.countDown() }.start()

        writer.drain(5.seconds)
        assertTrue(queued, "the drain waited for what was queued")

        var ranOn: Thread? = null
        writer.write("late") { ranOn = Thread.currentThread() }
        assertEquals(Thread.currentThread(), ranOn)
    }
}
