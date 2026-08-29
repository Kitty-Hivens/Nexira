package hivens.ui.notifications.drivers

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.update.CompatChange
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.UpdateDirection
import hivens.ui.i18n.EnglishStrings
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The amber policy reaches the user through this driver: Ask and Hold produce the
 * same [PackUpdateStatus.Pending], and only the driver decides whether it interrupts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackUpdateDriverTest {

    private class FakeHub : PackUpdateStatusHub {
        private val state = MutableStateFlow<Map<String, PackUpdateStatus>>(emptyMap())
        override val statuses: StateFlow<Map<String, PackUpdateStatus>> = state.asStateFlow()
        override fun report(id: String, status: PackUpdateStatus) { state.value = state.value + (id to status) }
    }

    private class FakeRepo(private val instance: PackInstance) : IPackRepository {
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(listOf(instance)).asStateFlow()
        override suspend fun list() = listOf(instance)
        override suspend fun get(id: String) = instance.takeIf { it.id == id }
        override suspend fun put(instance: PackInstance) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private val instance = PackInstance(
        id = "industrial",
        packRef = PackReference(PackOrigin.Mirror, "industrial", "2026.01.01"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
        pinnedPackVersion = "2026.01.01",
        followLatest = true,
    )

    private fun pending(held: Boolean) =
        PackUpdateStatus.Pending("2026.02.02", UpdateDirection.Newer, CompatChange.McBump, held = held)

    /**
     * Runs the driver on a scope the scheduler actually drives. `backgroundScope` is
     * not it -- its collector never advances here, so every assertion would pass
     * vacuously against a driver that did nothing at all.
     */
    private fun TestScope.startDriver(hub: FakeHub, center: NotificationCenter): CoroutineScope {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        PackUpdateDriver(hub, FakeRepo(instance), center, NavRequests(), scope) { EnglishStrings }.start()
        advanceUntilIdle()
        return scope
    }

    @Test
    fun `a pending build under Ask asks for action`() = runTest {
        val hub = FakeHub()
        val center = NotificationCenter()
        val scope = startDriver(hub, center)

        hub.report(instance.id, pending(held = false))
        advanceUntilIdle()

        val group = center.groups.value.single()
        assertEquals("Industrial", group.sender)
        assertEquals(Kind.ActionRequired, group.latest.kind)
        assertTrue(group.latest.actions.isNotEmpty(), "the toast offers a way to the versions screen")
        scope.cancel()
    }

    @Test
    fun `a pending build under Hold stays quiet`() = runTest {
        val hub = FakeHub()
        val center = NotificationCenter()
        val scope = startDriver(hub, center)

        hub.report(instance.id, pending(held = true))
        advanceUntilIdle()

        assertTrue(center.groups.value.isEmpty(), "a held build must not interrupt")
        scope.cancel()
    }

    @Test
    fun `switching off Hold announces the build that was held`() = runTest {
        val hub = FakeHub()
        val center = NotificationCenter()
        val scope = startDriver(hub, center)

        hub.report(instance.id, pending(held = true))
        advanceUntilIdle()
        assertTrue(center.groups.value.isEmpty())

        // The same build re-derived under Ask: suppressing it once must not consume
        // the announcement, or a policy change would go unnoticed until a new build.
        hub.report(instance.id, pending(held = false))
        advanceUntilIdle()
        assertEquals(1, center.groups.value.size)
        scope.cancel()
    }

    @Test
    fun `the same pending build is announced once`() = runTest {
        val hub = FakeHub()
        val center = NotificationCenter()
        val scope = startDriver(hub, center)

        hub.report(instance.id, pending(held = false))
        advanceUntilIdle()
        hub.report(instance.id, PackUpdateStatus.Checking)
        advanceUntilIdle()
        hub.report(instance.id, pending(held = false))
        advanceUntilIdle()

        assertEquals(1, center.groups.value.single().count, "one announcement per version")
        scope.cancel()
    }
}
