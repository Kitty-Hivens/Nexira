package hivens.launcher.instance

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.io.InstanceMutationLock
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.update.CompatChange
import hivens.launcher.catalogue.PackArt
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackReattacher
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import hivens.launcher.update.PendingApply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PackInstanceServiceTest {

    private class FakeRepo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = map.values.toList()
        override suspend fun get(id: String): PackInstance? = map[id]
        override suspend fun put(instance: PackInstance) {
            map[instance.id] = instance
            flow.value = map.values.toList()
        }
        override suspend fun delete(id: String) {
            map.remove(id)
            flow.value = map.values.toList()
        }
    }

    private val runningId = MutableStateFlow<String?>(null)
    private val running = object : RunningPackSource {
        override val runningPackInstanceId: StateFlow<String?> = runningId
    }
    private val work = InstanceWorkRegistry()

    private fun service(
        repo: IPackRepository,
        data: java.nio.file.Path,
        knownArt: (PackInstance) -> PackArt? = { null },
        reattachers: Map<PackOrigin, PackReattacher> = emptyMap(),
    ) = PackInstanceService(
        repo, data, running, work,
        snapshots = PackSnapshotService(data, json),
        journal = ApplyJournal(data, json),
        sizes = InstanceSizeService(data, CoroutineScope(Dispatchers.Unconfined)),
        knownArt = knownArt,
        reattachers = reattachers,
    )

    /** A source that records what it was asked and answers with the record put back on it. */
    private class RecordingReattacher : PackReattacher {
        val asked = mutableListOf<Pair<String, PackReference>>()
        override suspend fun previewReattach(instance: PackInstance, source: PackReference): ReattachPreview {
            asked += "preview:${instance.id}" to source
            return ReattachPreview("v9", "9", CompatChange.Same, removed = listOf("mods/mine.jar"), restored = emptyList(), replaced = emptyList())
        }
        override suspend fun reattach(
            instance: PackInstance,
            source: PackReference,
            confirmed: ReattachConfirmation,
            progress: ((current: Int, total: Int, path: String) -> Unit)?,
        ): PackInstance {
            asked += "reattach:${instance.id}:${confirmed.build}" to source
            return instance.copy(packRef = source, forkedFrom = null)
        }
        override suspend fun sourceName(source: PackReference): String? = "Source of ${source.id}"
    }
    private val json = Json { ignoreUnknownKeys = true }

    private fun instance(id: String, dir: String = id, origin: PackOrigin = PackOrigin.Mirror) = PackInstance(
        id = id,
        packRef = PackReference(origin, "pack", "2026.01.01"),
        displayName = id,
        instanceDirName = dir,
        createdAtEpoch = 0L,
    )

    @Test
    fun `deleteCompletely removes the files and the registry entry`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = data.resolve("instances").resolve("industrial")
        Files.createDirectories(instDir.resolve("mods"))
        Files.writeString(instDir.resolve("mods").resolve("a.jar"), "x")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)

        val outcome = service(repo, data).deleteCompletely(pack)

        assertEquals(DeleteOutcome.Deleted, outcome)
        assertFalse(Files.exists(instDir), "instance dir removed")
        assertNull(repo.get("1"), "registry entry dropped")
    }

    @Test
    fun `deleteCompletely on a missing dir still drops the entry`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", dir = "gone")
        repo.put(pack)

        assertEquals(DeleteOutcome.Deleted, service(repo, data).deleteCompletely(pack))
        assertNull(repo.get("1"))
    }

    @Test
    fun `detachToLocal flips origin to Local and records provenance`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", origin = PackOrigin.Mirror)
        repo.put(pack)

        val detached = service(repo, data).detachToLocal(pack)

        assertEquals(PackOrigin.Local, detached.packRef.origin)
        assertEquals(pack.packRef, detached.forkedFrom)
        assertEquals(PackOrigin.Local, repo.get("1")?.packRef?.origin, "persisted as Local")
    }

    /**
     * The detached pack used to keep only the art its install captured, which for a
     * pack installed before covers were captured was none: its card fell back to the
     * pixel mosaic while the mirror still published a banner for it.
     */
    @Test
    fun `a detach writes the cover already known into the record`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1").copy(iconUrl = "https://cdn/captured-icon.png")
        repo.put(pack)
        val known = PackArt(iconUrl = null, bannerUrl = "https://cdn/banner.png")

        val detached = service(repo, data, knownArt = { if (it.packRef.origin == PackOrigin.Mirror) known else null }).detachToLocal(pack)

        assertEquals("https://cdn/banner.png", detached.bannerUrl, "the source's banner, kept for good")
        assertEquals("https://cdn/captured-icon.png", detached.iconUrl, "a field nothing new is known for keeps what it had")
        assertEquals(detached, repo.get("1"))
    }

    @Test
    fun `a pack that was never detached offers no reattach`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val source = RecordingReattacher()
        val reattachers = mapOf<PackOrigin, PackReattacher>(PackOrigin.Mirror to source, PackOrigin.Modrinth to source)
        val local = instance("1", origin = PackOrigin.Local)
        val fromLocal = instance("2", origin = PackOrigin.Local).copy(forkedFrom = PackReference(PackOrigin.Local, "base"))
        val mirror = instance("3", origin = PackOrigin.Mirror)
        listOf(local, fromLocal, mirror).forEach { repo.put(it) }
        val service = service(repo, data, reattachers = reattachers)

        listOf(local, fromLocal, mirror).forEach { pack ->
            assertFalse(service.canReattach(pack), "${pack.id} offers a reattach")
            assertFailsWith<IllegalStateException> { service.previewReattach(pack) }
            assertFailsWith<IllegalStateException> { service.reattach(pack, ReattachConfirmation("v9", "any")) }
            assertNull(service.reattachSourceName(pack))
        }
        assertTrue(source.asked.isEmpty(), "no source was asked about a pack it never had")
    }

    @Test
    fun `a reattach goes to the source the pack was detached from`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val mirror = RecordingReattacher()
        val modrinth = RecordingReattacher()
        val service = service(repo, data, reattachers = mapOf(PackOrigin.Mirror to mirror, PackOrigin.Modrinth to modrinth))
        val fromModrinth = instance("1", origin = PackOrigin.Modrinth)
        repo.put(fromModrinth)
        val detached = service.detachToLocal(fromModrinth)

        assertTrue(service.canReattach(detached))
        val preview = service.previewReattach(detached)
        assertEquals(listOf("mods/mine.jar"), preview.removed)
        assertEquals("Source of pack", service.reattachSourceName(detached))
        val back = service.reattach(detached, preview.confirmation)

        assertEquals(fromModrinth.packRef, back.packRef)
        assertEquals(
            listOf("preview:1" to fromModrinth.packRef, "reattach:1:v9" to fromModrinth.packRef),
            modrinth.asked,
        )
        assertTrue(mirror.asked.isEmpty())
    }

    /**
     * A detach that landed while an update ran had the update's commit put the source
     * back, or its rollback re-bind the pack, over the player's own mods.
     */
    @Test
    fun `a pack being updated is not detached under the update`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1")
        repo.put(pack)
        val release = CompletableDeferred<Unit>()
        launch { work.during("1", InstanceWork.Update) { release.await() } }
        advanceUntilIdle()

        val refused = assertFailsWith<InstanceBusyException> { service(repo, data).detachToLocal(pack) }

        assertEquals(InstanceWork.Update, refused.work)
        assertEquals(pack, repo.get("1"), "nothing about the pack moved")
        release.complete(Unit)
    }

    /**
     * A detach waited for the lock under its claim, so a long sync showed the pack as
     * updating and refused Play for as long as it ran.
     */
    @Test
    fun `a detach refuses at once while the files are locked`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        launch { InstanceMutationLock.withLock(data.resolve("instances").resolve("industrial")) { held.complete(Unit); release.await() } }
        held.await()

        assertFailsWith<InstanceBusyException> { service(repo, data).detachToLocal(pack) }

        assertEquals(pack, repo.get("1"))
        assertNull(work.workOn("1"), "and its claim is let go")
        release.complete(Unit)
    }

    /** The files would be rewritten under the game, the way a delete used to remove them from under it. */
    @Test
    fun `a pack whose game is running is not reattached`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val source = RecordingReattacher()
        val service = service(repo, data, reattachers = mapOf(PackOrigin.Mirror to source))
        val pack = instance("1")
        repo.put(pack)
        val detached = service.detachToLocal(pack)
        runningId.value = "1"

        assertFailsWith<IllegalStateException> { service.reattach(detached, ReattachConfirmation("v9", "any")) }
        assertTrue(source.asked.isEmpty())
        assertEquals(PackOrigin.Local, repo.get("1")?.packRef?.origin)
    }

    /**
     * On Linux the whole tree went from under the live game, which kept writing its
     * world into a directory that no longer existed.
     */
    @Test
    fun `a pack whose game is running is not deleted`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = Files.createDirectories(data.resolve("instances").resolve("industrial"))
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        runningId.value = "1"

        assertEquals(DeleteOutcome.GameRunning, service(repo, data).deleteCompletely(pack))
        assertTrue(Files.exists(instDir))
        assertEquals(pack, repo.get("1"))
    }

    @Test
    fun `a pack being rewritten is not deleted under the work`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = Files.createDirectories(data.resolve("instances").resolve("industrial"))
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        val release = CompletableDeferred<Unit>()
        launch { work.during("1", InstanceWork.Update) { release.await() } }
        advanceUntilIdle()

        assertEquals(DeleteOutcome.Busy(InstanceWork.Update), service(repo, data).deleteCompletely(pack))
        assertTrue(Files.exists(instDir))
        release.complete(Unit)
    }

    /** Another pack's game is none of this one's business. */
    @Test
    fun `a different pack's game does not hold the delete`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        runningId.value = "2"

        assertEquals(DeleteOutcome.Deleted, service(repo, data).deleteCompletely(pack))
    }

    /** Up to three snapshots of a deleted pack's files used to stay on disk for good. */
    @Test
    fun `deleting a pack removes its snapshots and its journal marker too`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = Files.createDirectories(data.resolve("instances").resolve("industrial").resolve("mods"))
        Files.writeString(instDir.resolve("a.jar"), "x")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        PackSnapshotService(data, json).capture(data.resolve("instances/industrial"), pack, setOf("mods/a.jar"), "snap-1", 1L)
        ApplyJournal(data, json).begin(PendingApply("1", "industrial", "snap-1", "1", "2", listOf("mods/a.jar"), 1L))

        assertEquals(DeleteOutcome.Deleted, service(repo, data).deleteCompletely(pack))

        assertFalse(Files.exists(data.resolve("snapshots/industrial")), "snapshots gone")
        assertNull(ApplyJournal(data, json).read("industrial"), "and the marker, which recovery would otherwise chase")
        data.toFile().deleteRecursively()
    }

    /**
     * Detached, the pack stayed bound: the launch swept mods/ against the mirror's
     * baseline and deleted what the now-unlocked Content tab had let the player add.
     */
    @Test
    fun `a detached pack is no longer bound to its server`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val bound = instance("1").copy(
            cachedManifest = CachedManifestSnapshot("1.12.2", "forge", "14.23.5.2860", 8, PackAuthRequirement.SmartyCraft("Industrial")),
        )
        repo.put(bound)

        val detached = service(repo, data).detachToLocal(bound)

        assertNull(detached.cachedManifest?.authRequirement)
        assertNull(repo.get("1")?.cachedManifest?.authRequirement, "persisted unbound")
        assertEquals("forge", repo.get("1")?.cachedManifest?.loaderName, "and nothing else about the runtime moves")
    }
}
