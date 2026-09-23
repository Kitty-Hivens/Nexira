package hivens.core.launch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InstanceWorkTest {

    @Test
    fun `work is visible for exactly as long as it runs`() = runTest {
        val registry = InstanceWorkRegistry()
        val release = CompletableDeferred<Unit>()
        launch { registry.during("a", InstanceWork.Update) { release.await() } }
        advanceUntilIdle()

        assertEquals(InstanceWork.Update, registry.workOn("a"))
        assertNull(registry.workOn("b"), "another instance is not touched")

        release.complete(Unit)
        advanceUntilIdle()
        assertNull(registry.workOn("a"))
    }

    @Test
    fun `work that throws still clears its mark`() = runTest {
        val registry = InstanceWorkRegistry()
        assertFailsWith<IllegalStateException> {
            registry.during("a", InstanceWork.Repair) { error("fetch failed") }
        }
        assertNull(registry.workOn("a"), "a mark left behind would block Play for the rest of the session")
    }

    @Test
    fun `overlapping work on one instance ends in order, not all at once`() = runTest {
        val registry = InstanceWorkRegistry()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        launch { registry.during("a", InstanceWork.Update) { first.await() } }
        launch { registry.during("a", InstanceWork.ContentUpdate) { second.await() } }
        advanceUntilIdle()
        assertEquals(InstanceWork.ContentUpdate, registry.workOn("a"), "the most recent is the one reported")

        second.complete(Unit)
        advanceUntilIdle()
        assertEquals(InstanceWork.Update, registry.workOn("a"), "the first one is still running")

        first.complete(Unit)
        advanceUntilIdle()
        assertNull(registry.workOn("a"))
    }

    @Test
    fun `the block says the reason that will resolve itself first`() {
        assertEquals(
            LaunchBlock.Busy(InstanceWork.Update),
            launchBlockFor(hasIdentity = false, work = InstanceWork.Update, instancePresent = false, otherLaunchActive = true),
        )
        assertEquals(
            LaunchBlock.Missing,
            launchBlockFor(hasIdentity = false, work = null, instancePresent = false, otherLaunchActive = true),
        )
        assertEquals(
            LaunchBlock.OtherGameRunning,
            launchBlockFor(hasIdentity = false, work = null, instancePresent = true, otherLaunchActive = true),
        )
        assertEquals(
            LaunchBlock.NoIdentity,
            launchBlockFor(hasIdentity = false, work = null, instancePresent = true, otherLaunchActive = false),
        )
        assertNull(launchBlockFor(hasIdentity = true, work = null, instancePresent = true, otherLaunchActive = false))
    }
}
