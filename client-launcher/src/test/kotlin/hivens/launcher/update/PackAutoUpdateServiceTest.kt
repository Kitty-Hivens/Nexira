package hivens.launcher.update

import hivens.core.update.PackBuild
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.AmberUpdatePolicy
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.SettingsData
import hivens.core.update.CompatChange
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateDirection
import hivens.core.update.UpdateOutcome
import hivens.core.update.UpdatePlan
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackAutoUpdateServiceTest {

    private class FakeRepo(initial: List<PackInstance>) : IPackRepository {
        private val map = LinkedHashMap<String, PackInstance>().apply { initial.forEach { put(it.id, it) } }
        private val flow = MutableStateFlow(map.values.toList())
        override fun observe(): StateFlow<List<PackInstance>> = flow.asStateFlow()
        override suspend fun list() = map.values.toList()
        override suspend fun get(id: String) = map[id]
        override suspend fun put(instance: PackInstance) { map[instance.id] = instance; flow.value = map.values.toList() }
        override suspend fun delete(id: String) { map.remove(id); flow.value = map.values.toList() }
    }

    /** Returns a scripted check per instance id and records which ids were applied. */
    private class FakeUpdater(
        private val checks: Map<String, UpdateCheck>,
        /** Instance ids no source can offer other builds for. */
        private val unhandled: Set<String> = emptySet(),
    ) : PackUpdater {
        val applied = mutableListOf<String>()
        override fun handles(instance: PackInstance): Boolean = instance.id !in unhandled
        override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck =
            checks[instance.id] ?: UpdateCheck.UpToDate
        override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck =
            checks[instance.id] ?: UpdateCheck.UpToDate
        override suspend fun applyUpdate(
            instance: PackInstance,
            targetVersion: String?,
            progress: ((Int, Int, String) -> Unit)?,
        ): UpdateOutcome {
            applied += instance.id
            return UpdateOutcome.Applied("2026.02.02", CompatChange.Same, UpdatePlan())
        }
        override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> = emptyList()
        override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> = flowOf(emptyList())
        override fun listSnapshots(instance: PackInstance): List<PackSnapshot> = emptyList()
        override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance = instance
    }

    private fun instance(id: String, origin: PackOrigin = PackOrigin.Mirror, followLatest: Boolean = true) =
        PackInstance(
            id = id,
            packRef = PackReference(origin, id, "2026.01.01"),
            displayName = id,
            instanceDirName = id,
            createdAtEpoch = 0L,
            pinnedPackVersion = "2026.01.01",
            followLatest = followLatest,
        )

    private fun settings(auto: Boolean = true, amber: AmberUpdatePolicy = AmberUpdatePolicy.Ask) =
        SettingsData(autoUpdatePacks = auto, amberUpdatePolicy = amber)

    private fun available(compat: CompatChange, direction: UpdateDirection = UpdateDirection.Newer) =
        UpdateCheck.Available("2026.01.01", "2026.02.02", direction, compat, UpdatePlan(toUpdate = listOf("mods/x.jar")))

    @Test
    fun `green auto-applies while amber under Ask is held`() = runTest {
        val repo = FakeRepo(listOf(instance("green"), instance("amber")))
        val updater = FakeUpdater(
            mapOf("green" to available(CompatChange.Same), "amber" to available(CompatChange.McBump)),
        )
        val service = PackAutoUpdateService(repo, updater) { settings(amber = AmberUpdatePolicy.Ask) }

        service.runOnce()

        assertEquals(listOf("green"), updater.applied)
        assertTrue(service.statuses.value["green"] is PackUpdateStatus.Updated)
        assertTrue(service.statuses.value["amber"] is PackUpdateStatus.Pending)
    }

    // Ask and Hold both leave the build pending; only the flag on the status tells
    // the ambient surfaces which one asked for attention.
    @Test
    fun `pending under Ask asks, pending under Hold does not`() = runTest {
        suspend fun pendingUnder(amber: AmberUpdatePolicy): PackUpdateStatus.Pending {
            val repo = FakeRepo(listOf(instance("amber")))
            val updater = FakeUpdater(mapOf("amber" to available(CompatChange.McBump)))
            val service = PackAutoUpdateService(repo, updater) { settings(amber = amber) }
            service.runOnce()
            assertTrue(updater.applied.isEmpty(), "$amber must not apply an amber change")
            return service.statuses.value["amber"] as PackUpdateStatus.Pending
        }

        assertEquals(false, pendingUnder(AmberUpdatePolicy.Ask).held)
        assertEquals(true, pendingUnder(AmberUpdatePolicy.Hold).held)
    }

    @Test
    fun `amber applies under snapshot-then-apply`() = runTest {
        val repo = FakeRepo(listOf(instance("amber")))
        val updater = FakeUpdater(mapOf("amber" to available(CompatChange.LoaderSwap)))
        val service = PackAutoUpdateService(repo, updater) { settings(amber = AmberUpdatePolicy.SnapshotThenApply) }

        service.runOnce()

        assertEquals(listOf("amber"), updater.applied)
        assertTrue(service.statuses.value["amber"] is PackUpdateStatus.Updated)
    }

    @Test
    fun `a pinned instance is skipped whatever its source offers`() = runTest {
        val repo = FakeRepo(listOf(instance("pinned", followLatest = false)))
        val updater = FakeUpdater(mapOf("pinned" to available(CompatChange.Same)))
        val service = PackAutoUpdateService(repo, updater) { settings() }

        service.runOnce()

        assertTrue(updater.applied.isEmpty())
        assertTrue(service.statuses.value.isEmpty())
    }

    @Test
    fun `an instance nothing can offer builds for is skipped`() = runTest {
        // A locally created pack, or one synced from a game server: there is no
        // version feed to ask. The pass asks the updater whether it handles the
        // instance rather than testing where the pack came from, so a source that
        // learns to update is not silently left out of this loop.
        val repo = FakeRepo(listOf(instance("local", origin = PackOrigin.Local)))
        val updater = FakeUpdater(mapOf("local" to available(CompatChange.Same)), unhandled = setOf("local"))
        val service = PackAutoUpdateService(repo, updater) { settings() }

        service.runOnce()

        assertTrue(updater.applied.isEmpty())
        assertTrue(service.statuses.value.isEmpty())
    }

    @Test
    fun `an instance from another source is updated once something handles it`() = runTest {
        // The behaviour this replaced: the pass tested for the mirror by name, so
        // a Modrinth instance was never even checked no matter what could update it.
        val repo = FakeRepo(listOf(instance("mr", origin = PackOrigin.Modrinth)))
        val updater = FakeUpdater(mapOf("mr" to available(CompatChange.Same)))
        val service = PackAutoUpdateService(repo, updater) { settings() }

        service.runOnce()

        assertEquals(listOf("mr"), updater.applied)
    }

    @Test
    fun `disabled setting is a no-op`() = runTest {
        val repo = FakeRepo(listOf(instance("green")))
        val updater = FakeUpdater(mapOf("green" to available(CompatChange.Same)))
        val service = PackAutoUpdateService(repo, updater) { settings(auto = false) }

        service.runOnce()

        assertTrue(updater.applied.isEmpty())
    }
}
