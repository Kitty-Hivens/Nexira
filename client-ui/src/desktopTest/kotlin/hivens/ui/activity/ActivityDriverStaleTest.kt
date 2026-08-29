package hivens.ui.activity

import hivens.core.activity.ActivityPhase
import hivens.core.activity.ActivityRegistry
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.time.Clock
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.launcher.AutoSyncService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the DRIVER, which the first version of this file did not: it built a
 * registry, called `dismiss` on it by hand and asserted the entry left. That
 * proved the registry works and said nothing about the code under test -- removing
 * the driver's own `dismiss` would have left it green, which is exactly the defect
 * it was written for.
 *
 * Every source here keeps settled entries and re-emits its whole map on any
 * change, so the two things that matter are: an entry leaves when its work leaves
 * flight, and a re-emission carrying nothing new does not resurrect what the user
 * dismissed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDriverStaleTest {

    private class FakeHub : PackUpdateStatusHub {
        val state = MutableStateFlow<Map<String, PackUpdateStatus>>(emptyMap())
        override val statuses: StateFlow<Map<String, PackUpdateStatus>> = state
        override fun report(id: String, status: PackUpdateStatus) {
            state.value = state.value + (id to status)
        }
    }

    private class FakeRepo : IPackRepository {
        override fun observe() = MutableStateFlow(emptyList<PackInstance>())
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private fun keys(reg: ActivityRegistry) = reg.activities.value.map { it.key }

    @Test
    fun `an update that settles takes its entry with it`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = Clock { 0L }, terminalHoldMs = 60_000)
        val hub = FakeHub()
        driver(reg, hub).start()

        hub.report("x", PackUpdateStatus.Updated("6"))
        runCurrent()
        assertEquals(listOf("update:x"), keys(reg), "an applied update is worth narrating")

        // The hub keeps its key forever; the next pass simply finds nothing to do.
        hub.report("x", PackUpdateStatus.UpToDate)
        runCurrent()
        assertTrue(keys(reg).isEmpty(), "a settled check must take its entry off the surface")
    }

    @Test
    fun `a dismissed failure is not resurrected by an unrelated emission`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = Clock { 0L }, terminalHoldMs = 60_000)
        val hub = FakeHub()
        driver(reg, hub).start()

        hub.report("bad", PackUpdateStatus.Failed("boom"))
        runCurrent()
        assertEquals(listOf("update:bad"), keys(reg))

        // The user takes it off the surface. The hub still holds the failure.
        reg.dismiss("update:bad")
        runCurrent()
        assertTrue(keys(reg).isEmpty())

        // Something else moves, so the whole map is re-emitted -- including "bad",
        // whose status has not changed. It must not come back.
        hub.report("other", PackUpdateStatus.Failed("unrelated"))
        runCurrent()
        assertEquals(listOf("update:other"), keys(reg), "only the new failure belongs on the surface")
    }

    @Test
    fun `a repeated identical status is not reported twice`() = runTest {
        val reg = ActivityRegistry(scope = this, clock = Clock { 0L }, terminalHoldMs = 4_000)
        val hub = FakeHub()
        driver(reg, hub).start()

        hub.report("x", PackUpdateStatus.Updated("6"))
        runCurrent()

        // Re-emitting the same status must not restart the entry's hold, or a
        // success sitting beside a busy job would never age out.
        repeat(5) {
            hub.report("noise$it", PackUpdateStatus.UpToDate)
            runCurrent()
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(
            reg.activities.value.none { it.key == "update:x" },
            "the hold should have run out rather than being restarted by the noise",
        )
    }

    private fun TestScope.driver(reg: ActivityRegistry, hub: FakeHub) = ActivityDriver(
        registry = reg,
        installs = MutableStateFlow(emptyMap()),
        updates = hub.statuses,
        sync = MutableStateFlow(AutoSyncService.Snapshot(emptyMap(), AutoSyncService.OverallState.Idle)),
        repository = FakeRepo(),
        appScope = backgroundScope,
    )
}
