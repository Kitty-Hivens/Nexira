package hivens.core.diag

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionRingTest {

    @BeforeTest
    fun reset() { ActionRing.clear() }

    @AfterTest
    fun teardown() { ActionRing.clear() }

    @Test
    fun `record appends to snapshot in order`() {
        ActionRing.record("A")
        ActionRing.record("B")
        ActionRing.record("C")
        assertEquals(listOf("A", "B", "C"), ActionRing.snapshot().map { it.text })
    }

    @Test
    fun `capacity is enforced -- oldest entries fall off`() {
        repeat(ActionRing.CAPACITY + 10) { ActionRing.record("e$it") }
        val snap = ActionRing.snapshot()
        assertEquals(ActionRing.CAPACITY, snap.size)
        // Oldest visible entry should be the one at index 10 (first 10 fell off).
        assertEquals("e10", snap.first().text)
        assertEquals("e${ActionRing.CAPACITY + 9}", snap.last().text)
    }

    @Test
    fun `mostRecent returns the latest entry`() {
        assertNull(ActionRing.mostRecent())
        ActionRing.record("only")
        assertEquals("only", ActionRing.mostRecent()?.text)
        ActionRing.record("newer")
        assertEquals("newer", ActionRing.mostRecent()?.text)
    }

    @Test
    fun `an entry is stamped with when it was recorded`() {
        val before = Instant.now()
        ActionRing.record("x")
        ActionRing.record("y")
        val after = Instant.now()
        val (first, second) = ActionRing.snapshot()
        assertTrue(!first.timestamp.isBefore(before) && !first.timestamp.isAfter(after), "stamped at the call, got ${first.timestamp}")
        assertTrue(!second.timestamp.isBefore(first.timestamp), "a later entry is never stamped earlier")
    }

    @Test
    fun `snapshot is a defensive copy -- mutations on the ring after the call don't affect it`() {
        ActionRing.record("first")
        val before = ActionRing.snapshot()
        ActionRing.record("second")
        assertEquals(1, before.size)
        assertEquals(2, ActionRing.snapshot().size)
    }

    @Test
    fun `capacity holds under concurrent writers -- synchronized trim must be atomic`() {
        // Regression test for the prior ConcurrentLinkedDeque + size() implementation,
        // which let racing threads overshoot CAPACITY because size() is documented
        // non-atomic and the trim loop was outside any lock. Spawn many writers,
        // each hammering record(), and assert the ring NEVER exceeds CAPACITY at
        // any observation point.
        //
        // An exception thrown on a writer thread never reaches the test thread, so
        // every overshoot, and anything a writer throws, is collected and asserted
        // on here instead.
        val failures = ConcurrentLinkedQueue<String>()
        val start = CountDownLatch(1)
        val threads = (1..16).map { tIdx ->
            Thread {
                start.await()
                runCatching {
                    repeat(500) { i ->
                        ActionRing.record("t${tIdx}-${i}")
                        // Probe the size while writers are still active -- must not exceed.
                        val size = ActionRing.snapshot().size
                        if (size > ActionRing.CAPACITY) failures += "writer $tIdx saw size=$size"
                    }
                }.onFailure { failures += "writer $tIdx threw $it" }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(emptyList(), failures.toList(), "the ring must stay within CAPACITY=${ActionRing.CAPACITY} under concurrent writers")
        val finalSize = ActionRing.snapshot().size
        assertEquals(ActionRing.CAPACITY, finalSize,
            "after 16×500 records the ring should be exactly at capacity")
    }
}
