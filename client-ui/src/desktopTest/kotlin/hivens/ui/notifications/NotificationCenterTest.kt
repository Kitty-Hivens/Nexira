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
            severity  = Severity.Info,
            kind      = Kind.Progress,
            title     = "Preparing",
        )

        val groups = center.groups.first()
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals("pack:Industrial:launch", g.sourceKey)
        assertEquals("Industrial", g.sender)
        assertEquals(1, g.count)
        assertEquals("Preparing", g.latest.title)
        assertEquals(Severity.Info, g.latest.severity)
        assertEquals(Kind.Progress, g.latest.kind)
    }

    @Test
    fun `re-push with same sourceKey appends event to existing group`() = runTest {
        val center = newCenter()
        center.push("pack:X", "X", null, Severity.Info, Kind.Progress, "Preparing")
        clock.advance(seconds = 2)
        center.push("pack:X", "X", null, Severity.Info, Kind.Progress, "Downloading 47%")
        clock.advance(seconds = 5)
        center.push("pack:X", "X", null, Severity.Success, Kind.OneShot, "Done")

        val g = center.groups.first().single()
        assertEquals(3, g.count)
        assertEquals("Done", g.latest.title)
        assertEquals("Preparing", g.events.last().title, "oldest at tail")
    }

    @Test
    fun `group history is capped at historyPerGroup`() = runTest {
        val center = NotificationCenter(historyPerGroup = 2, clock = clock::now)
        center.push("k", "X", null, Severity.Info, Kind.OneShot, "e1")
        center.push("k", "X", null, Severity.Info, Kind.OneShot, "e2")
        center.push("k", "X", null, Severity.Info, Kind.OneShot, "e3")

        val g = center.groups.first().single()
        assertEquals(2, g.count, "oldest event dropped at the cap")
        assertEquals("e3", g.events[0].title)
        assertEquals("e2", g.events[1].title)
    }

    @Test
    fun `severity is max across events`() = runTest {
        val center = newCenter()
        center.push("k", "X", null, Severity.Info,     Kind.OneShot, "info")
        center.push("k", "X", null, Severity.Critical, Kind.Sticky,  "boom")
        // A later non-critical event must NOT visually downgrade the
        // group -- max wins so the user does not lose track of the
        // earlier critical when scanning the stack.
        center.push("k", "X", null, Severity.Info,     Kind.OneShot, "muted recovery")

        val g = center.groups.first().single()
        assertEquals(Severity.Critical, g.severity)
    }

    @Test
    fun `kind follows the latest event, not the max`() = runTest {
        val center = newCenter()
        center.push("k", "X", null, Severity.Info,    Kind.Progress, "downloading")
        center.push("k", "X", null, Severity.Success, Kind.OneShot,  "done")

        val g = center.groups.first().single()
        assertEquals(Kind.OneShot, g.kind, "lifecycle tracks current state")
    }

    @Test
    fun `touched group floats to the front of the stack`() = runTest {
        val center = newCenter()
        center.push("k1", "First",  null, Severity.Info, Kind.OneShot, "1")
        center.push("k2", "Second", null, Severity.Info, Kind.OneShot, "2")
        center.push("k3", "Third",  null, Severity.Info, Kind.OneShot, "3")
        // Touch k1 -- it should float to the top, k3 / k2 follow.
        center.push("k1", "First",  null, Severity.Info, Kind.OneShot, "1 again")

        val keys = center.groups.first().map { it.sourceKey }
        assertEquals(listOf("k1", "k3", "k2"), keys)
    }

    @Test
    fun `dismiss removes the group and is idempotent on unknown key`() = runTest {
        val center = newCenter()
        center.push("k", "X", null, Severity.Info, Kind.OneShot, "hi")
        center.dismiss("k")
        assertTrue(center.groups.first().isEmpty())

        center.dismiss("k")
        center.dismiss("never-existed")
        assertTrue(center.groups.first().isEmpty())
    }

    @Test
    fun `clear wipes the stack`() = runTest {
        val center = newCenter()
        center.push("a", "A", null, Severity.Info, Kind.OneShot, "1")
        center.push("b", "B", null, Severity.Info, Kind.OneShot, "2")
        center.clear()
        assertTrue(center.groups.first().isEmpty())
    }

    @Test
    fun `kind auto-dismiss policy is consistent with the docs`() {
        // The renderer + driver depend on these values; lock them in.
        assertNull(Kind.Progress.autoDismissAfter(Severity.Info))
        assertNull(Kind.Sticky.autoDismissAfter(Severity.Critical))
        assertNull(Kind.ActionRequired.autoDismissAfter(Severity.Success))
        assertEquals(5,  Kind.OneShot.autoDismissAfter(Severity.Info)?.inWholeSeconds)
        assertEquals(4,  Kind.OneShot.autoDismissAfter(Severity.Success)?.inWholeSeconds)
        assertEquals(30, Kind.OneShot.autoDismissAfter(Severity.Warn)?.inWholeSeconds)
        assertEquals(30, Kind.OneShot.autoDismissAfter(Severity.Critical)?.inWholeSeconds)
    }

    private class FixedClock(start: Instant = Instant.parse("2026-05-26T00:00:00Z")) {
        private var current: Instant = start
        fun now(): Instant = current
        fun advance(seconds: Long) { current = current.plusSeconds(seconds) }
    }
}
