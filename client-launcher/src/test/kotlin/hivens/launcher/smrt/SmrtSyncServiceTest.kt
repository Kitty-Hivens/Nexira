package hivens.launcher.smrt

import hivens.test.testTransferEngine
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.HttpClientProvider
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
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
     * The build the caller picked. sync places what it is handed rather than
     * fetching a pack's current manifest, which is the whole point: an install of
     * an older build used to record that build and download the newest one.
     */
    private fun parsed(text: String = manifest()) =
        json.decodeFromString(SmrtPackManifest.serializer(), text)

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
        val modrinth = ModrinthClient(provider, testTransferEngine(provider), json)
        return SmrtSyncService(modrinth, testTransferEngine(provider))
    }


    // ── Assets the pack owns ──────────────────────────────────────────────────

    private val serversBytes = "MIRROR-SERVER-LIST".toByteArray()

    private fun assetManifest() = """
        {"schema_version":2,"pack_id":"test","pack_version":"1","generated_at":"now",
         "minecraft":{"version":"1.20.1"},"loader":{"name":"fabric","version":"0.19.2"},"java":{"major":17},
         "mods":[],
         "assets":[
           {"dest":"servers.dat","sha1":"${sha1(serversBytes)}","size_bytes":${serversBytes.size},"required":true,"source":{"type":"smrt_static","url":"$SERVERS_URL"}}
         ]}
    """.trimIndent()

    private fun assetService(): SmrtSyncService = serviceWith(
        MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(assetManifest(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                SERVERS_URL -> respond(ByteReadChannel(serversBytes), HttpStatusCode.OK)
                else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
            }
        }
    )

    /**
     * The mirror ships the server list on purpose, so a pack that names it must be
     * able to deliver it. A name-based exemption used to skip any listed path that
     * already existed on disk, which meant the pack's own `servers.dat` never
     * arrived after the first install -- the exemption belongs to the `clients/`
     * path, where there is no baseline to reason with.
     */
    @Test
    fun `the pack delivers its own server list over an existing one`() = runTest {
        val dir = tempDir("asset-owned")
        Files.writeString(dir.resolve("servers.dat"), "STALE")

        assetService().sync(parsed(assetManifest()), dir)

        assertContentEquals(
            serversBytes,
            Files.readAllBytes(dir.resolve("servers.dat")),
            "an asset the pack names must land even when a file is already there",
        )
    }

    @Test
    fun `enforceRoster drops what the pack does not name and keeps what it does`() = runTest {
        val dir = tempDir("enforce")
        val service = syncService()
        service.sync(parsed(), dir)

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
        assertTrue(
            Files.exists(dir.resolve("mods/extra/hidden.jar")),
            "a subdirectory holding no mod is not a place the loader reads",
        )
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
            setOf("wurst.jar", ".cheat.jar"),
            verdict.removed.toSet(),
            "only loadable archives where the loader reads them are touched",
        )
    }

    @Test
    fun `a foreign jar that cannot be deleted leaves the instance unverified`() = runTest {
        // The bypass this pins: mark the file read-only (or deny delete on it) and the
        // sweep's failure used to be swallowed, so the jar stayed AND the launch was
        // treated as verified -- which is what hands it a session token.
        val dir = tempDir("blocked")
        val service = syncService()
        service.sync(parsed(), dir)

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
        service.sync(parsed(), dir)
        Files.createDirectories(dir.resolve("mods/.connector"))
        Files.write(dir.resolve("mods/.connector/bobby_mapped.jar"), "CACHE".toByteArray())

        service.sync(parsed(), dir)

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

    /**
     * A jar an antivirus is holding open, or one the previous session has not let
     * go of yet, reads exactly like this. Calling that a mismatch tells the player
     * they swapped a mod, on evidence that is only a failed open.
     */
    @Test
    fun `a jar that cannot be read is reported apart from one that does not match`() = runTest {
        val dir = tempDir("baseline-unreadable")
        Files.createDirectories(dir.resolve("mods"))
        val locked = dir.resolve("mods/req.jar")
        Files.write(locked, "GENUINE".toByteArray())
        Files.write(dir.resolve("mods/opt.jar"), "SWAPPED".toByteArray())
        val genuine = sha1Hex("GENUINE".toByteArray())
        val baseline = mapOf("req.jar" to genuine, "opt.jar" to genuine)

        // Same POSIX caveat as the blocked-delete test above: what varies by
        // platform is only how one arranges for a read to fail.
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return@runTest
        val perms = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, emptySet())
        // Root is not bound by the mode bits, so there would be nothing to observe.
        if (Files.isReadable(locked)) return@runTest
        try {
            val verdict = syncService().enforceRoster(dir, baseline)

            assertEquals(listOf("req.jar"), verdict.unreadable)
            assertEquals(listOf("opt.jar"), verdict.mismatched, "only the one actually compared is accused")
            assertFalse(verdict.verified, "unchecked is not cleared -- it still denies the token")
        } finally {
            Files.setPosixFilePermissions(locked, perms)
        }
    }

    /**
     * The whole reason the read-only variant exists: it runs against a live game,
     * where deleting a jar the loader has open breaks the install.
     */
    @Test
    fun `inspectRoster finds what enforceRoster would remove and removes nothing`() = runTest {
        val dir = tempDir("inspect-readonly")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        Files.write(dir.resolve("mods/freecam.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val inspection = syncService().inspectRoster(dir, baseline)

        assertEquals(listOf("freecam.jar"), inspection.foreign)
        assertFalse(inspection.isClean)
        assertTrue(Files.exists(dir.resolve("mods/freecam.jar")), "reported, not removed")
        assertTrue(Files.exists(dir.resolve("mods/req.jar")))
    }

    @Test
    fun `inspectRoster sees a jar swapped under a name the pack declared`() = runTest {
        val dir = tempDir("inspect-swap")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val inspection = syncService().inspectRoster(dir, baseline)

        assertEquals(listOf("req.jar"), inspection.mismatched)
        assertTrue(inspection.foreign.isEmpty(), "the name is one the pack declared")
        assertFalse(inspection.isClean)
    }

    @Test
    fun `inspectRoster is clean on an untouched instance and reports an absent roster`() = runTest {
        val dir = tempDir("inspect-clean")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val clean = syncService().inspectRoster(dir, baseline)
        assertTrue(clean.isClean)
        assertTrue(clean.checkable)

        // No baseline and no roster file: nothing to hold it to, which must not
        // arrive as a clean bill of health.
        val blind = syncService().inspectRoster(tempDir("inspect-blind"), expected = null)
        assertFalse(blind.checkable)
    }

    /**
     * A toggle flip during a session renames a jar between its two roster names.
     * Both are declared, so the scan has nothing to report -- otherwise turning an
     * optional mod off mid-game would look like tampering.
     */
    @Test
    fun `inspectRoster does not read a toggled optional as foreign`() = runTest {
        val dir = tempDir("inspect-toggle")
        Files.createDirectories(dir.resolve("mods"))
        Files.write(dir.resolve("mods/opt.jar.disabled"), "GENUINE".toByteArray())
        val sha = sha1Hex("GENUINE".toByteArray())
        val baseline = mapOf("opt.jar" to sha, "opt.jar.disabled" to sha)

        val inspection = syncService().inspectRoster(dir, baseline)

        assertTrue(inspection.isClean, "a disabled optional is the same bytes under the other declared name")
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

    /**
     * A jar built for real, because the rule under test reads a manifest attribute
     * out of one. The other fixtures here are plain bytes under a `.jar` name,
     * which is all a name comparison ever needed.
     */
    private fun jarDeclaring(vararg contained: String): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (contained.isNotEmpty()) mainAttributes.putValue("ContainedDeps", contained.joinToString(" "))
        }
        val out = ByteArrayOutputStream()
        JarOutputStream(out, manifest).use { }
        return out.toByteArray()
    }

    /**
     * Scalar is the case: a coremod that carries a Scala runtime and has FML lay it
     * out under `mods/<mcversion>/` once the game is up. Those files appear after
     * the launch, in the window the session guard watches, and used to read as an
     * instance modifying itself.
     */
    @Test
    fun `what a rostered jar declares it unpacks is not foreign`() = runTest {
        val dir = tempDir("contained-deps")
        Files.createDirectories(dir.resolve("mods/1.12.2"))
        val scalar = jarDeclaring("scala-library-2.11.1.jar", "scala-reflect-2.11.1.jar")
        Files.write(dir.resolve("mods/scalar.jar"), scalar)
        Files.write(dir.resolve("mods/1.12.2/scala-library-2.11.1.jar"), "RUNTIME".toByteArray())
        Files.write(dir.resolve("mods/1.12.2/scala-reflect-2.11.1.jar"), "RUNTIME".toByteArray())
        val baseline = mapOf("scalar.jar" to sha1Hex(scalar))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.verified)
        assertTrue(verdict.removed.isEmpty(), "the loader unpacked these out of a jar the pack vouches for")
        assertTrue(Files.exists(dir.resolve("mods/1.12.2/scala-library-2.11.1.jar")))
        assertTrue(Files.exists(dir.resolve("mods/1.12.2/scala-reflect-2.11.1.jar")))
    }

    /**
     * The allowance is a set of names, not an exemption for the directory. Both
     * `mods/` and `mods/<mcversion>/` are read by the loader, so a directory-wide
     * pass would be a place to run code from unquestioned.
     */
    @Test
    fun `an undeclared jar beside the unpacked ones is still foreign`() = runTest {
        val dir = tempDir("contained-deps-stranger")
        Files.createDirectories(dir.resolve("mods/1.12.2"))
        val scalar = jarDeclaring("scala-library-2.11.1.jar")
        Files.write(dir.resolve("mods/scalar.jar"), scalar)
        Files.write(dir.resolve("mods/1.12.2/scala-library-2.11.1.jar"), "RUNTIME".toByteArray())
        Files.write(dir.resolve("mods/1.12.2/freecam.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("scalar.jar" to sha1Hex(scalar))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertEquals(listOf("1.12.2/freecam.jar"), verdict.removed)
        assertTrue(Files.exists(dir.resolve("mods/1.12.2/scala-library-2.11.1.jar")), "the declared one stays")
    }

    /** Only a jar the roster already names gets a say, or one planted jar would clear the way for others. */
    @Test
    fun `a foreign jar cannot declare its way past the sweep`() = runTest {
        val dir = tempDir("contained-deps-laundering")
        Files.createDirectories(dir.resolve("mods/1.12.2"))
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        Files.write(dir.resolve("mods/cheat.jar"), jarDeclaring("payload.jar"))
        Files.write(dir.resolve("mods/1.12.2/payload.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertEquals(setOf("cheat.jar", "1.12.2/payload.jar"), verdict.removed.toSet())
    }

    /**
     * The names are read off the jar each time rather than remembered, so moving
     * the carrier to a build that ships a different runtime version needs nothing
     * here, and what the old build unpacked stops being declared and goes.
     */
    @Test
    fun `moving the carrier to another build re-reads its names and sweeps the old ones`() = runTest {
        val dir = tempDir("contained-deps-bumped")
        Files.createDirectories(dir.resolve("mods/1.12.2"))
        val bumped = jarDeclaring("scala-library-2.11.12.jar")
        Files.write(dir.resolve("mods/scalar.jar"), bumped)
        Files.write(dir.resolve("mods/1.12.2/scala-library-2.11.12.jar"), "RUNTIME".toByteArray())
        // left behind by the build the pack used to pin
        Files.write(dir.resolve("mods/1.12.2/scala-library-2.11.1.jar"), "OLD".toByteArray())
        val baseline = mapOf("scalar.jar" to sha1Hex(bumped))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertEquals(listOf("1.12.2/scala-library-2.11.1.jar"), verdict.removed, "the superseded runtime goes")
        assertTrue(Files.exists(dir.resolve("mods/1.12.2/scala-library-2.11.12.jar")), "the one it declares now stays")
    }

    /** A mod switched off unpacks nothing, so it vouches for nothing. */
    @Test
    fun `a disabled carrier does not vouch for what it would have unpacked`() = runTest {
        val dir = tempDir("contained-deps-disabled")
        Files.createDirectories(dir.resolve("mods/1.12.2"))
        val scalar = jarDeclaring("scala-library-2.11.1.jar")
        Files.write(dir.resolve("mods/scalar.jar.disabled"), scalar)
        Files.write(dir.resolve("mods/1.12.2/scala-library-2.11.1.jar"), "LEFTOVER".toByteArray())
        val sha = sha1Hex(scalar)
        val baseline = mapOf("scalar.jar" to sha, "scalar.jar.disabled" to sha)

        val verdict = syncService().enforceRoster(dir, baseline)

        assertEquals(listOf("1.12.2/scala-library-2.11.1.jar"), verdict.removed)
    }

    /**
     * IndustrialCraft 2's case: a jar a mod unpacks into a directory of its own
     * choosing. FML reads `mods/` and `mods/<mcversion>/` and neither read
     * recurses, so nothing will load it but the mod that put it there.
     */
    @Test
    fun `an archive in a mod's own directory is not foreign`() = runTest {
        val dir = tempDir("carried")
        Files.createDirectories(dir.resolve("mods/ic2"))
        Files.write(dir.resolve("mods/IndustrialCraft.jar"), "GENUINE".toByteArray())
        Files.write(dir.resolve("mods/ic2/EJML-core-0.26.jar"), "LIB".toByteArray())
        val baseline = mapOf("IndustrialCraft.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.removed.isEmpty(), "nothing reads a mod's own directory")
        assertTrue(Files.exists(dir.resolve("mods/ic2/EJML-core-0.26.jar")))
    }

    /**
     * CodeChickenCore's dependency loader moves what it finds into
     * `mods/<mcversion>/`, so the pack's own jar ends up somewhere the pack never
     * put it, and the original is gone from the top level.
     */
    @Test
    fun `a pack mod moved down a level by the loader is recognised by its bytes`() = runTest {
        val dir = tempDir("relocated")
        Files.createDirectories(dir.resolve("mods/1.7.10"))
        Files.write(dir.resolve("mods/1.7.10/Baubles-1.7.10-1.0.1.10.jar"), "GENUINE".toByteArray())
        val baseline = mapOf("Baubles-1.7.10-1.0.1.10.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.removed.isEmpty(), "same name and same bytes as the pack's own mod")
        assertTrue(Files.exists(dir.resolve("mods/1.7.10/Baubles-1.7.10-1.0.1.10.jar")))
    }

    /** The bytes are what the name cannot carry: a swap under a pack name still goes. */
    @Test
    fun `a different jar under a relocated pack name is still foreign`() = runTest {
        val dir = tempDir("relocated-swap")
        Files.createDirectories(dir.resolve("mods/1.7.10"))
        Files.write(dir.resolve("mods/1.7.10/Baubles-1.7.10-1.0.1.10.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("Baubles-1.7.10-1.0.1.10.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertEquals(listOf("1.7.10/Baubles-1.7.10-1.0.1.10.jar"), verdict.removed)
    }

    /** FML unpacks one level down, and reads nothing deeper. */
    @Test
    fun `a declared name deeper than the unpack directory is left alone`() = runTest {
        val dir = tempDir("contained-deps-depth")
        Files.createDirectories(dir.resolve("mods/1.12.2/nested"))
        val scalar = jarDeclaring("scala-library-2.11.1.jar")
        Files.write(dir.resolve("mods/scalar.jar"), scalar)
        Files.write(dir.resolve("mods/1.12.2/nested/scala-library-2.11.1.jar"), "CHEAT".toByteArray())
        val baseline = mapOf("scalar.jar" to sha1Hex(scalar))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.removed.isEmpty(), "nothing loads from two levels down")
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
        syncService().sync(parsed(), dir)
        assertTrue(Files.exists(dir.resolve("mods/req.jar")), "required mod active")
        assertFalse(Files.exists(dir.resolve("mods/opt.jar")), "default-off optional not active")
        assertTrue(Files.exists(dir.resolve("mods/opt.jar.disabled")), "default-off optional placed as .disabled")
    }

    @Test
    fun `enabledState activates an otherwise default-off optional`() = runTest {
        val dir = tempDir("sync2")
        syncService().sync(parsed(), dir, enabledState = mapOf("req.jar" to true, "opt.jar" to true))
        assertTrue(Files.exists(dir.resolve("mods/opt.jar")), "user-enabled optional is active")
        assertFalse(Files.exists(dir.resolve("mods/opt.jar.disabled")), "no leftover .disabled variant")
    }

    // --- a broken transfer must not cost the instance its contents ---

    @Test
    fun `a cut transfer is retried rather than failing the pack`() = runTest {
        val dir = tempDir("sync-retry")
        syncService(failDownloads = 2).sync(parsed(), dir)
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
        rangeAwareService(ranges).sync(parsed(), dir)

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

        rangeAwareService().sync(parsed(), dir)

        assertContentEquals(reqBytes, Files.readAllBytes(mods.resolve("req.jar")), "refetched file")
    }

    // --- verify and repair ---

    @Test
    fun `repair on an untouched install reports everything intact and fetches nothing`() = runTest {
        val dir = tempDir("repair-clean")
        val service = syncService()
        service.sync(parsed(), dir)
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
        service.sync(parsed(), dir)
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
        service.sync(parsed(), dir)
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

        rangeAwareService(ignoreRanges = true).sync(parsed(), dir)

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
        val failed = runCatching { syncService(failDownloads = Int.MAX_VALUE).sync(parsed(), dir) }
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

        syncService().sync(parsed(), dir)

        assertFalse(Files.exists(mods.resolve("foreign.jar")), "foreign jar survived a completed sync")
        // Nothing reads `mods/nested/`, so what sits in it is a mod's belongings
        // rather than a way to run code. The sweep stopped claiming otherwise when
        // holding that line meant meeting a new mechanism on every launch.
        assertTrue(Files.exists(mods.resolve("nested/buried.jar")), "a mod's own directory is not swept")
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
        syncService().sync(parsed(), dir)
        val mods = dir.resolve("mods")
        Files.writeString(mods.resolve("foreign.zip"), "loadable on 1.7.10")

        syncService().sync(parsed(), dir)

        assertFalse(Files.exists(mods.resolve("foreign.zip")), "foreign zip survived a completed sync")
        assertTrue(Files.exists(mods.resolve("req.jar")), "the pack's own mod is in place")
    }

    private companion object {
        const val MIRROR_BASE = "https://mirror.test"
        const val MANIFEST_URL = "https://mirror.test/v1/packs/test/manifest"
        const val REQ_URL = "https://mirror.test/req.jar"
        const val OPT_URL = "https://mirror.test/opt.jar"
        const val SERVERS_URL = "https://mirror.test/servers.dat"
    }

    /**
     * Carpenter's Blocks writes a generated texture cache into its own directory
     * while the game runs. It is a zip, which the 1.7.10 discovery would read as
     * a mod anywhere the loader looks, and this is not one of those places.
     */
    @Test
    fun `a zip a mod generates in its own directory is not foreign`() = runTest {
        val dir = tempDir("generated")
        Files.createDirectories(dir.resolve("mods/carpentersblocks"))
        Files.write(dir.resolve("mods/CarpentersBlocks.jar"), "GENUINE".toByteArray())
        Files.write(
            dir.resolve("mods/carpentersblocks/CarpentersBlocksCachedResources.zip"),
            "TEXTURES".toByteArray(),
        )
        val baseline = mapOf("CarpentersBlocks.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertTrue(verdict.removed.isEmpty())
    }

    /** The version directory is read by the loader, so the standard there is unchanged. */
    @Test
    fun `an undeclared jar in the version directory is still foreign`() = runTest {
        val dir = tempDir("version-dir-strict")
        Files.createDirectories(dir.resolve("mods/1.7.10"))
        Files.write(dir.resolve("mods/1.7.10/freecam.jar"), "CHEAT".toByteArray())

        val verdict = syncService().enforceRoster(dir, mapOf("req.jar" to sha1Hex("X".toByteArray())))

        assertEquals(listOf("1.7.10/freecam.jar"), verdict.removed)
    }

    /**
     * The shape the archive rule never looked at. Forge adds a directory it finds
     * beside the mods as a candidate and reads it as an unpacked mod, so classes
     * dropped there run, and `.class` is not a name the sweep treats as loadable.
     */
    @Test
    fun `a mod unpacked into a directory beside the mods is foreign`() = runTest {
        val dir = tempDir("exploded")
        Files.createDirectories(dir.resolve("mods/cheat/wurst"))
        Files.write(dir.resolve("mods/cheat/mcmod.info"), "[{\"modid\":\"wurst\"}]".toByteArray())
        Files.write(dir.resolve("mods/cheat/wurst/Main.class"), "CAFEBABE".toByteArray())
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())
        val baseline = mapOf("req.jar" to sha1Hex("GENUINE".toByteArray()))

        val verdict = syncService().enforceRoster(dir, baseline)

        assertFalse(verdict.verified, "an unpacked mod must not leave the instance vouched for")
        assertEquals(listOf("cheat"), verdict.blocked)
        assertTrue(Files.exists(dir.resolve("mods/cheat/mcmod.info")), "reported, not deleted")
    }

    /** A directory a mod fills with its own data is not a mod. */
    @Test
    fun `a mod's data directory is not read as an unpacked mod`() = runTest {
        val dir = tempDir("not-exploded")
        Files.createDirectories(dir.resolve("mods/carpentersblocks"))
        Files.write(dir.resolve("mods/carpentersblocks/cache.zip"), "TEXTURES".toByteArray())
        Files.write(dir.resolve("mods/req.jar"), "GENUINE".toByteArray())

        val verdict = syncService().enforceRoster(dir, mapOf("req.jar" to sha1Hex("GENUINE".toByteArray())))

        assertTrue(verdict.verified)
        assertTrue(verdict.blocked.isEmpty())
    }
}
