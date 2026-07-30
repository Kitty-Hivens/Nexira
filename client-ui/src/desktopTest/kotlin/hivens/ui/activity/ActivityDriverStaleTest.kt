package hivens.ui.activity

import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.core.time.Clock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The update status hub keeps its last value per instance for the life of the
 * process, so "skip the statuses that are not work in flight" is not enough: the
 * entry reported before the settle stays on a permanent surface for the rest of
 * the session. That is what put a pack's name on the pill and left it there.
 * The leave has to be explicit.
 */
class ActivityDriverStaleTest {

    @Test
    fun `a running entry is held by design, so a settling check must drop it`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = Clock { 0L })

        // Exactly the shape the defect had: a check reported as in flight, then a
        // status the driver does not narrate.
        reg.report("update:x", ActivityKind.Update, "Industrial", ActivityPhase.Running(0, 0))
        runCurrent()
        assertEquals(1, reg.activities.value.size, "in-flight work is never evicted by age")

        reg.dismiss("update:x")
        runCurrent()
        assertTrue(reg.activities.value.isEmpty(), "so the driver has to drop it explicitly")
    }

    @Test
    fun `a success is held for reading and then leaves`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = Clock { 0L }, terminalHoldMs = 4_000)

        reg.report("update:x", ActivityKind.Update, "Industrial", ActivityPhase.Succeeded)
        runCurrent()
        assertEquals(1, reg.activities.value.size)

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(reg.activities.value.isEmpty(), "an applied update should not stay on screen")
    }
}
