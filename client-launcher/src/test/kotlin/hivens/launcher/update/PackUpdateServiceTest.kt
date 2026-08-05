package hivens.launcher.update

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.toBaselineManifest
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
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
import hivens.launcher.ProtectedPaths
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        private val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): Flow<List<PackInstance>> = flow.asStateFlow()
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

    private fun manifest(version: String, mods: List<String>, assets: List<String>, mc: String = "1.20.1") =
        """{"schema_version":2,"pack_id":"test","pack_version":"$version","generated_at":"now",
            "minecraft":{"version":"$mc"},"loader":{"name":"fabric","version":"0.19.2"},"java":{"major":17},
            "mods":[${mods.joinToString(",")}],"assets":[${assets.joinToString(",")}]}"""

    private fun summary(latest: String) =
        """{"pack_id":"test","display_name":"Test","tagline":"t","minecraft_version":"1.20.1","latest_pack_version":"$latest"}"""

    /** Per-test wiring: a mock mirror whose served manifest/summary can be flipped v1 -> v2. */
    private inner class Harness {
        val dataDir = tempDir("data")
        val clientDir: Path = dataDir.resolve("instances").resolve("inst")
        val repo = FakeRepo()
        private val protectedPaths = ProtectedPaths(tempDir("pp").resolve("pp.json"), json)

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
                else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
            }
        }
        private val provider = HttpClientProvider { HttpClient(engine) }
        val client = SmrtPackClient(provider, MIRROR, json)
        val sync = SmrtSyncService(client, ModrinthClient(provider, testTransferEngine(provider), json), protectedPaths, testTransferEngine(provider))
        private val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        val service = PackUpdateService(client, sync, repo, snapshots, journal, dataDir)

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

        /** Install v1 with optB toggled off, and record the resulting instance. */
        suspend fun installV1(): PackInstance {
            val enabled = mapOf("req.jar" to true, "optB.jar" to false, "drop.jar" to true)
            sync.sync("test", clientDir, enabledState = enabled)
            val v1 = client.fetchManifest("test")
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
        assertTrue(check.plan.toUpdate.contains("mods/req.jar"))
        assertTrue(check.plan.toDelete.contains("mods/drop.jar"))
        assertTrue(check.plan.toAdd.contains("config/new.cfg"))
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
        assertTrue(check.plan.toUpdate.contains("mods/req.jar"))
        assertTrue(check.plan.toDelete.contains("mods/drop.jar"))
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
