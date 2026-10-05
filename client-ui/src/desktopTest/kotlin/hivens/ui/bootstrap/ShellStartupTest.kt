package hivens.ui.bootstrap

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The part of bring-up whose order decides whether an instance survives it:
 * recovering an update a crash interrupted, and the auto-update pass.
 *
 * Both used to start as two coroutines with nothing ordering them. They meet on
 * one instance lock, which makes them exclusive and not ordered, so when the pass
 * got there first the recovery afterwards restored an older snapshot over the
 * update that had just landed.
 */
class ShellStartupTest {

    private fun TestScope.startup(
        autoUpdate: Boolean,
        recover: suspend () -> Unit,
        update: suspend () -> Unit,
    ) = ShellStartup(
        policy = StartupPolicy(trayEnabled = false, notifierEnabled = false, autoUpdatePacks = autoUpdate),
        bringUpTray = {},
        bringUpNotifier = {},
        readIcon = { ByteArray(0) },
        trayIsSupported = { true },
        showWindow = {},
        recoverInterrupted = recover,
        autoUpdatePacks = update,
        appScope = this,
    )

    @Test
    fun `the auto-update pass waits for recovery to finish`() = runTest {
        val events = mutableListOf<String>()
        val recoveryMayFinish = CompletableDeferred<Unit>()

        startup(
            autoUpdate = true,
            recover = { events += "recovery started"; recoveryMayFinish.await(); events += "recovery finished" },
            update = { events += "update started" },
        ).run(windowVisible = { true })
        advanceUntilIdle()

        assertEquals(listOf("recovery started"), events, "nothing may update while recovery is still at work")

        recoveryMayFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("recovery started", "recovery finished", "update started"), events)
    }

    @Test
    fun `recovery that fails holds the auto-update pass for this session`() = runTest {
        var updated = false

        startup(
            autoUpdate = true,
            recover = { throw IllegalStateException("snapshot store unreadable") },
            update = { updated = true },
        ).run(windowVisible = { true })
        advanceUntilIdle()

        assertTrue(!updated, "an update on top of an instance recovery could not vouch for is a state nobody can undo")
    }

    @Test
    fun `recovery runs even with auto-update off`() = runTest {
        var recovered = false
        var updated = false

        startup(
            autoUpdate = false,
            recover = { recovered = true },
            update = { updated = true },
        ).run(windowVisible = { true })
        advanceUntilIdle()

        assertTrue(recovered)
        assertTrue(!updated)
    }
}
