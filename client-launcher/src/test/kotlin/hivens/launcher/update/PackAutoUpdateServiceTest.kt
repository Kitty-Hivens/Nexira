package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.AmberUpdatePolicy
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.SettingsData
import hivens.core.update.CompatChange
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.core.update.UpdatePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackAutoUpdateServiceTest {

    private class FakeRepo(initial: List<PackInstance>) : IPackRepository {
        private val map = LinkedHashMap<String, PackInstance>().apply { initial.forEach { put(it.id, it) } }
        private val flow = MutableStateFlow(map.values.toList())
        override fun observe(): Flow<List<PackInstance>> = flow.asStateFlow()
        override suspend fun list() = map.values.toList()
        override suspend fun get(id: String) = map[id]
        override suspend fun put(instance: PackInstance) { map[instance.id] = instance; flow.value = map.values.toList() }
        override suspend fun delete(id: String) { map.remove(id); flow.value = map.values.toList() }
    }

    /** Returns a scripted check per instance id and records which ids were applied. */
    private class FakeUpdater(private val checks: Map<String, UpdateCheck>) : PackUpdater {
        val applied = mutableListOf<String>()
        override suspend fun checkForUpdate(instance: PackInstance): UpdateCheck =
            checks[instance.id] ?: UpdateCheck.UpToDate
        override suspend fun applyUpdate(
            instance: PackInstance,
            targetVersion: String?,
            progress: ((Int, Int, String) -> Unit)?,
        ): UpdateOutcome {
            applied += instance.id
            return UpdateOutcome.Applied("2026.02.02", CompatChange.Same, UpdatePlan())
        }
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

    private fun available(compat: CompatChange) =
        UpdateCheck.Available("2026.01.01", "2026.02.02", compat, UpdatePlan(toUpdate = listOf("mods/x.jar")))

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
    fun `pinned and non-mirror instances are skipped`() = runTest {
        val repo = FakeRepo(listOf(instance("pinned", followLatest = false), instance("mr", origin = PackOrigin.Modrinth)))
        val updater = FakeUpdater(
            mapOf("pinned" to available(CompatChange.Same), "mr" to available(CompatChange.Same)),
        )
        val service = PackAutoUpdateService(repo, updater) { settings() }

        service.runOnce()

        assertTrue(updater.applied.isEmpty())
        assertTrue(service.statuses.value.isEmpty())
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
