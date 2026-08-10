package hivens.core.launch

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one reading of launch state every surface shares. Parallel copies of this
 * `when` are what let the launch tile miss the running case entirely, so the
 * mapping is pinned here rather than trusted to each call site.
 */
class LaunchControlModeTest {

    private val handle = object : LaunchHandle {
        override suspend fun awaitExit() = 0
        override fun terminate() = Unit
        override val stdin = java.io.OutputStream.nullOutputStream()
    }

    @Test
    fun `idle offers a launch`() {
        assertEquals(LaunchControlMode.Play, LaunchState.Idle.controlMode())
    }

    @Test
    fun `a failed attempt offers the next one`() {
        assertEquals(LaunchControlMode.Play, LaunchState.Error(LaunchError.ExitCode(1)).controlMode())
    }

    @Test
    fun `preparing and downloading are the same wait`() {
        assertEquals(LaunchControlMode.Wait, LaunchState.Prepare(PrepareStage.SYNC, 0.4f).controlMode())
        assertEquals(
            LaunchControlMode.Wait,
            LaunchState.Downloading(1, 10, 1024, 4096, 512).controlMode(),
        )
    }

    @Test
    fun `a running game offers a stop`() {
        assertEquals(LaunchControlMode.Stop, LaunchState.GameRunning(handle).controlMode())
    }
}
