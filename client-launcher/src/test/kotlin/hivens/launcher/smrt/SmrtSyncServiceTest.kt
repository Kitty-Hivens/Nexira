package hivens.launcher.smrt

import hivens.test.testTransferEngine
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.HttpClientProvider
import hivens.launcher.ProtectedPaths
import hivens.launcher.modrinth.ModrinthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmrtSyncServiceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(p: String) = Files.createTempDirectory(p).also { temps.add(it) }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private val reqBytes = "REQUIRED".toByteArray()
    private val optBytes = "OPTIONAL".toByteArray()

    private fun manifest() = """
        {"schema_version":2,"pack_id":"test","pack_version":"1","generated_at":"now",
         "minecraft":{"version":"1.20.1"},"loader":{"name":"fabric","version":"0.19.2"},"java":{"major":17},
         "mods":[
           {"filename":"req.jar","sha1":"${sha1(reqBytes)}","size_bytes":${reqBytes.size},"required":true,"source":{"type":"smrt_static","url":"$REQ_URL"}},
           {"filename":"opt.jar","sha1":"${sha1(optBytes)}","size_bytes":${optBytes.size},"required":false,"default_enabled":false,"source":{"type":"smrt_static","url":"$OPT_URL"}}
         ],"assets":[]}
    """.trimIndent()

    /**
     * [failDownloads] cuts the first N mod-file responses the way a middlebox cuts a
     * transfer: the request is answered and the body then fails to arrive. The
     * manifest is always served, since a pack that cannot be described never gets as
     * far as touching the directory.
     */
    private fun syncService(failDownloads: Int = 0): SmrtSyncService {
        var cuts = failDownloads
        return serviceWith(
            MockEngine { req ->
                when (req.url.toString()) {
                    MANIFEST_URL -> respond(manifest(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    REQ_URL, OPT_URL -> {
                        if (cuts > 0) {
                            cuts--
                            throw IOException("stream was reset: PROTOCOL_ERROR")
                        }
                        val bytes = if (req.url.toString() == REQ_URL) reqBytes else optBytes
                        respond(ByteReadChannel(bytes), HttpStatusCode.OK)
                    }
                    else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
                }
            }
        )
    }

    /**
     * Serves the mod bodies with the range semantics a static host has: no range
     * gets the whole object, an offset inside it gets a 206 with the remainder, and
     * an offset at or past the end gets a 416, since there is nothing left to send.
     * [seenRanges] collects the offsets asked for, which is how a test tells a
     * resumed transfer from one that quietly started over.
     *
     * [ignoreRanges] answers every request with the whole object instead, the way a
     * host with no range support does.
     */
    private fun rangeAwareService(
        seenRanges: MutableList<Long> = mutableListOf(),
        ignoreRanges: Boolean = false,
    ): SmrtSyncService = serviceWith(
        MockEngine { req ->
            val url = req.url.toString()
            val bytes = when (url) {
                REQ_URL -> reqBytes
                OPT_URL -> optBytes
                else -> null
            }
            when {
                url == MANIFEST_URL ->
                    respond(manifest(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                bytes == null -> respond("missing $url", HttpStatusCode.NotFound)
                else -> {
                    val from = req.headers[HttpHeaders.Range]
                        ?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
                    if (from > 0L) seenRanges += from
                    when {
                        ignoreRanges || from == 0L -> respond(ByteReadChannel(bytes), HttpStatusCode.OK)
                        from >= bytes.size -> respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
                        else -> respond(
                            ByteReadChannel(bytes.copyOfRange(from.toInt(), bytes.size)),
                            HttpStatusCode.PartialContent,
                        )
                    }
                }
            }
        }
    )

    private fun serviceWith(engine: MockEngine): SmrtSyncService {
        val provider = HttpClientProvider { HttpClient(engine) }
        val client = SmrtPackClient(provider, MIRROR_BASE, json)
        val modrinth = ModrinthClient(provider, testTransferEngine(provider), json)
        return SmrtSyncService(
            client,
            modrinth,
            ProtectedPaths(tempDir("pp").resolve("pp.json"), json),
            testTransferEngine(provider),
        )
    }

    @Test
    fun `enforceRoster drops what the pack does not name and keeps what it does`() = runTest {
        val dir = tempDir("enforce")
        val service = syncService()
        service.sync("test", dir)

        // A hand-placed jar, one a level down, and the kinds of file that are NOT
        // a way to run code: tooling caches in dot-directories (Connector's remapped
        // jars, our block maps) and a stray temp file.
        Files.write(dir.resolve("mods/wurst.jar"), "CHEAT".toByteArray())
        Files.createDirectories(dir.resolve("mods/extra"))
        Files.write(dir.resolve("mods/extra/hidden.jar"), "CHEAT".toByteArray())
        Files.createDirectories(dir.resolve("mods/.connector/temp"))
        Files.write(dir.resolve("mods/.connector/bobby_mapped.jar"), "CACHE".toByteArray())
        Files.createDirectories(dir.resolve("mods/.nexira-blocks"))
        Files.write(dir.resolve("mods/.nexira-blocks/req.jar.blocks"), "MAP".toByteArray())
        Files.write(dir.resolve("mods/Uranus.jar.tmp"), "PARTIAL".toByteArray())
        // A dot-NAMED jar is not tooling state: the loader reads it like any other.
        Files.write(dir.resolve("mods/.cheat.jar"), "CHEAT".toByteArray())

        val verdict = service.enforceRoster(dir)

        assertTrue(verdict.verified, "the sync above wrote a roster, so the check could be made")
        assertFalse(Files.exists(dir.resolve("mods/wurst.jar")), "foreign jar removed")
        assertFalse(Files.exists(dir.resolve("mods/extra/hidden.jar")), "foreign jar in a subdirectory removed")
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "pack mod kept")
        assertTrue(
            Files.exists(dir.resolve("mods/opt.jar.disabled")),
            "an optional the user turned off is part of the pack and stays",
        )
        assertTrue(
            Files.exists(dir.resolve("mods/.connector/bobby_mapped.jar")),
            "a loader's own cache is not foreign content -- wiping it costs a rebuild and protects nothing",
        )
        assertTrue(Files.exists(dir.resolve("mods/.nexira-blocks/req.jar.blocks")), "block maps survive")
        assertTrue(Files.exists(dir.resolve("mods/Uranus.jar.tmp")), "a non-loadable leftover is not executable")
        assertFalse(
            Files.exists(dir.resolve("mods/.cheat.jar")),
            "a leading dot must not smuggle a jar past the sweep -- the loader still loads it",
        )
        assertEquals(
            setOf("wurst.jar", "extra/hidden.jar", ".cheat.jar"),
            verdict.removed.toSet(),
            "only loadable archives outside the roster are touched",
        )
    }

    @Test
    fun `a foreign jar that cannot be deleted leaves the instance unverified`() = runTest {
        // The bypass this pins: mark the file read-only (or deny delete on it) and the
        // sweep's failure used to be swallowed, so the jar stayed AND the launch was
        // treated as verified -- which is what hands it a session token.
        val dir = tempDir("blocked")
        val service = syncService()
        service.sync("test", dir)

        // Denying the delete is expressed through directory permissions, which is a
        // POSIX notion; Windows models this with ACLs and the test does not apply
        // there. The behaviour under test is platform-independent -- what varies is
        // only how one arranges for a delete to fail.
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return@runTest

        val planted = dir.resolve("mods/cheat.jar")
        Files.write(planted, "CHEAT".toByteArray())
        val perms = Files.getPosixFilePermissions(dir.resolve("mods"))
        Files.setPosixFilePermissions(dir.resolve("mods"), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        try {
            val verdict = service.enforceRoster(dir)

            assertFalse(verdict.verified, "a jar that refused to go means the instance is not what the pack says")
            assertEquals(listOf("cheat.jar"), verdict.blocked, "and it is named, not silently dropped from the report")
            assertTrue(Files.exists(planted), "the file is still there -- that is the point")
        } finally {
            Files.setPosixFilePermissions(dir.resolve("mods"), perms)
        }
    }

    @Test
    fun `a plain sync keeps the loader cache it finds under mods`() = runTest {
        // The regression this pins: the ordinary sync path removed any loadable file
        // outside mods/ root, which took out Connector's remapped-jar cache on every
        // single sync -- silently, at the cost of a full remap next launch.
        val dir = tempDir("cache-kept")
        val service = syncService()
        service.sync("test", dir)
        Files.createDirectories(dir.resolve("mods/.connector"))
        Files.write(dir.resolve("mods/.connector/bobby_mapped.jar"), "CACHE".toByteArray())

        service.sync("test", dir)

        assertTrue(
            Files.exists(dir.resolve("mods/.connector/bobby_mapped.jar")),
            "a loader's own cache is not foreign content",
        )
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "the pack itself is untouched")
    }

    /**
     * The property the pre-spawn seal rests on. The check that decides a launch's
     * session has to precede the sign-in it authorises, and the runtime is
     * provisioned after that, so the two moments are far apart. Asking again has
     * to notice what arrived in between -- and "verified" alone does not say so,
     * since the second sweep removes the newcomer and then reports itself clean.
     * Agreement is `removed` being empty, not `verified` being true.
     */
    @Test
    fun `asking a second time reports what arrived in between`() = runTest {
        val dir = tempDir("seal")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))
        val service = syncService()

        val gate = service.enforceRoster(dir, baseline)
        assertTrue(gate.verified)
        assertTrue(gate.removed.isEmpty(), "nothing to remove at the gate")

        // The window: a jar dropped in while the runtime provisions.
        Files.write(dir.resolve("mods/freecam.jar"), "CHEAT".toByteArray())

        val seal = service.enforceRoster(dir, baseline)
        assertEquals(listOf("freecam.jar"), seal.removed, "the second look names the newcomer")
        assertTrue(seal.verified, "and having removed it, calls the instance clean -- which is why removed is what the seal reads")
    }

    // --- the baseline answers what the roster file cannot -----------------------

    private fun sha1Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * The case the roster file cannot see at all: the name is one the pack
     * declared, so a comparison of names is satisfied by the replacement.
     */
    @Test
    fun `a jar overwritten in place fails the verdict and is not deleted`() = runTest {
        val dir = tempDir("baseline-swap")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertFalse(verdict.verified, "the bytes are not the pack's, so no token")
        assertEquals(listOf("req.jar"), verdict.mismatched)
        assertTrue(
            Files.exists(dir.resolve("mods/req.jar")),
            "not deleted -- removing it mid-launch leaves the pack incomplete and the repair path is what fixes it",
        )
    }

    @Test
    fun `matching bytes verify, and a foreign jar still goes`() = runTest {
        val dir = tempDir("baseline-ok")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        Files.write(dir.resolve("mods/freecam.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.verified)
        assertTrue(verdict.mismatched.isEmpty())
        assertEquals(listOf("freecam.jar"), verdict.removed)
    }

    /**
     * A name added to the roster file is how one gets a jar past a comparison of
     * names. The baseline is not on disk beside the mods, so the same edit does
     * nothing.
     */
    @Test
    fun `a roster file edited beside the mods does not widen the baseline`() = runTest {
        val dir = tempDir("baseline-outranks")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        Files.write(dir.resolve("mods/freecam.jar"), "CHEAT".toByteArray())
        Files.write(dir.resolve(".nexira-mods"), "req.jar\nfreecam.jar\n".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertEquals(listOf("freecam.jar"), verdict.removed, "the file on disk does not get a vote")
        assertTrue(verdict.verified)
    }

    /** An optional mod turned off is the same bytes under another name. */
    @Test
    fun `a disabled optional is not tampering`() = runTest {
        val dir = tempDir("baseline-disabled")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/opt.jar.disabled"), "GENUINE".toByteArray())
        val sha = sha1Hex("GENUINE".toByteArray())
        val baseline = mapOf("opt.jar" to sha, "opt.jar.disabled" to sha)

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.verified)
        assertTrue(verdict.removed.isEmpty())
    }

    /** Instances predating the baseline keep the older, weaker answer. */
    @Test
    fun `no baseline falls back to the roster file`() = runTest {
        val dir = tempDir("baseline-absent")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "ANY".toByteArray())
        Files.write(dir.resolve(".nexira-mods"), "req.jar\n".toByteArray())

        val verdict = syncService().enforceRoster(dir, expected = null)

        assertTrue(verdict.verified, "names are all the old answer has")
        assertTrue(verdict.mismatched.isEmpty())
    }

    @Test
    fun `enforceRoster leaves an instance with no roster alone and reports it unverified`() = runTest {
        val dir = tempDir("no-roster")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/whatever.jar"), "BYTES".toByteArray())

        val verdict = syncService().enforceRoster(dir)

        assertFalse(verdict.verified, "nothing to check against")
        assertTrue(
            Files.exists(dir.resolve("mods/whatever.jar")),
            "an absent roster must not be read as an empty pack -- that would wipe a working instance",
        )
        assertTrue(verdict.removed.isEmpty())
    }

    @Test
    fun `optional default-off lands as disabled, required stays active`() = runTest {
        val dir = tempDir("sync")
        syncService().sync("test", dir)
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "required mod active")
        assertFalse(Files.exists(dir.resolve("mods/opt.jar")), "default-off optional not active")
        assertTrue(Files.exists(dir.resolve("mods/opt.jar.disabled")), "default-off optional placed as .disabled")
    }

    @Test
    fun `enabledState activates an otherwise default-off optional`() = runTest {
        val dir = tempDir("sync2")
        syncService().sync("test", dir, enabledState = mapOf("req.jar" to true, "opt.jar" to true))
        assertTrue(Files.exists(dir.resolve("mods/opt.jar")), "user-enabled optional is active")
        assertFalse(Files.exists(dir.resolve("mods/opt.jar.disabled")), "no leftover .disabled variant")
    }

    // --- a broken transfer must not cost the instance its contents ---

    @Test
    fun `a cut transfer is retried rather than failing the pack`() = runTest {
        val dir = tempDir("sync-retry")
        syncService(failDownloads = 2).sync("test", dir)
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "the retry completed the transfer")
    }

    @Test
    fun `a partial left behind is continued rather than refetched`() = runTest {
        val dir = tempDir("sync-resume")
        val mods = Files.createDirectories(dir.resolve("mods"))
        // What a transfer cut mid-body leaves: the head of the object in the partial
        // the commit moves from.
        Files.write(mods.resolve("req.jar.part"), reqBytes.copyOfRange(0, 4))

        val ranges = mutableListOf<Long>()
        rangeAwareService(ranges).sync("test", dir)

        assertContentEquals(reqBytes, Files.readAllBytes(mods.resolve("req.jar")), "resumed file")
        assertTrue(4L in ranges, "the transfer did not ask to continue from the partial")
    }

    @Test
    fun `a partial the host will not continue is dropped instead of failing every attempt`() = runTest {
        val dir = tempDir("sync-416")
        val mods = Files.createDirectories(dir.resolve("mods"))
        // A transfer that reached the last byte but never got committed -- killed
        // launcher, crash, a commit that could not take the lock. The offset is at
        // the end of the object, so the host answers 416 and keeps answering it for
        // as long as the partial decides the offset.
        Files.write(mods.resolve("req.jar.part"), reqBytes + "TRAILING".toByteArray())

        rangeAwareService().sync("test", dir)

        assertContentEquals(reqBytes, Files.readAllBytes(mods.resolve("req.jar")), "refetched file")
    }

    // --- verify and repair ---

    @Test
    fun `repair on an untouched install reports everything intact and fetches nothing`() = runTest {
        val dir = tempDir("repair-clean")
        val service = syncService()
        service.sync("test", dir)
        val manifest = json.decodeFromString(SmrtPackManifest.serializer(), manifest())

        val report = service.verifyAndRepair(dir, manifest)

        assertEquals(2, report.checked, "both mods were not accounted for")
        assertEquals(2, report.intact)
        assertEquals(0L, report.bytesFetched, "an intact pack cost network traffic")
    }

    @Test
    fun `repair replaces a mod that was damaged on disk`() = runTest {
        val dir = tempDir("repair-damaged")
        val service = syncService()
        service.sync("test", dir)
        val manifest = json.decodeFromString(SmrtPackManifest.serializer(), manifest())

        // What a bad sector, a truncating copy or a half-finished manual edit leaves.
        Files.write(dir.resolve("mods/req.jar"), "DAMAGED!".toByteArray())

        val report = service.verifyAndRepair(dir, manifest)

        assertContentEquals(reqBytes, Files.readAllBytes(dir.resolve("mods/req.jar")), "the mod was not restored")
        assertEquals(listOf("req.jar"), report.repaired)
        assertEquals(1, report.intact, "the untouched optional was not counted as intact")
    }

    @Test
    fun `repair puts back a mod that was deleted outright`() = runTest {
        val dir = tempDir("repair-missing")
        val service = syncService()
        service.sync("test", dir)
        val manifest = json.decodeFromString(SmrtPackManifest.serializer(), manifest())
        Files.delete(dir.resolve("mods/req.jar"))

        val report = service.verifyAndRepair(dir, manifest)

        assertContentEquals(reqBytes, Files.readAllBytes(dir.resolve("mods/req.jar")))
        assertEquals(listOf("req.jar"), report.repaired)
    }

    @Test
    fun `a host that ignores the range restarts the transfer instead of appending`() = runTest {
        val dir = tempDir("sync-no-range")
        val mods = Files.createDirectories(dir.resolve("mods"))
        Files.write(mods.resolve("req.jar.part"), reqBytes.copyOfRange(0, 4))

        rangeAwareService(ignoreRanges = true).sync("test", dir)

        // Appending to a partial the response already contains would land 12 bytes
        // and fail the sha1; the partial has to be thrown away instead.
        assertContentEquals(reqBytes, Files.readAllBytes(mods.resolve("req.jar")), "file written from zero")
    }

    @Test
    fun `a failed sync leaves what was already installed`() = runTest {
        val dir = tempDir("sync-keep")
        val mods = Files.createDirectories(dir.resolve("mods"))
        Files.writeString(mods.resolve("foreign.jar"), "from another source")

        // Every attempt is cut, so the sync gives up.
        val failed = runCatching { syncService(failDownloads = Int.MAX_VALUE).sync("test", dir) }
        assertTrue(failed.isFailure, "the sync was expected to fail")
        assertTrue(
            Files.exists(mods.resolve("foreign.jar")),
            "a failed install destroyed content it had not replaced",
        )
    }

    @Test
    fun `a completed sync drops the foreign content it replaced`() = runTest {
        val dir = tempDir("sync-drop")
        val mods = Files.createDirectories(dir.resolve("mods"))
        Files.writeString(mods.resolve("foreign.jar"), "from another source")
        Files.createDirectories(mods.resolve("nested")).also {
            Files.writeString(it.resolve("buried.jar"), "nested payload")
        }

        syncService().sync("test", dir)

        assertFalse(Files.exists(mods.resolve("foreign.jar")), "foreign jar survived a completed sync")
        assertFalse(Files.exists(mods.resolve("nested/buried.jar")), "nested payload survived a completed sync")
        assertTrue(Files.exists(mods.resolve("req.jar")), "the pack's own mod is in place")
    }

    @Test
    fun `a completed sync drops a foreign zip too`() = runTest {
        // A pack may target 1.7.10 -- the legacy Forge resolver takes whatever
        // Minecraft version it is asked for -- and that loader discovers
        // `(.+).(zip|jar)$` out of mods/. Pruning only .jar left a loadable
        // file behind on exactly those packs.
        val dir = tempDir("sync-drop-zip")
        // Synced once first, so the instance is already marked mirror-sourced.
        // Without that the next sync counts as a source change and takes the
        // drop-everything branch, which removes the file whatever its
        // extension -- the test would pass without exercising the orphan prune
        // at all.
        syncService().sync("test", dir)
        val mods = dir.resolve("mods")
        Files.writeString(mods.resolve("foreign.zip"), "loadable on 1.7.10")

        syncService().sync("test", dir)

        assertFalse(Files.exists(mods.resolve("foreign.zip")), "foreign zip survived a completed sync")
        assertTrue(Files.exists(mods.resolve("req.jar")), "the pack's own mod is in place")
    }

    private companion object {
        const val MIRROR_BASE = "https://mirror.test"
        const val MANIFEST_URL = "https://mirror.test/v1/packs/test/manifest"
        const val REQ_URL = "https://mirror.test/req.jar"
        const val OPT_URL = "https://mirror.test/opt.jar"
    }
}
