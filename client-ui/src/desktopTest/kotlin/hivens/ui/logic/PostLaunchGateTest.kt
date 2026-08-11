package hivens.ui.logic

import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchHandle
import hivens.core.launch.LaunchState
import hivens.core.launch.PrepareStage
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PostLaunchGateTest {

    private fun handle() = object : LaunchHandle {
        override suspend fun awaitExit(): Int = 0
        override fun terminate() {}
        override val stdin: OutputStream = OutputStream.nullOutputStream()
    }

    private val preparing = LaunchState.Prepare(PrepareStage.INIT, 0f)
    private val failed = LaunchState.Error(LaunchError.ExitCode(1))

    private fun PostLaunchGate.feed(
        state: LaunchState,
        hideAfterStart: Boolean = true,
        trayReady: Boolean = true,
        windowMinimized: Boolean = false,
    ) = onState(state, hideAfterStart, trayReady, windowMinimized)

    @Test
    fun `a started game hides the window to the tray`() {
        val gate = PostLaunchGate()
        gate.feed(preparing)
        assertEquals(PostLaunchMove.HideToTray, gate.feed(LaunchState.GameRunning(handle())))
    }

    @Test
    fun `without a tray the window is iconified instead`() {
        // Nothing to hide into: a hidden window with no tray icon has no way back
        // short of the second-instance .show signal.
        val gate = PostLaunchGate()
        assertEquals(
            PostLaunchMove.Minimize,
            gate.feed(LaunchState.GameRunning(handle()), trayReady = false),
        )
    }

    @Test
    fun `the setting off leaves the window alone`() {
        val gate = PostLaunchGate()
        assertEquals(
            PostLaunchMove.Stay,
            gate.feed(LaunchState.GameRunning(handle()), hideAfterStart = false),
        )
    }

    @Test
    fun `the move happens once per session`() {
        // The same session re-observed -- a recomposition, a conflated flow -- is
        // not a second launch, so a user who brought the window back mid-game
        // keeps it.
        val gate = PostLaunchGate()
        val running = LaunchState.GameRunning(handle())
        assertEquals(PostLaunchMove.HideToTray, gate.feed(running))
        assertEquals(PostLaunchMove.Stay, gate.feed(running))
    }

    @Test
    fun `a second session hides again`() {
        val gate = PostLaunchGate()
        gate.feed(LaunchState.GameRunning(handle()))
        gate.feed(LaunchState.Idle)
        gate.feed(preparing)
        assertEquals(PostLaunchMove.HideToTray, gate.feed(LaunchState.GameRunning(handle())))
    }

    @Test
    fun `a second session hides even with no state observed between the two`() {
        // StateFlow collection is conflated: an exit and the next launch's prepare
        // can both be missed. Sessions are told apart by their handle, so the
        // second one is still a launch.
        val gate = PostLaunchGate()
        gate.feed(LaunchState.GameRunning(handle()))
        assertEquals(PostLaunchMove.HideToTray, gate.feed(LaunchState.GameRunning(handle())))
    }

    @Test
    fun `a game already running at mount is not touched`() {
        // Shell restart after a UI crash: the effect re-runs against the state it
        // finds, and that arrival is not a launch.
        val running = LaunchState.GameRunning(handle())
        val gate = PostLaunchGate(runningAtMount = running.handle)
        assertEquals(PostLaunchMove.Stay, gate.feed(running))
    }

    @Test
    fun `the session after a restart hides normally`() {
        val running = LaunchState.GameRunning(handle())
        val gate = PostLaunchGate(runningAtMount = running.handle)
        gate.feed(running)
        gate.feed(LaunchState.Idle)
        assertEquals(PostLaunchMove.HideToTray, gate.feed(LaunchState.GameRunning(handle())))
    }

    @Test
    fun `a failed launch raises the window this gate iconified`() {
        val gate = PostLaunchGate()
        assertEquals(
            PostLaunchMove.Minimize,
            gate.feed(LaunchState.GameRunning(handle()), trayReady = false),
        )
        assertEquals(PostLaunchMove.Restore, gate.feed(failed))
    }

    @Test
    fun `the raise is spent once`() {
        val gate = PostLaunchGate()
        gate.feed(LaunchState.GameRunning(handle()), trayReady = false)
        gate.feed(failed)
        assertEquals(PostLaunchMove.Stay, gate.feed(failed))
    }

    @Test
    fun `a window the user minimized during the launch is not raised`() {
        // The move is a no-op on a window that is already down, so it buys no
        // claim on it: the user put it there and a later failure leaves it there.
        val gate = PostLaunchGate()
        gate.feed(LaunchState.GameRunning(handle()), trayReady = false, windowMinimized = true)
        assertEquals(PostLaunchMove.Stay, gate.feed(failed))
    }

    @Test
    fun `a failure raises nothing when the window was hidden to the tray`() {
        // The shell's own error path un-hides it; nothing was iconified.
        val gate = PostLaunchGate()
        gate.feed(LaunchState.GameRunning(handle()))
        assertEquals(PostLaunchMove.Stay, gate.feed(failed))
    }

    @Test
    fun `a clean exit ends the claim on the iconified window`() {
        // The window stays down -- the session ended with nothing on screen
        // waiting to be read -- but a failure two launches later is no longer
        // this gate's excuse to raise whatever the user has minimized by then.
        val gate = PostLaunchGate()
        gate.feed(LaunchState.GameRunning(handle()), trayReady = false)
        assertEquals(PostLaunchMove.Stay, gate.feed(LaunchState.Idle))
        assertEquals(PostLaunchMove.Stay, gate.feed(failed))
    }

    @Test
    fun `download progress does not disturb the session`() {
        // Downloading re-keys the shell effect on every tick; the gate must read
        // those as nothing at all.
        val gate = PostLaunchGate()
        val running = LaunchState.GameRunning(handle())
        gate.feed(running)
        assertEquals(
            PostLaunchMove.Stay,
            gate.feed(LaunchState.Downloading(1, 10, 100, 1000, 50)),
        )
        assertEquals(PostLaunchMove.Stay, gate.feed(running))
    }
}
