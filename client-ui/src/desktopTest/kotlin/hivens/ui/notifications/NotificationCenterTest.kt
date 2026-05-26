package hivens.ui.notifications

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationCenterTest {

    private val clock = FixedClock()

    private fun newCenter() = NotificationCenter(historyPerGroup = 3, clock = clock::now)

    @Test
    fun `push creates new group when sourceKey is new`() = runTest {
        val center = newCenter()
        center.push(
            sourceKey = "pack:Industrial:launch",
            sender    = "Industrial",
            iconUrl   = null,
            severity  = Severity.Progress,
            title     = "Preparing",
        )

        val groups = center.groups.first()
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals("pack:Industrial:launch", g.sourceKey)
        assertEquals("Industrial", g.sender)
        assertEquals(1, g.count)
        assertEquals("Preparing", g.latest.title)
        assertEquals(Severity.Progress, g.latest.severity)
    }

    @Test
    fun `re-push with same sourceKey appends event to existing group`() = runTest {
        val center = newCenter()
        center.push("pack:X", "X", null, Severity.Progress, "Preparing")
        clock.advance(seconds = 2)
        center.push("pack:X", "X", null, Severity.Progress, "Downloading 47%")
        clock.advance(seconds = 5)
        center.push("pack:X", "X", null, Severity.Success, "Done")

        val g = center.groups.first().single()
        assertEquals(3, g.count)
        assertEquals("Done", g.latest.title)
        assertEquals("Preparing", g.events.last().title, "oldest at tail")
    }

    @Test
    fun `group history is capped at historyPerGroup`() = runTest {
        val center = NotificationCenter(historyPerGroup = 2, clock = clock::now)
        center.push("k", "X", null, Severity.Info, "e1")
        center.push("k", "X", null, Severity.Info, "e2")
        center.push("k", "X", null, Severity.Info, "e3")

        val g = center.groups.first().single()
        assertEquals(2, g.count, "oldest event dropped at the cap")
        assertEquals("e3", g.events[0].title)
        assertEquals("e2", g.events[1].title)
    }

    @Test
    fun `severity is max across events`() = runTest {
        val center = newCenter()
        center.push("k", "X", null, Severity.Info, "info")
        center.push("k", "X", null, Severity.Critical, "boom")
        // A later non-critical event must NOT visually downgrade the
        // group -- max wins so the user does not lose track of the
        // earlier critical when scanning the stack.
        center.push("k", "X", null, Severity.Info, "muted recovery")

        val g = center.groups.first().single()
        assertEquals(Severity.Critical, g.severity)
    }

    @Test
    fun `touched group floats to the front of the stack`() = runTest {
        val center = newCenter()
        center.push("k1", "First",  null, Severity.Info, "1")
        center.push("k2", "Second", null, Severity.Info, "2")
        center.push("k3", "Third",  null, Severity.Info, "3")
        // Touch k1 -- it should float to the top, k3 / k2 follow.
        center.push("k1", "First",  null, Severity.Info, "1 again")

        val keys = center.groups.first().map { it.sourceKey }
        assertEquals(listOf("k1", "k3", "k2"), keys)
    }

    @Test
    fun `dismiss removes the group and is idempotent on unknown key`() = runTest {
        val center = newCenter()
        center.push("k", "X", null, Severity.Info, "hi")
        center.dismiss("k")
        assertTrue(center.groups.first().isEmpty())

        center.dismiss("k")
        center.dismiss("never-existed")
        assertTrue(center.groups.first().isEmpty())
    }

    @Test
    fun `clear wipes the stack`() = runTest {
        val center = newCenter()
        center.push("a", "A", null, Severity.Info, "1")
        center.push("b", "B", null, Severity.Info, "2")
        center.clear()
        assertTrue(center.groups.first().isEmpty())
    }

    @Test
    fun `severity auto-dismiss policy is consistent with the docs`() {
        // The renderer + driver depend on these values; lock them in.
        assertNull(Severity.Progress.autoDismissAfter)
        assertNull(Severity.Critical.autoDismissAfter)
        assertEquals(5, Severity.Info.autoDismissAfter?.inWholeSeconds)
        assertEquals(4, Severity.Success.autoDismissAfter?.inWholeSeconds)
        assertEquals(30, Severity.Warn.autoDismissAfter?.inWholeSeconds)
    }

    private class FixedClock(start: Instant = Instant.parse("2026-05-26T00:00:00Z")) {
        private var current: Instant = start
        fun now(): Instant = current
        fun advance(seconds: Long) { current = current.plusSeconds(seconds) }
    }
}
