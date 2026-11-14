package hivens.launcher.update

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.toBaselineManifest
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackAuthRequirement
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.flatten
import hivens.core.update.CompatChange
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateDirection
import hivens.core.update.UpdateOutcome
import hivens.core.update.VersionChannel
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackUpdateServiceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(p: String) = Files.createTempDirectory(p).also { temps.add(it) }
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
    private fun readText(p: Path) = Files.readString(p)

    private class FakeRepo : IPackRepository {
        /** How many of the next record updates fail, the way a full disk fails the registry write. */
        var failingUpdates = 0
        override suspend fun update(id: String, transform: (PackInstance) -> PackInstance): PackInstance? {
            if (failingUpdates > 0) {
                failingUpdates--
                throw IOException("the registry could not be written")
            }
            return super.update(id, transform)
        }
        private val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow.asStateFlow()
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

    private fun mod(filename: String, bytes: ByteArray, url: String, required: Boolean, defaultEnabled: Boolean) =
        """{"filename":"$filename","sha1":"${sha1(bytes)}","size_bytes":${bytes.size},"required":$required,"default_enabled":$defaultEnabled,"source":{"type":"smrt_static","url":"$url"}}"""

    private fun asset(dest: String, bytes: ByteArray, url: String) =
        """{"dest":"$dest","sha1":"${sha1(bytes)}","size_bytes":${bytes.size},"required":true,"source":{"type":"smrt_static","url":"$url"}}"""

    private fun manifest(version: String, mods: List<String>, assets: List<String>, mc: String = "1.20.1", auth: String? = null) =
        """{"schema_version":2,"pack_id":"test","pack_version":"$version","generated_at":"now",
            "minecraft":{"version":"$mc"},"loader":{"name":"fabric","version":"0.19.2"},"java":{"major":17},
            ${auth?.let { "\"auth\":$it," }.orEmpty()}
            "mods":[${mods.joinToString(",")}],"assets":[${assets.joinToString(",")}]}"""

    private fun summary(latest: String) =
        """{"pack_id":"test","display_name":"Test","tagline":"t","minecraft_version":"1.20.1","latest_pack_version":"$latest"}"""

    /**
     * Per-test wiring: a mock mirror whose served manifest/summary can be flipped v1 -> v2.
     * [auth] is the server binding every build declares, as the mirror writes it.
     */
    private inner class Harness(auth: String? = null) {
        val dataDir = tempDir("data")

        /** More addresses the mock mirror answers, for a test that needs a build or a file of its own. */
        val extra = HashMap<String, ByteArray>()
        val clientDir: Path = dataDir.resolve("instances").resolve("inst")
        val repo = FakeRepo()

        // The installed baseline (V1), served at its own version URL so the update
        // path can fetch the baseline mods with identity (not just latest/target).
        val v1Body = manifest(
            V1,
            listOf(
                mod("req.jar", REQ_V1, REQ_V1_URL, required = true, defaultEnabled = true),
                mod("optB.jar", OPTB_V1, OPTB_V1_URL, required = false, defaultEnabled = true),
                mod("drop.jar", DROP, DROP_URL, required = true, defaultEnabled = true),
            ),
            // servers.dat is the one default-protected path the mirror actually
            // ships, and the game rewrites it whenever the player edits their
            // server list -- the case the name-keyed list used to answer.
            listOf(asset("config/x.cfg", CFG, CFG_URL), asset("servers.dat", SERVERS_V1, SERVERS_V1_URL)),
            auth = auth,
        )
        var manifestBody = v1Body
        var summaryBody = summary(V1)
        var versionsBody = """{"schema_version":2,"pack_id":"test","latest":"$V2","builds":[
            {"version_number":"$V2","version_type":"release","date_published":"2026-02-02T00:00:00Z","fingerprint":"bb","mods_count":2,"assets_count":2},
            {"version_number":"$V1","version_type":"release","date_published":"2026-01-01T00:00:00Z","fingerprint":"aa","mods_count":3,"assets_count":1}
        ]}"""

        private val v2Body = manifest(
            V2,
            listOf(
                mod("req.jar", REQ_V2, REQ_V2_URL, required = true, defaultEnabled = true),
                mod("optB.jar", OPTB_V2, OPTB_V2_URL, required = false, defaultEnabled = true),
            ),
            listOf(
                asset("config/x.cfg", CFG, CFG_URL),
                asset("config/new.cfg", NEWCFG, NEWCFG_URL),
                asset("servers.dat", SERVERS_V2, SERVERS_V2_URL),
            ),
            auth = auth,
        )

        private val engine = MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(manifestBody, HttpStatusCode.OK, jsonHeaders)
                MANIFEST_V2_URL -> respond(v2Body, HttpStatusCode.OK, jsonHeaders)
                MANIFEST_V1_URL -> respond(v1Body, HttpStatusCode.OK, jsonHeaders)
                SUMMARY_URL -> respond(summaryBody, HttpStatusCode.OK, jsonHeaders)
                VERSIONS_URL -> respond(versionsBody, HttpStatusCode.OK, jsonHeaders)
                REQ_V1_URL -> respond(ByteReadChannel(REQ_V1), HttpStatusCode.OK)
                REQ_V2_URL -> respond(ByteReadChannel(REQ_V2), HttpStatusCode.OK)
                OPTB_V1_URL -> respond(ByteReadChannel(OPTB_V1), HttpStatusCode.OK)
                OPTB_V2_URL -> respond(ByteReadChannel(OPTB_V2), HttpStatusCode.OK)
                DROP_URL -> respond(ByteReadChannel(DROP), HttpStatusCode.OK)
                CFG_URL -> respond(ByteReadChannel(CFG), HttpStatusCode.OK)
                NEWCFG_URL -> respond(ByteReadChannel(NEWCFG), HttpStatusCode.OK)
                SERVERS_V1_URL -> respond(ByteReadChannel(SERVERS_V1), HttpStatusCode.OK)
                SERVERS_V2_URL -> respond(ByteReadChannel(SERVERS_V2), HttpStatusCode.OK)
                else -> extra[req.url.toString()]?.let { respond(ByteReadChannel(it), HttpStatusCode.OK, jsonHeaders) }
                    ?: respond("missing ${req.url}", HttpStatusCode.NotFound)
            }
        }
        private val provider = HttpClientProvider { HttpClient(engine) }
        val client = SmrtPackClient(provider, MIRROR, json)
        val sync = SmrtSyncService(ModrinthClient(provider, testTransferEngine(provider), json), testTransferEngine(provider))
        val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        val service = PackUpdateService(client, sync, repo, snapshots, journal, dataDir)

        /** Detach and reattach as the launcher runs them, with this mirror as the only source that takes a pack back. */
        val instances = PackInstanceService(
            repository = repo,
            dataDir = dataDir,
            running = object : RunningPackSource {
                override val runningPackInstanceId: StateFlow<String?> = MutableStateFlow(null)
            },
            work = InstanceWorkRegistry(),
            snapshots = snapshots,
            journal = journal,
            sizes = InstanceSizeService(dataDir, CoroutineScope(Dispatchers.Unconfined)),
            reattachers = mapOf(PackOrigin.Mirror to service),
        )

        fun serveV2() { manifestBody = v2Body }

        // An amber v2: same content as v2 but MC 1.20.1 -> 1.20.2, so classifyCompat
        // grades it McBump and the driver snapshots before applying. [reqShaBytes]
        // seeds req.jar's declared sha1 -- pass corrupt bytes to force a mismatch.
        private fun amberV2(reqShaBytes: ByteArray) = manifest(
            V2,
            listOf(
                mod("req.jar", reqShaBytes, REQ_V2_URL, required = true, defaultEnabled = true),
                mod("optB.jar", OPTB_V2, OPTB_V2_URL, required = false, defaultEnabled = true),
            ),
            listOf(
                asset("config/x.cfg", CFG, CFG_URL),
                asset("config/new.cfg", NEWCFG, NEWCFG_URL),
                asset("servers.dat", SERVERS_V1, SERVERS_V1_URL),
            ),
            mc = "1.20.2",
        )
        fun serveAmberV2() { manifestBody = amberV2(REQ_V2) }
        fun serveCorruptAmberV2() { manifestBody = amberV2("WRONG-SHA".toByteArray()) }

        // v2 where optB flips optional -> required with the SAME bytes/url as v1 (a routine
        // curation change): no toUpdate entry, so only the final relabel pass can place it.
        private fun v2OptRequired() = manifest(
            V2,
            listOf(
                mod("req.jar", REQ_V2, REQ_V2_URL, required = true, defaultEnabled = true),
                mod("optB.jar", OPTB_V1, OPTB_V1_URL, required = true, defaultEnabled = true),
            ),
            // servers.dat carried forward unchanged: the build moves, this file does not.
            listOf(asset("config/x.cfg", CFG, CFG_URL), asset("servers.dat", SERVERS_V1, SERVERS_V1_URL)),
        )
        fun serveV2OptRequired() { manifestBody = v2OptRequired() }

        /** [installV1], bound to its server the way an install of a build that declares one records it. */
        suspend fun installBound(): PackInstance {
            val installed = installV1()
            val bound = installed.copy(cachedManifest = installed.cachedManifest?.copy(authRequirement = PackAuthRequirement.SmartyCraft("Industrial")))
            repo.put(bound)
            return bound
        }

        /** Install v1 with optB toggled off, and record the resulting instance. */
        suspend fun installV1(): PackInstance {
            val enabled = mapOf("req.jar" to true, "optB.jar" to false, "drop.jar" to true)
            val v1 = client.fetchManifest("test")
            sync.sync(v1, clientDir, enabledState = enabled)
            val instance = PackInstance(
                id = "i1",
                packRef = PackReference(PackOrigin.Mirror, "test", v1.packVersion),
                displayName = "Test",
                instanceDirName = "inst",
                createdAtEpoch = 0L,
                pinnedPackVersion = v1.packVersion,
                optionalContent = OptionalContentRules.togglesFrom(v1.mods, enabled),
                cachedManifest = CachedManifestSnapshot(
                    v1.minecraft.version, v1.loader.name, v1.loader.version, v1.java.major,
                ),
                installedManifest = v1.toBaselineManifest(),
            )
            repo.put(instance)
            return instance
        }
    }

    @Test
    fun `applyUpdate updates changed, deletes dropped, adds new, keeps optional disabled`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        // Sanity: v1 landed with optB disabled and drop present.
        assertTrue(Files.exists(h.clientDir.resolve("mods/optB.jar.disabled")))
        assertFalse(Files.exists(h.clientDir.resolve("mods/optB.jar")))
        assertTrue(Files.exists(h.clientDir.resolve("mods/drop.jar")))

        h.serveV2()
        val outcome = h.service.applyUpdate(instance, null, null)
        assertTrue(outcome is UpdateOutcome.Applied)
        // Even a green update with changes snapshots now, so a mid-apply failure can revert.
        assertTrue(h.service.listSnapshots(instance).isNotEmpty())
        // The in-flight apply marker is cleared after commit -- no stale journal to
        // trigger a false rollback on the next start.
        assertTrue(h.journal.listPending().isEmpty(), "apply marker cleared after commit")

        // Required mod re-downloaded to the new bytes.
        assertEquals("REQ-V2", readText(h.clientDir.resolve("mods/req.jar")))
        // Optional stays OFF across the update, updated bytes at the .disabled variant.
        assertFalse(Files.exists(h.clientDir.resolve("mods/optB.jar")))
        assertEquals("OPTB-V2", readText(h.clientDir.resolve("mods/optB.jar.disabled")))
        // Dropped mod removed.
        assertFalse(Files.exists(h.clientDir.resolve("mods/drop.jar")))
        // Unchanged asset left as is; new asset added.
        assertEquals("CFG", readText(h.clientDir.resolve("config/x.cfg")))
        assertEquals("NEWCFG", readText(h.clientDir.resolve("config/new.cfg")))

        // Instance committed at the new version with a v2 baseline and carried toggle.
        val committed = h.repo.get("i1")!!
        assertEquals(V2, committed.pinnedPackVersion)
        assertEquals(V2, committed.packRef.version)
        val baseline = committed.installedManifest!!.flatten()
        assertTrue(baseline.containsKey("config/new.cfg"))
        assertFalse(baseline.containsKey("mods/drop.jar"))
        assertEquals(false, committed.optionalContent.first { it.entryId == "optB.jar" }.enabled)
    }

    @Test
    fun `checkForUpdate reports up-to-date then available`() = runTest {
        val h = Harness()
        val instance = h.installV1()

        h.summaryBody = summary(V1)
        assertTrue(h.service.checkForUpdate(instance) is UpdateCheck.UpToDate)

        h.summaryBody = summary(V2)
        h.serveV2()
        val check = h.service.checkForUpdate(instance)
        assertTrue(check is UpdateCheck.Available)
        assertEquals(V2, check.toVersion)
        assertTrue(check.plan!!.toUpdate.contains("mods/req.jar"))
        assertTrue(check.plan!!.toDelete.contains("mods/drop.jar"))
        assertTrue(check.plan!!.toAdd.contains("config/new.cfg"))
    }

    @Test
    fun `applyUpdate is a no-op when already at the target version`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        // Still serving v1; applying "latest" (v1) must not fetch/write.
        val outcome = h.service.applyUpdate(instance, null, null)
        assertTrue(outcome is UpdateOutcome.AlreadyCurrent)
    }

    @Test
    fun `a stale summary can no longer invent an update onto the installed build`() = runTest {
        // The two reads used to be independent: the summary's latest pointer
        // decided whether anything moved, the manifest endpoint decided to what.
        // Separate cache namespaces with separate TTLs let them disagree, and a
        // summary left over from before an apply pointed the user back at the
        // build they just left. The manifest is the single source now, so a
        // summary saying otherwise changes nothing.
        val h = Harness()
        val instance = h.installV1()
        h.summaryBody = summary(V2)

        assertEquals(UpdateCheck.UpToDate, h.service.checkForUpdate(instance))
    }

    @Test
    fun `a mirror-side rollback of latest reports the move as backwards`() = runTest {
        // Installed at V2, mirror serves V1 as current: a real state, since the
        // mirror can retire a bad build. Detection is label inequality, so this
        // arrives through the same path as a release and would otherwise be
        // announced as an update onto an older build.
        val h = Harness()
        h.serveV2()
        val installed = h.installV1().let { h.service.applyUpdate(it, null, null); h.repo.get(it.id)!! }
        assertEquals(V2, installed.pinnedPackVersion)

        h.manifestBody = h.v1Body

        val check = h.service.checkForUpdate(installed)
        assertTrue(check is UpdateCheck.Available, "a different current build is still a move")
        assertEquals(V1, check.toVersion)
        assertEquals(UpdateDirection.Older, check.direction)
    }

    @Test
    fun `a forward move reports the move as newer`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        h.serveV2()

        val check = h.service.checkForUpdate(instance)
        assertTrue(check is UpdateCheck.Available)
        assertEquals(UpdateDirection.Newer, check.direction)
    }

    @Test
    fun `a build the listing no longer retains is left unranked`() = runTest {
        // Honest over confident: with the target missing from the publish-ordered
        // listing there is no ranking to give, and version tuples cannot supply
        // one across channels.
        val h = Harness()
        val instance = h.installV1()
        h.serveV2()
        h.versionsBody = """{"schema_version":2,"pack_id":"test","latest":"$V2","builds":[
            {"version_number":"$V1","version_type":"release","date_published":"2026-01-01T00:00:00Z","fingerprint":"aa","mods_count":3,"assets_count":1}
        ]}"""

        val check = h.service.checkForUpdate(instance)
        assertTrue(check is UpdateCheck.Available)
        assertEquals(UpdateDirection.Unknown, check.direction)
    }

    @Test
    fun `availableBuilds keeps the server's newest-first order and channel data`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        val builds = h.service.availableBuilds(instance)
        assertEquals(listOf(V2, V1), builds.map { it.versionNumber })
        assertEquals(VersionChannel.Release, builds.first().channel)
        assertEquals(2, builds.first().modsCount)
        assertEquals("bb", builds.first().fingerprint)
    }

    @Test
    fun `a channel build with an older-looking tuple still reports available`() = runTest {
        // SNAPSHOT-... tuples to [0,0,0,...] which the old ordering read as older
        // than any release, silently reporting UpToDate for the whole snapshot
        // chain. Detection is label inequality now.
        val h = Harness()
        val instance = h.installV1()
        val snap = "SNAPSHOT-0.0.0-2026.07.18.7"
        h.summaryBody = summary(snap)
        h.manifestBody = manifest(
            snap,
            listOf(mod("req.jar", REQ_V2, REQ_V2_URL, required = true, defaultEnabled = true)),
            emptyList(),
        )
        val check = h.service.checkForUpdate(instance)
        assertTrue(check is UpdateCheck.Available)
        assertEquals(snap, check.toVersion)
    }

    @Test
    fun `previewSwitch plans against the chosen build and is quiet on the current one`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        assertTrue(h.service.previewSwitch(instance, V1) is UpdateCheck.UpToDate)

        val check = h.service.previewSwitch(instance, V2)
        assertTrue(check is UpdateCheck.Available)
        assertEquals(V2, check.toVersion)
        assertTrue(check.plan!!.toUpdate.contains("mods/req.jar"))
        assertTrue(check.plan!!.toDelete.contains("mods/drop.jar"))
    }

    @Test
    fun `amber update snapshots and rollback restores the previous build`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        h.serveAmberV2()
        assertTrue(h.service.applyUpdate(instance, null, null) is UpdateOutcome.Applied)

        // A structural (MC) change took one snapshot; v2 is live.
        val snaps = h.service.listSnapshots(instance)
        assertEquals(1, snaps.size)
        assertEquals(V1, snaps.first().fromVersion)
        assertEquals("REQ-V2", readText(h.clientDir.resolve("mods/req.jar")))
        assertFalse(Files.exists(h.clientDir.resolve("mods/drop.jar")))
        assertTrue(Files.exists(h.clientDir.resolve("config/new.cfg")))

        val restored = h.service.rollback(instance, snaps.first().id)

        // Files and metadata are back to v1; a rollback also pins (stops following latest).
        assertEquals(V1, restored.pinnedPackVersion)
        assertEquals(false, restored.followLatest)
        assertEquals(V1, h.repo.get("i1")!!.pinnedPackVersion)
        assertEquals("REQ-V1", readText(h.clientDir.resolve("mods/req.jar")))
        assertEquals("DROP", readText(h.clientDir.resolve("mods/drop.jar")))               // deleted mod restored
        assertEquals("OPTB-V1", readText(h.clientDir.resolve("mods/optB.jar.disabled")))   // optional stays off, old bytes
        assertFalse(Files.exists(h.clientDir.resolve("config/new.cfg")))                   // added asset removed
    }

    /**
     * The snapshot gives each file back under the name it had when it was taken, and
     * the record keeps the choice as it is now. Switched on after the update, the
     * optional came back under its off name, on in the record and off in the game.
     */
    @Test
    fun `a rollback puts the restored files under the names the current choice gives them`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        h.serveAmberV2()
        assertTrue(h.service.applyUpdate(instance, null, null) is UpdateOutcome.Applied)
        val snapshot = h.service.listSnapshots(instance).single()
        // The player switches optB on after the update.
        val v2 = h.client.fetchManifest("test")
        val on = mapOf("req.jar" to true, "optB.jar" to true)
        h.repo.put(h.repo.get("i1")!!.copy(optionalContent = OptionalContentRules.togglesFrom(v2.mods, on)))
        h.sync.relabel(h.clientDir, v2.mods, on)

        h.service.rollback(instance, snapshot.id)

        assertEquals("OPTB-V1", readText(h.clientDir.resolve("mods/optB.jar")), "the restored bytes, under the name the choice gives them")
        assertFalse(Files.exists(h.clientDir.resolve("mods/optB.jar.disabled")))
    }

    @Test
    fun `a failed amber apply auto-reverts to the snapshot`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        h.serveCorruptAmberV2()

        var threw = false
        try {
            h.service.applyUpdate(instance, null, null)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw, "a sha1 mismatch must surface")

        // Auto-revert left the instance on v1 -- files and metadata intact.
        assertEquals("REQ-V1", readText(h.clientDir.resolve("mods/req.jar")))
        assertEquals("DROP", readText(h.clientDir.resolve("mods/drop.jar")))
        assertFalse(Files.exists(h.clientDir.resolve("config/new.cfg")))
        assertEquals(V1, h.repo.get("i1")!!.pinnedPackVersion)
        // The failed snapshot was cleaned up.
        assertTrue(h.service.listSnapshots(instance).isEmpty())
        // The in-flight apply marker is cleared after the in-process revert, so the
        // startup recovery does not try to roll back an already-reverted instance.
        assertTrue(h.journal.listPending().isEmpty(), "apply marker cleared after auto-revert")
    }

    @Test
    fun `an optional flipped to required is placed active even with unchanged bytes`() = runTest {
        val h = Harness()
        val instance = h.installV1() // optB off -> mods/optB.jar.disabled (OPTB-V1)
        assertTrue(Files.exists(h.clientDir.resolve("mods/optB.jar.disabled")))

        h.serveV2OptRequired()
        assertTrue(h.service.applyUpdate(instance, null, null) is UpdateOutcome.Applied)

        // Same bytes but now required: the final relabel pass must move it to the active name.
        assertTrue(Files.exists(h.clientDir.resolve("mods/optB.jar")))
        assertFalse(Files.exists(h.clientDir.resolve("mods/optB.jar.disabled")))
        assertEquals("OPTB-V1", readText(h.clientDir.resolve("mods/optB.jar")))
    }

    @Test
    fun `a null-baseline instance grades amber`() = runTest {
        val h = Harness()
        val preBaseline = h.installV1().copy(installedManifest = null) // predates the baseline field
        h.repo.put(preBaseline)
        h.summaryBody = summary(V2)
        h.serveV2()

        val check = h.service.checkForUpdate(preBaseline)
        assertTrue(check is UpdateCheck.Available)
        assertEquals(CompatChange.Unknown, check.compat)
    }

    @Test
    fun `a protected-by-name path the pack changed is now delivered`() = runTest {
        // servers.dat is on the default protected list, so the update used to drop
        // it into skippedProtected and never write it. A pack that moves its server
        // to another address could not reach anyone who already had the file.
        val h = Harness()
        val instance = h.installV1()
        assertEquals("SERVERS-V1", readText(h.clientDir.resolve("servers.dat")))

        h.serveV2()
        assertTrue(h.service.applyUpdate(instance, null, null) is UpdateOutcome.Applied)

        assertEquals("SERVERS-V2", readText(h.clientDir.resolve("servers.dat")))
    }

    @Test
    fun `an edit to a path the pack left alone survives the update`() = runTest {
        // What the protected list was standing in for, now answered by content: the
        // build moves, this file does not, so the only difference on disk is the
        // player's own.
        val h = Harness()
        val instance = h.installV1()
        Files.writeString(h.clientDir.resolve("servers.dat"), "PLAYER-ADDED-A-SERVER")

        h.serveV2OptRequired()
        assertTrue(h.service.applyUpdate(instance, null, null) is UpdateOutcome.Applied)

        assertEquals("PLAYER-ADDED-A-SERVER", readText(h.clientDir.resolve("servers.dat")))
        // Not parked as a conflict either -- one side moving is not a merge.
        assertFalse(Files.exists(h.clientDir.resolve("servers.dat.new")))
        // The rest of the update still landed.
        assertEquals("REQ-V2", readText(h.clientDir.resolve("mods/req.jar")))
    }

    @Test
    fun `an explicit version switch stops following latest`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        assertTrue(h.service.applyUpdate(instance, V2, null) is UpdateOutcome.Applied)
        assertEquals(false, h.repo.get("i1")!!.followLatest)
    }

    /**
     * A delete can take the instance's lock while an update waits for it. The update
     * then carried on from the record it had read before the wait and wrote the whole
     * build back into the directory the delete had removed.
     */
    @Test
    fun `an update that finds its instance deleted writes nothing`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        h.serveV2()
        h.clientDir.toFile().deleteRecursively()
        h.repo.delete(instance.id)

        assertFailsWith<InstanceRemovedException> { h.service.applyUpdate(instance, null, null) }

        assertFalse(Files.exists(h.clientDir), "the deleted directory stays deleted")
        assertTrue(h.journal.listPending().isEmpty())
    }

    @Test
    fun `a rollback that finds its instance deleted writes nothing`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        h.serveAmberV2()
        h.service.applyUpdate(instance, null, null)
        val snapshot = h.service.listSnapshots(instance).single()
        h.clientDir.toFile().deleteRecursively()
        h.repo.delete(instance.id)

        assertFailsWith<InstanceRemovedException> { h.service.rollback(instance, snapshot.id) }

        assertFalse(Files.exists(h.clientDir))
        assertEquals(null, h.repo.get(instance.id))
    }

    // ── Reattach ──────────────────────────────────────────────────────

    /** Reattaches [detached] on the preview a player would be shown, confirmed as shown. */
    private suspend fun Harness.reattachAsShown(detached: PackInstance): PackInstance =
        instances.reattach(detached, instances.previewReattach(detached).confirmation)

    @Test
    fun `a reattach puts the source back as the reference with the binding an install gives it`() = runTest {
        val h = Harness(auth = """{"kind":"smartycraft","server_id":"Industrial"}""")
        val detached = h.instances.detachToLocal(h.installBound())
        assertEquals(PackOrigin.Local, detached.packRef.origin)
        assertNull(detached.cachedManifest?.authRequirement)
        assertTrue(h.instances.canReattach(detached))

        val back = h.reattachAsShown(detached)

        assertEquals(PackReference(PackOrigin.Mirror, "test", V1), back.packRef)
        assertNull(back.forkedFrom, "the fork note goes with the fork")
        assertEquals(PackAuthRequirement.SmartyCraft("Industrial"), back.cachedManifest?.authRequirement, "bound to its server again")
        assertEquals(V1, back.pinnedPackVersion)
        assertEquals(h.client.fetchManifest("test").toBaselineManifest(), back.installedManifest)
        assertEquals(back, h.repo.get("i1"), "and that is what the registry holds")
        assertFalse(h.instances.canReattach(back), "nothing to reattach once it is back")
        assertTrue(h.journal.listPending().isEmpty())
    }

    @Test
    fun `the reattach preview lists exactly what will change, and the reattach does just that`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val mods = h.clientDir.resolve("mods")
        Files.writeString(mods.resolve("extra.jar"), "MINE")
        Files.writeString(mods.resolve("notes.txt"), "not a mod")
        Files.delete(mods.resolve("req.jar"))
        Files.writeString(mods.resolve("drop.jar"), "PATCHED")
        Files.writeString(h.clientDir.resolve("config/x.cfg"), "EDITED")

        val preview = h.instances.previewReattach(detached)

        assertEquals(V1, preview.toVersion)
        assertEquals(CompatChange.Same, preview.compat)
        assertEquals(listOf("mods/extra.jar"), preview.removed)
        assertEquals(listOf("mods/req.jar"), preview.restored)
        assertEquals(listOf("mods/drop.jar"), preview.replaced, "a mod is held to the build, a config edit the build did not change is not listed")
        assertEquals(listOf("mods/drop.jar", "mods/req.jar"), preview.returned)
        assertTrue(preview.turnedOn.isEmpty() && preview.turnedOff.isEmpty() && preview.kept.isEmpty())

        h.instances.reattach(detached, preview.confirmation)

        assertFalse(Files.exists(mods.resolve("extra.jar")), "a mod the player added is gone")
        assertEquals("REQ-V1", readText(mods.resolve("req.jar")), "one they removed is back")
        assertEquals("DROP", readText(mods.resolve("drop.jar")), "one they changed is the source's again")
        assertEquals("EDITED", readText(h.clientDir.resolve("config/x.cfg")), "the player's config edit survives, as an update keeps it")
        assertEquals("not a mod", readText(mods.resolve("notes.txt")), "what no loader reads is not the sweep's")
        assertEquals("OPTB-V1", readText(mods.resolve("optB.jar.disabled")), "an optional the player turned off stays off")
        assertTrue(h.instances.previewReattach(detached).isEmpty, "nothing differs once it is done")
    }

    /**
     * A reattach moves the pack the way the source's own update does. It used to place
     * the build as a fresh install would, and a config the player had tuned went back
     * to the pack's copy although the build had not changed it.
     */
    @Test
    fun `a reattach keeps a config edit the build did not change and updates what the build changed`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        Files.writeString(h.clientDir.resolve("config/x.cfg"), "MY SETTINGS")
        h.serveV2()

        val preview = h.instances.previewReattach(detached)

        assertEquals(V2, preview.toVersion)
        assertEquals(listOf("mods/drop.jar"), preview.removed)
        assertEquals(listOf("config/new.cfg"), preview.restored)
        assertEquals(listOf("mods/optB.jar", "mods/req.jar", "servers.dat"), preview.replaced)
        assertTrue(preview.kept.isEmpty())

        h.instances.reattach(detached, preview.confirmation)

        assertEquals("MY SETTINGS", readText(h.clientDir.resolve("config/x.cfg")), "the build left it alone, so the edit stays")
        assertEquals("SERVERS-V2", readText(h.clientDir.resolve("servers.dat")), "the build changed it, so it is updated")
        assertEquals("NEWCFG", readText(h.clientDir.resolve("config/new.cfg")))
        assertEquals("REQ-V2", readText(h.clientDir.resolve("mods/req.jar")))
        assertFalse(Files.exists(h.clientDir.resolve("mods/drop.jar")))
    }

    @Test
    fun `a file both the player and the build changed keeps the player's copy and gets the build's beside it`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        Files.writeString(h.clientDir.resolve("servers.dat"), "MY SERVERS")
        h.serveV2()

        val preview = h.instances.previewReattach(detached)
        assertEquals(listOf("servers.dat"), preview.kept)
        assertFalse("servers.dat" in preview.replaced)

        h.instances.reattach(detached, preview.confirmation)

        assertEquals("MY SERVERS", readText(h.clientDir.resolve("servers.dat")))
        assertEquals("SERVERS-V2", readText(h.clientDir.resolve("servers.dat.new")))
    }

    /**
     * The player agreed to a list. Anything else, a confirmation that names no preview
     * or one the pack no longer matches, used to have the files rewritten all the same.
     */
    @Test
    fun `a reattach without a preview that still holds refuses and touches nothing`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val mods = h.clientDir.resolve("mods")
        Files.writeString(mods.resolve("extra.jar"), "MINE")
        val shown = h.instances.previewReattach(detached)
        Files.writeString(mods.resolve("late.jar"), "ADDED AFTER THE PREVIEW")

        assertFailsWith<ReattachPreviewStaleException> { h.instances.reattach(detached, shown.confirmation) }
        assertFailsWith<ReattachPreviewStaleException> { h.instances.reattach(detached, ReattachConfirmation(V1, "made up")) }
        h.serveV2()
        assertFailsWith<ReattachPreviewStaleException> {
            h.instances.reattach(detached, h.instances.previewReattach(detached).confirmation.copy(build = V1))
        }

        assertEquals("MINE", readText(mods.resolve("extra.jar")))
        assertEquals("ADDED AFTER THE PREVIEW", readText(mods.resolve("late.jar")))
        assertEquals(detached, h.repo.get("i1"), "the record is the detached one still")
        assertTrue(h.service.listSnapshots(detached).isEmpty(), "not even a snapshot was taken")
        assertTrue(h.journal.listPending().isEmpty())
    }

    @Test
    fun `a reattach that fails part way puts back the player's mods and the detached record`() = runTest {
        val h = Harness(auth = """{"kind":"smartycraft","server_id":"Industrial"}""")
        val detached = h.instances.detachToLocal(h.installBound())
        val mods = h.clientDir.resolve("mods")
        Files.writeString(mods.resolve("extra.jar"), "MINE")
        Files.delete(mods.resolve("req.jar"))
        val confirmed = h.instances.previewReattach(detached).confirmation
        // The files are all in place by the time the record is written, and the write fails.
        h.repo.failingUpdates = 1

        assertFailsWith<IOException> { h.instances.reattach(detached, confirmed) }

        assertEquals("MINE", readText(mods.resolve("extra.jar")), "the swept mod is back")
        assertFalse(Files.exists(mods.resolve("req.jar")), "and what the build brought is gone again")
        val record = h.repo.get("i1")!!
        assertEquals(PackOrigin.Local, record.packRef.origin)
        assertEquals(detached.forkedFrom, record.forkedFrom)
        assertNull(record.cachedManifest?.authRequirement)
        assertTrue(h.journal.listPending().isEmpty())
    }

    /**
     * Rolled back with the build fields alone, the record kept the source's reference
     * over the player's own mods again: unbound, its Content tab locked, and no
     * reattach offered.
     */
    @Test
    fun `rolling back a reattach returns the pack to the detached state it came from`() = runTest {
        val h = Harness(auth = """{"kind":"smartycraft","server_id":"Industrial"}""")
        val detached = h.instances.detachToLocal(h.installBound())
        Files.writeString(h.clientDir.resolve("mods/extra.jar"), "MINE")
        val back = h.reattachAsShown(detached)

        val rolled = h.service.rollback(back, h.service.listSnapshots(back).first().id)

        assertEquals(PackOrigin.Local, rolled.packRef.origin)
        assertEquals(detached.forkedFrom, rolled.forkedFrom)
        assertNull(rolled.cachedManifest?.authRequirement, "unbound, as a detached pack is")
        assertEquals(rolled, h.repo.get("i1"))
        assertEquals("MINE", readText(h.clientDir.resolve("mods/extra.jar")))
        assertTrue(h.instances.canReattach(rolled), "and it can be reattached again")
    }

    @Test
    fun `a crash between the reattach commit and its marker is recovered to the detached state`() = runTest {
        val h = Harness(auth = """{"kind":"smartycraft","server_id":"Industrial"}""")
        val detached = h.instances.detachToLocal(h.installBound())
        Files.writeString(h.clientDir.resolve("mods/extra.jar"), "MINE")
        val back = h.reattachAsShown(detached)
        val snapshot = h.service.listSnapshots(back).first()
        // What a crash right after the commit leaves: the marker the guard clears last.
        h.journal.begin(PendingApply(back.id, back.instanceDirName, snapshot.id, V1, V1, listOf("mods/extra.jar", "mods/req.jar"), 1L))

        ApplyRecovery(h.snapshots, h.repo, h.journal, h.dataDir, InstanceWorkRegistry()).recoverInterrupted()

        val record = h.repo.get("i1")!!
        assertEquals(PackOrigin.Local, record.packRef.origin)
        assertEquals(detached.forkedFrom, record.forkedFrom)
        assertNull(record.cachedManifest?.authRequirement)
        assertEquals("MINE", readText(h.clientDir.resolve("mods/extra.jar")))
    }

    /**
     * A mod dropped in unpacked is one the loader reads. Left by the reattach, every
     * launch of the bound pack after it went without a token, and nobody had said why.
     * A folder kept by a link is taken as the link, and an empty folder inside one
     * comes back with it.
     */
    @Test
    fun `an unpacked mod folder is listed, removed, and put back by a rollback`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val folder = Files.createDirectories(h.clientDir.resolve("mods/handmade"))
        Files.writeString(folder.resolve("mcmod.info"), "[]")
        Files.writeString(folder.resolve("Handmade.class"), "CAFEBABE")
        Files.createDirectories(folder.resolve("assets/empty"))
        val elsewhere = Files.createDirectories(tempDir("linked-mod").resolve("linked"))
        Files.writeString(elsewhere.resolve("mcmod.info"), "[]")
        val link = Files.createSymbolicLink(h.clientDir.resolve("mods/linked"), elsewhere)

        val preview = h.instances.previewReattach(detached)
        assertEquals(listOf("mods/handmade", "mods/linked"), preview.removed)

        val back = h.instances.reattach(detached, preview.confirmation)
        assertFalse(Files.exists(folder), "the folder goes with the reattach")
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link goes")
        assertTrue(Files.exists(elsewhere.resolve("mcmod.info")), "and what it points at is left alone")

        h.service.rollback(back, h.service.listSnapshots(back).first().id)
        assertEquals("CAFEBABE", readText(folder.resolve("Handmade.class")), "and comes back whole with its rollback")
        assertTrue(Files.isDirectory(folder.resolve("assets/empty")), "its empty folders too")
        assertTrue(Files.isSymbolicLink(link), "the link comes back as a link")
        assertEquals(elsewhere, Files.readSymbolicLink(link))
    }

    /**
     * The version folder is read by the loader, and the first entries of one holding
     * an unpacked mod look like a mod themselves. The strict sweep took the whole
     * folder for one and deleted it.
     */
    @Test
    fun `the version folder is never a stray, only what is in it`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val versionDir = Files.createDirectories(h.clientDir.resolve("mods/1.7.10"))
        val exploded = Files.createDirectories(versionDir.resolve("exploded"))
        Files.writeString(exploded.resolve("mcmod.info"), "[]")
        Files.writeString(exploded.resolve("Exploded.class"), "CAFEBABE")
        Files.writeString(versionDir.resolve("notes.txt"), "kept")

        val preview = h.instances.previewReattach(detached)
        assertEquals(listOf("mods/1.7.10/exploded"), preview.removed)

        h.instances.reattach(detached, preview.confirmation)
        assertFalse(Files.exists(exploded))
        assertEquals("kept", readText(versionDir.resolve("notes.txt")), "and the folder with the rest of it stays")
    }

    /**
     * A link under a mod's name read as the build's file to the scan, which reads
     * through it, so the reattach left the link in place: whatever it points at
     * later is what the game loads.
     */
    @Test
    fun `a mod kept as a link is replaced by the build's own file`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val elsewhere = tempDir("linked-jar").resolve("req.jar").also { Files.write(it, REQ_V1) }
        val req = h.clientDir.resolve("mods/req.jar")
        Files.delete(req)
        Files.createSymbolicLink(req, elsewhere)

        val preview = h.instances.previewReattach(detached)
        assertEquals(listOf("mods/req.jar"), preview.replaced)

        h.instances.reattach(detached, preview.confirmation)
        assertFalse(Files.isSymbolicLink(req))
        assertEquals("REQ-V1", readText(req))
        assertEquals("REQ-V1", readText(elsewhere), "what the link pointed at is left alone")
    }

    /**
     * An update reads the record before it waits for the lock. A detach that landed
     * in between had the update write its source's build and server binding onto a
     * pack that had just become its player's own.
     */
    @Test
    fun `an update asked for before a detach refuses once it finds the pack detached`() = runTest {
        val h = Harness(auth = """{"kind":"smartycraft","server_id":"Industrial"}""")
        val bound = h.installBound()
        h.instances.detachToLocal(bound)
        h.serveV2()

        assertFailsWith<InstanceChangedException> { h.service.applyUpdate(bound, null, null) }

        val record = h.repo.get(bound.id)!!
        assertEquals(PackOrigin.Local, record.packRef.origin)
        assertNull(record.cachedManifest?.authRequirement, "not bound again")
        assertEquals("REQ-V1", readText(h.clientDir.resolve("mods/req.jar")), "and no file moved")
    }

    /**
     * The reconcile keeps a jar the player changed where the build did not change it,
     * and with the build the pack was detached from gone it has no identity to tell
     * otherwise. The reattach reported success over a jar the build never shipped.
     */
    @Test
    fun `a swapped jar is the build's again even when the build the pack left is gone`() = runTest {
        val h = Harness()
        val installed = h.installV1()
        // A pin the mirror no longer serves: the mods' identity cannot be read.
        h.repo.put(installed.copy(pinnedPackVersion = "2025.12.31"))
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        Files.writeString(h.clientDir.resolve("mods/drop.jar"), "EVIL")

        val preview = h.instances.previewReattach(detached)
        assertEquals(ReattachBasis.NamesOnly, preview.basis, "and the dialog says what it is going on")
        assertEquals(listOf("mods/drop.jar"), preview.replaced)

        h.instances.reattach(detached, preview.confirmation)
        assertEquals("DROP", readText(h.clientDir.resolve("mods/drop.jar")))
    }

    /** An update parks the build's copy beside an edit both sides made. A jar is the build's instead. */
    @Test
    fun `a mod both the player and the build changed is the build's after a reattach`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        Files.writeString(h.clientDir.resolve("mods/req.jar"), "MINE")
        h.serveV2()

        val preview = h.instances.previewReattach(detached)
        assertTrue("mods/req.jar" in preview.replaced)
        assertTrue(preview.kept.isEmpty())

        h.instances.reattach(detached, preview.confirmation)
        assertEquals("REQ-V2", readText(h.clientDir.resolve("mods/req.jar")))
        assertFalse(Files.exists(h.clientDir.resolve("mods/req.jar.new")))
    }

    @Test
    fun `with no record of what the build placed every difference is replaced and the preview says so`() = runTest {
        val h = Harness()
        val installed = h.installV1()
        h.repo.put(installed.copy(installedManifest = null, pinnedPackVersion = "2025.12.31"))
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        Files.writeString(h.clientDir.resolve("config/x.cfg"), "EDITED")

        val preview = h.instances.previewReattach(detached)

        assertEquals(ReattachBasis.NoBaseline, preview.basis)
        assertEquals(CompatChange.Unknown, preview.compat)
        assertEquals(listOf("config/x.cfg"), preview.replaced, "an edit cannot be told from the build's, so it is listed as replaced")
    }

    /**
     * FML extracts a contained dependency only where no file of its name sits yet, so
     * whatever bytes sat under that name stayed through a reattach for good.
     */
    @Test
    fun `whatever sits under a name a jar declares is removed for the loader to extract again`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val carrier = jarDeclaring("scala-library-2.11.1.jar")
        Files.write(h.clientDir.resolve("mods/scalar.jar"), carrier)
        val versionDir = Files.createDirectories(h.clientDir.resolve("mods/1.7.10"))
        Files.writeString(versionDir.resolve("scala-library-2.11.1.jar"), "NOT THE CARRIER'S")
        // A build that ships the carrier, so it is on the roster.
        val v3 = "2026.03.03"
        h.extra["https://mirror.test/dl/scalar"] = carrier
        val body = manifest(
            v3,
            listOf(
                mod("req.jar", REQ_V1, REQ_V1_URL, required = true, defaultEnabled = true),
                mod("optB.jar", OPTB_V1, OPTB_V1_URL, required = false, defaultEnabled = true),
                mod("drop.jar", DROP, DROP_URL, required = true, defaultEnabled = true),
                mod("scalar.jar", carrier, "https://mirror.test/dl/scalar", required = true, defaultEnabled = true),
            ),
            listOf(asset("config/x.cfg", CFG, CFG_URL), asset("servers.dat", SERVERS_V1, SERVERS_V1_URL)),
        )
        h.manifestBody = body
        h.extra["https://mirror.test/v1/packs/test/manifest/$v3"] = body.toByteArray()

        val preview = h.instances.previewReattach(detached)
        assertEquals(listOf("mods/1.7.10/scala-library-2.11.1.jar"), preview.removed)

        h.instances.reattach(detached, preview.confirmation)
        assertFalse(Files.exists(versionDir.resolve("scala-library-2.11.1.jar")))
        assertTrue(Files.exists(h.clientDir.resolve("mods/scalar.jar")))
    }

    /**
     * On a filesystem that ignores case the build's `JEI.jar` opens `jei.jar`, so the
     * scan found the build's bytes and wrote nothing, and the sweep then removed the
     * file by its name. A hard link stands in for that here: two names, one file.
     */
    @Test
    fun `a file the build's name opens is not a stray`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        Files.createLink(h.clientDir.resolve("mods/Req.jar"), h.clientDir.resolve("mods/req.jar"))

        val preview = h.instances.previewReattach(detached)

        assertFalse("mods/Req.jar" in preview.removed)
    }

    /**
     * A detach that landed while an update ran had the update's rollback put the
     * source back over the player's own mods, bound to its server again.
     */
    @Test
    fun `an ordinary update rolled back after a detach leaves the pack detached`() = runTest {
        val h = Harness(auth = """{"kind":"smartycraft","server_id":"Industrial"}""")
        val bound = h.installBound()
        val guard = ApplyGuard(h.snapshots, h.journal, h.repo)

        assertFailsWith<IOException> {
            guard.apply(h.clientDir, bound, setOf("mods/req.jar"), V1, V2) {
                h.repo.update(bound.id) { current ->
                    current.copy(
                        packRef = current.packRef.copy(origin = PackOrigin.Local),
                        forkedFrom = current.packRef,
                        cachedManifest = current.cachedManifest?.copy(authRequirement = null),
                    )
                }
                throw IOException("the build did not land")
            }
        }

        val record = h.repo.get(bound.id)!!
        assertEquals(PackOrigin.Local, record.packRef.origin)
        assertEquals(bound.packRef, record.forkedFrom)
        assertNull(record.cachedManifest?.authRequirement)
    }

    /**
     * Rolled back, a reattach is a Local pack again whose mods are its owner's. The
     * build's rules for which of them are on moved a mod the player had switched on.
     */
    @Test
    fun `rolling back a reattach leaves the player's switches as they were`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val mods = h.clientDir.resolve("mods")
        Files.move(mods.resolve("optB.jar.disabled"), mods.resolve("optB.jar"))
        val back = h.reattachAsShown(detached)
        assertTrue(Files.exists(mods.resolve("optB.jar.disabled")), "the build's choice, while reattached")

        h.service.rollback(back, h.service.listSnapshots(back).first().id)

        assertTrue(Files.exists(mods.resolve("optB.jar")), "the player's choice again")
        assertFalse(Files.exists(mods.resolve("optB.jar.disabled")))
    }

    /**
     * The scan reads a mod under either name, so a mod switched by renaming it made
     * no difference to the preview, while the sync moved it all the same.
     */
    @Test
    fun `a mod switched by its name is listed as switched back`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val mods = h.clientDir.resolve("mods")
        Files.move(mods.resolve("req.jar"), mods.resolve("req.jar.disabled"))
        Files.move(mods.resolve("optB.jar.disabled"), mods.resolve("optB.jar"))

        val preview = h.instances.previewReattach(detached)

        assertEquals(listOf("mods/req.jar"), preview.turnedOn, "a required mod switched off comes back on")
        assertEquals(listOf("mods/optB.jar"), preview.turnedOff, "an optional the pack has off goes off")
        assertTrue(preview.removed.isEmpty() && preview.restored.isEmpty() && preview.replaced.isEmpty())
        assertFalse(preview.isEmpty)

        h.instances.reattach(detached, preview.confirmation)
        assertTrue(Files.exists(mods.resolve("req.jar")))
        assertTrue(Files.exists(mods.resolve("optB.jar.disabled")))
        assertFalse(Files.exists(mods.resolve("optB.jar")))
    }

    @Test
    fun `a reattach that moves Minecraft is graded like the update that would`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        h.serveAmberV2()

        val preview = h.instances.previewReattach(detached)

        assertEquals(V2, preview.toVersion)
        assertEquals(CompatChange.McBump, preview.compat)
        assertFalse(preview.compat.isSafe)
    }

    @Test
    fun `a pinned pack goes back to its pinned build, and to the latest once the source no longer has it`() = runTest {
        val kept = Harness()
        val pinned = kept.installV1().copy(followLatest = false)
        kept.repo.put(pinned)
        kept.serveV2()
        assertEquals(V1, kept.reattachAsShown(kept.instances.detachToLocal(pinned)).pinnedPackVersion)
        assertEquals("REQ-V1", readText(kept.clientDir.resolve("mods/req.jar")))

        val retired = Harness()
        val stale = retired.installV1().copy(followLatest = false)
        retired.repo.put(stale)
        retired.serveV2()
        retired.versionsBody = """{"schema_version":2,"pack_id":"test","latest":"$V2","builds":[
            {"version_number":"$V2","version_type":"release","date_published":"2026-02-02T00:00:00Z","fingerprint":"bb","mods_count":2,"assets_count":2}
        ]}"""
        val detached = retired.instances.detachToLocal(stale)
        assertEquals(V2, retired.instances.previewReattach(detached).toVersion)
        assertEquals(V2, retired.reattachAsShown(detached).pinnedPackVersion)
        assertEquals("REQ-V2", readText(retired.clientDir.resolve("mods/req.jar")))
        assertFalse(Files.exists(retired.clientDir.resolve("mods/drop.jar")), "a mod the new build dropped goes with the sweep")
    }

    /** A follow-latest pack's recorded build is not a pin: it was simply the build on the day it was detached. */
    @Test
    fun `a pack that follows the latest goes back to the latest`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        h.serveV2()

        assertEquals(V2, h.reattachAsShown(detached).packRef.version)
        assertEquals("REQ-V2", readText(h.clientDir.resolve("mods/req.jar")))
    }

    /** A jar whose manifest declares [contained] as `ContainedDeps`, the way FML reads it. */
    private fun jarDeclaring(vararg contained: String): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("ContainedDeps", contained.joinToString(" "))
        }
        val out = ByteArrayOutputStream()
        JarOutputStream(out, manifest).use { }
        return out.toByteArray()
    }

    private companion object {
        const val MIRROR = "https://mirror.test"
        const val MANIFEST_URL = "https://mirror.test/v1/packs/test/manifest"
        const val MANIFEST_V2_URL = "https://mirror.test/v1/packs/test/manifest/2026.02.02"
        const val MANIFEST_V1_URL = "https://mirror.test/v1/packs/test/manifest/2026.01.01"
        const val SUMMARY_URL = "https://mirror.test/v1/packs/test"
        const val VERSIONS_URL = "https://mirror.test/v1/packs/test/manifest/versions"
        const val REQ_V1_URL = "https://mirror.test/dl/req-v1"
        const val REQ_V2_URL = "https://mirror.test/dl/req-v2"
        const val OPTB_V1_URL = "https://mirror.test/dl/optb-v1"
        const val OPTB_V2_URL = "https://mirror.test/dl/optb-v2"
        const val DROP_URL = "https://mirror.test/dl/drop"
        const val CFG_URL = "https://mirror.test/dl/cfg"
        const val NEWCFG_URL = "https://mirror.test/dl/newcfg"
        const val SERVERS_V1_URL = "https://mirror.test/dl/servers-v1"
        const val SERVERS_V2_URL = "https://mirror.test/dl/servers-v2"
        const val V1 = "2026.01.01"
        const val V2 = "2026.02.02"

        val REQ_V1 = "REQ-V1".toByteArray()
        val REQ_V2 = "REQ-V2".toByteArray()
        val OPTB_V1 = "OPTB-V1".toByteArray()
        val OPTB_V2 = "OPTB-V2".toByteArray()
        val DROP = "DROP".toByteArray()
        val CFG = "CFG".toByteArray()
        val NEWCFG = "NEWCFG".toByteArray()
        val SERVERS_V1 = "SERVERS-V1".toByteArray()
        val SERVERS_V2 = "SERVERS-V2".toByteArray()

        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
