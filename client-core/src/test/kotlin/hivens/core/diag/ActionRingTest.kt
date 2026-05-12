package hivens.core.diag

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun `capacity is enforced — oldest entries fall off`() {
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
    fun `every entry carries a non-null timestamp`() {
        ActionRing.record("x")
        val entry = ActionRing.snapshot().first()
        assertNotNull(entry.timestamp)
    }

    @Test
    fun `snapshot is a defensive copy — mutations on the ring after the call don't affect it`() {
        ActionRing.record("first")
        val before = ActionRing.snapshot()
        ActionRing.record("second")
        assertEquals(1, before.size)
        assertEquals(2, ActionRing.snapshot().size)
    }
}
