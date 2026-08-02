package hivens.ui.notifications.drivers

import hivens.core.launch.AuthRefreshFailure
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchLogEvent
import hivens.core.launch.LaunchState
import hivens.core.launch.PrepareStage
import hivens.ui.i18n.EnglishStrings
import hivens.ui.notifications.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Driver pulls in LauncherController (heavy DI graph: auth, credentials,
// java manager, ...) so a full integration test is out of scope here.
// These tests pin the FLOW CONTRACT the driver depends on -- if the
// dropWhile / transformWhile pipeline breaks, both the driver and its
// review-flagged stale-terminal / leak bugs come back.
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchDriverTest {

    @Test
    fun `dropWhile gate ignores stale terminal until Prepare arrives`() = runTest {
        val flow = MutableStateFlow<LaunchState>(LaunchState.Error(LaunchError.Internal("stale")))
        val emitted = mutableListOf<LaunchState>()
        val job = launch {
            flow.dropWhile { it !is LaunchState.Prepare }.collect { emitted += it }
        }
        advanceUntilIdle()
        assertTrue(emitted.isEmpty(), "stale Error must not pass the Prepare gate")

        flow.value = LaunchState.Prepare(PrepareStage.INIT, 0.0f)
        advanceUntilIdle()
        assertEquals(1, emitted.size)
        assertIs<LaunchState.Prepare>(emitted.last())

        flow.value = LaunchState.Idle
        advanceUntilIdle()
        // After the gate opens, subsequent values (including Idle) come through.
        assertEquals(2, emitted.size)
        job.cancel()
    }

    @Test
    fun `transformWhile terminates on first terminal state`() = runTest {
        // The driver's `transformWhile { emit(it); !is Idle && !is Error }`
        // is what replaces a forbidden non-local return from `collect { }`.
        // Verify it actually stops on the first terminal value.
        val flow = MutableStateFlow<LaunchState>(LaunchState.Prepare(PrepareStage.INIT, 0f))
        val emitted = mutableListOf<LaunchState>()
        val job = launch {
            flow
                .dropWhile { it !is LaunchState.Prepare }
                .transformWhile { state ->
                    emit(state)
                    state !is LaunchState.Idle && state !is LaunchState.Error
                }
                .collect { emitted += it }
        }
        advanceUntilIdle()
        assertEquals(1, emitted.size, "initial Prepare reaches the collector")
        assertIs<LaunchState.Prepare>(emitted[0])

        flow.value = LaunchState.Idle
        advanceUntilIdle()
        assertEquals(2, emitted.size, "Idle emitted, transformWhile stops after")
        assertEquals(LaunchState.Idle, emitted[1])

        flow.value = LaunchState.Prepare(PrepareStage.INIT, 1f)
        advanceUntilIdle()
        assertEquals(2, emitted.size, "later values dropped after terminal")
        job.cancel()
    }

    @Test
    fun `a rejected refresh warns critically, an unreachable one only warns`() {
        val s = EnglishStrings

        val rejected = staleSessionWarning(
            LaunchLogEvent.AuthFailed("Invalid password", AuthRefreshFailure.Rejected), s,
        )
        assertEquals(Severity.Critical, rejected?.first, "the server already refused; the join will be refused too")
        assertEquals(s.notifSessionStaleRejected, rejected?.second)

        val unreachable = staleSessionWarning(
            LaunchLogEvent.AuthFailed("connection reset", AuthRefreshFailure.Unreachable), s,
        )
        assertEquals(Severity.Warn, unreachable?.first, "nothing judged the token, so it may still be good")

        val unknown = staleSessionWarning(
            LaunchLogEvent.AuthFailed("boom", AuthRefreshFailure.Unknown), s,
        )
        assertEquals(Severity.Warn, unknown?.first)

        // Distinct bodies: the three cases ask the user for different things.
        val bodies = setOf(rejected?.second, unreachable?.second, unknown?.second)
        assertEquals(3, bodies.size, "each cause needs its own advice; got $bodies")
    }

    @Test
    fun `a missing password warns critically and other events say nothing`() {
        val s = EnglishStrings

        val noPassword = staleSessionWarning(LaunchLogEvent.NoPassword, s)
        assertEquals(Severity.Critical, noPassword?.first, "no password means the session is never refreshed at all")
        assertEquals(s.notifSessionStaleNoPassword, noPassword?.second)

        // Everything else on the launch channel is unrelated to the session and
        // must not raise a warning about it.
        assertNull(staleSessionWarning(LaunchLogEvent.Launching, s))
        assertNull(staleSessionWarning(LaunchLogEvent.AuthSucceeded("uuid"), s))
        assertNull(staleSessionWarning(LaunchLogEvent.OfflineSkipAuth, s))
    }

    @Test
    fun `severity ladder gives Critical highest precedence`() {
        // Driver picks Severity.Critical on Error -- group.severity then
        // wins as max across history events, surfacing the failure even
        // after subsequent Info/Success arrivals.
        assertTrue(Severity.Critical.ordinal > Severity.Warn.ordinal)
        assertTrue(Severity.Critical.ordinal > Severity.Success.ordinal)
        assertTrue(Severity.Critical.ordinal > Severity.Info.ordinal)
    }
}
