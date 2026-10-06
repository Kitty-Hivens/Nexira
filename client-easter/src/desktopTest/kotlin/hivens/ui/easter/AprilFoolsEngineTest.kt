package hivens.ui.easter

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AprilFoolsEngineTest {

    @AfterTest
    fun tearDown() {
        AprilFoolsEngine.stop()
        AprilFools.debugForceActive = null
    }

    // A launcher left open past the last day: the engine notices on its own and
    // ends, rather than drifting the tilt until the process exits.
    @Test
    fun `the engine ends itself when the window closes`() {
        val scope = TestScope()
        AprilFools.debugForceActive = true
        AprilFoolsEngine.start(scope, { Offset.Zero }, { IntSize(800, 600) })
        scope.advanceTimeBy(5_000)
        assertTrue(AprilFoolsEngine.running)

        AprilFools.debugForceActive = false
        scope.advanceTimeBy(60_000)
        scope.runCurrent()

        assertFalse(AprilFoolsEngine.running, "the engine stopped once the window was over")
        assertEquals(0f, ChaosState.globalTiltDeg)
    }

    @Test
    fun `cleaning up puts an escaped button back in its layout`() {
        val btn = FloatingButton("b", "B", 10f, 10f)
        ChaosState.register(btn)
        btn.originalVisible = false
        btn.phase = ChaosPhase.RESTING

        ChaosState.clean()

        assertTrue(btn.originalVisible)
        assertEquals(ChaosPhase.IDLE, btn.phase)
    }

    @Test
    fun `intensity counts days from the first of April, and a forced run off season is a first day`() {
        assertEquals(1f / 14f, AprilFools.intensityOn(LocalDate.of(2026, 4, 1)))
        assertEquals(1f, AprilFools.intensityOn(LocalDate.of(2026, 4, 14)))
        assertEquals(1f / 14f, AprilFools.intensityOn(LocalDate.of(2026, 10, 20)), "not the twentieth's full strength")
    }

    @Test
    fun `a finished download reads as finished`() {
        AprilFools.debugForceActive = true
        AprilFoolsProgress.reset()
        repeat(5) { AprilFoolsProgress.wrap(it * 10L, 100L) }
        assertEquals(1f, AprilFoolsProgress.wrap(100L, 100L))
    }
}
