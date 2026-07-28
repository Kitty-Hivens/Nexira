package hivens.launcher

import hivens.core.api.HttpClientProvider
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondBadRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * End-to-end integration coverage for [FileDownloadService.processSession]
 * against a real on-disk sandbox. The unit tests in [FileDownloadServiceTest]
 * exercise pure-helper logic (path normalization, MD5, staleness predicate);
 * this harness covers the orchestration that ties them together -- which has
 * surface for regressions that no helper test could catch.
 *
 * Each test runs in a fresh tempdir. HTTP is mocked at the
 * [io.ktor.client.HttpClient] boundary so we can both serve canned bytes
 * AND assert how many requests were made (the cache short-circuit is
 * verified by call count, not by guessing through state).
 *
 * Per the user's explicit ask: "tests for managing builds -- how files
 * download, parse, what should happen in incorrect cases. Should run
 * exclusively on dev machine because files will be guaranteed broken."
 * Tempdir per test enforces that -- broken-on-purpose state never escapes.
 */
class FileDownloadDiskIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private lateinit var workDir: Path
    private lateinit var clientDir: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-fds-disk-")
        clientDir = workDir / "clients" / "Industrial"
        Files.createDirectories(clientDir)
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    fun `processSession downloads every manifest entry and disk MD5 matches`() = runBlocking {
        val files = mapOf(
            "mods/foo.jar" to "I am a fake mod jar".toByteArray(),
            "mods/bar.jar" to "Another fake mod".toByteArray(),
            "config/foo.cfg" to "key=value\nother=42".toByteArray(),
        )
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        svc.processSession(
            session = sessionWith(manifest),
            serverId = "Industrial",
            targetDir = clientDir,
            extraCheckSum = null,
            ignoredFiles = null,
            messageUI = null,
            progressUI = null,
        )

        for ((relPath, expectedBytes) in files) {
            val onDisk = clientDir.resolve(relPath)
            assertTrue(Files.exists(onDisk), "missing expected file: $relPath")
            assertEquals(expectedBytes.toList(), Files.readAllBytes(onDisk).toList(),
                "byte mismatch on $relPath")
        }
        assertEquals(files.size, requests.get(),
            "expected one HTTP fetch per manifest file")
    }

    // ── Idempotent re-sync ────────────────────────────────────────────────

    @Test
    fun `second processSession with identical manifest hits manifest-cache and skips network`() = runBlocking {
        val files = mapOf("mods/foo.jar" to "stable bytes".toByteArray())
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)
        val firstCount = requests.get()

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertEquals(firstCount, requests.get(),
            "manifest-cache short-circuit should produce ZERO additional fetches")
    }

    // ── Corruption recovery ───────────────────────────────────────────────

    @Test
    fun `corrupted local file (wrong content) is replaced with the manifest version`() = runBlocking {
        val files = mapOf("mods/foo.jar" to "the-correct-bytes".toByteArray())
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        // Pre-corrupt: file present on disk with wrong content (and therefore
        // wrong MD5). The integrity walk should detect mismatch and refetch.
        val corrupt = clientDir.resolve("mods/foo.jar")
        Files.createDirectories(corrupt.parent)
        Files.writeString(corrupt, "WRONG-LOCAL-BYTES-PRE-EXISTING")

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertEquals("the-correct-bytes", Files.readString(corrupt),
            "corrupt local file must be replaced from upstream")
        assertEquals(1, requests.get(), "exactly one refetch -- the only stale file")
    }

    @Test
    fun `missing local file is downloaded`() = runBlocking {
        // config/* paths so the #169 mods-jar ZIP-validity scan doesn't
        // trigger -- focus of this test is the up-to-date-vs-missing
        // dispatch, not jar integrity. Pre-existing file with matching
        // MD5 must NOT be re-fetched.
        val files = mapOf(
            "config/foo.cfg" to "foo content".toByteArray(),
            "config/bar.cfg" to "bar content".toByteArray(),
        )
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        // Pre-populate one of two -- the other is missing.
        Files.createDirectories(clientDir.resolve("config"))
        Files.write(clientDir.resolve("config/foo.cfg"), "foo content".toByteArray())

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertEquals("bar content", Files.readString(clientDir.resolve("config/bar.cfg")))
        assertEquals(1, requests.get(),
            "only the missing file should be fetched -- the up-to-date one stays put")
    }

    // ── ProtectedPaths preservation ───────────────────────────────────────

    @Test
    fun `user-edited options_txt is NOT overwritten even when manifest claims a different MD5`() = runBlocking {
        // ProtectedPaths defaults gate options.txt -- the user's hand-tuned
        // settings must survive a sync that wants to push a different version
        // (e.g. server admin shipped a new default). The unit test
        // `isFileMissingOrChanged respects ProtectedPaths` covers the
        // predicate; this end-to-end check confirms the gate actually flows
        // through the sync path.
        val files = mapOf("config/options.txt" to "renderDistance:32\nfov:90".toByteArray())
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        // User has different settings on disk.
        val userOptions = clientDir.resolve("config/options.txt")
        Files.createDirectories(userOptions.parent)
        Files.writeString(userOptions, "renderDistance:8\nfov:70  # MY tuning")

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertEquals("renderDistance:8\nfov:70  # MY tuning", Files.readString(userOptions),
            "ProtectedPaths must shield options.txt from upstream overwrite")
        assertEquals(0, requests.get(), "no HTTP fetch should fire for a protected file")
    }

    // ── #184: cache must yield to disk reality ────────────────────────────

    @Test
    fun `disk-wipe between syncs forces re-download even when manifest-cache is fresh`() = runBlocking {
        // Reproduces the bug user hit on RPG: sync once successfully (cache
        // marks Industrial clean), then the client dir is gone (data-dir
        // move that left manifest-cache/ behind, manual rm, etc.). Pre-fix
        // the second processSession trusted the cache and short-circuited,
        // leaving an empty disk + a "clean" cache -> game launched with
        // empty classpath. The disk-sanity gate must catch this.
        val files = mapOf(
            "mods/foo.jar" to "foo bytes".toByteArray(),
            "mods/bar.jar" to "bar bytes".toByteArray(),
            "config/settings.cfg" to "key=value".toByteArray(),
        )
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)
        val firstSyncRequests = requests.get()

        // Wipe the client dir. The manifest-cache file at
        // <workDir>/manifest-cache/Industrial.json stays untouched.
        Files.walk(clientDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        Files.createDirectories(clientDir)

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        // All files must be back on disk with correct content.
        for ((relPath, expectedBytes) in files) {
            val onDisk = clientDir.resolve(relPath)
            assertTrue(Files.exists(onDisk), "missing after re-sync: $relPath")
            assertEquals(expectedBytes.toList(), Files.readAllBytes(onDisk).toList(),
                "byte mismatch on re-sync for $relPath")
        }
        // Re-sync should have re-downloaded each file -- disk-sanity gate
        // must invalidate the otherwise-clean cache.
        assertEquals(firstSyncRequests * 2, requests.get(),
            "post-wipe sync must refetch -- cache should NOT short-circuit on missing files")
    }

    // ── #203: single-file changes must invalidate the cache ─────────────

    @Test
    fun `single-file deletion outside top-20 forces re-download`() = runBlocking {
        // Pre-#203 the sanity gate sampled only the first 20 manifest entries.
        // A user-caused delete of file #25 (or beyond) passed the gate, the
        // cache was trusted, and Minecraft launched with a missing mod.
        // Sized check is intentional: build a manifest with >20 entries and
        // delete one beyond the top-20 to prove the walk now covers all entries.
        val files = buildMap {
            for (i in 1..30) put("mods/mod-$i.jar", "mod $i bytes".toByteArray())
        }
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)
        val firstSyncRequests = requests.get()

        // Delete one file that previously sat outside the sample window. The
        // exact name doesn't matter -- alphabetical ordering of HashMap is not
        // guaranteed, but a 30-entry manifest with a single removal guarantees
        // the sample (any 20 of 30) misses one entry in roughly a third of
        // hash orderings. We pick one explicitly so the test is deterministic.
        val victim = clientDir.resolve("mods/mod-25.jar")
        assertTrue(Files.exists(victim), "victim must be on disk before deletion")
        Files.delete(victim)

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertTrue(Files.exists(victim), "deleted file must be restored on re-sync")
        // The full integrity walk should have refetched the missing file (and
        // re-verified the others via MD5). At minimum, we expect more fetches
        // than zero -- pre-#203 this was zero because the cache short-circuit
        // covered the deletion.
        assertTrue(requests.get() > firstSyncRequests,
            "post-deletion sync must refetch -- cache must NOT short-circuit when any manifest entry is missing")
    }

    @Test
    fun `single-file truncation outside top-20 forces re-download`() = runBlocking {
        // Sibling to the deletion test: file present but corrupted (size
        // mismatch). The sanity gate compares stat().size to manifest.size,
        // so a truncated mod is detected without paying the MD5 walk cost.
        // Pre-#203 the file was present so exists() said true and the cache
        // was trusted; Minecraft loaded the truncated JAR and crashed with
        // NoClassDefFoundError.
        val files = buildMap {
            for (i in 1..30) put("mods/mod-$i.jar", "mod $i contents -- substantial bytes here".toByteArray())
        }
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)
        val firstSyncRequests = requests.get()

        // Corrupt one file by truncating to zero bytes. File still exists,
        // but its size no longer matches the manifest.
        val victim = clientDir.resolve("mods/mod-25.jar")
        val originalSize = Files.size(victim)
        Files.write(victim, ByteArray(0))
        assertTrue(Files.size(victim) < originalSize, "victim must be truncated")

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertEquals(originalSize, Files.size(victim),
            "truncated file must be restored to manifest size on re-sync")
        assertTrue(requests.get() > firstSyncRequests,
            "post-truncation sync must refetch -- cache must NOT short-circuit when size doesn't match manifest")
    }

    // ── Stale disabled jars in legacy location ────────────────────────────

    @Test
    fun `cleanup runs on every sync, including when the manifest cache short-circuits the integrity walk`() = runBlocking {
        // Scenario: SC's manifest used to place FoamFix at top-level
        // mods/FoamFix.jar; a later release moved it into a version
        // subdir (or dropped it entirely from the manifest when the user
        // disabled the optional). The user has the mod disabled. The
        // current manifest doesn't reference the top-level path, so the
        // integrity walk never inspects the legacy copy -- without an
        // unconditional cleanup pass, Forge happily loads the "disabled"
        // mod from the stale top-level path every launch.
        //
        // Critical: the manifest in this test must NOT contain the
        // disabled mod. If it did, the disk-sanity gate inside
        // ManifestCache.isClean would see the disabled (and therefore
        // absent-from-disk) manifest entry and return false on every
        // call, invalidating the cache and forcing the slow path on
        // every sync. Cleanup would then run via the slow path on its
        // own and the test would silently lose its "cache hot" claim.
        val keeperBytes = "still-here".toByteArray()
        val files = mapOf("mods/keeper.jar" to keeperBytes)
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        Files.createDirectories(clientDir.resolve("mods"))
        Files.write(clientDir.resolve("mods/FoamFix.jar"), "legacy-bytes".toByteArray())

        val ignored = setOf("FoamFix.jar")

        // First sync: cache cold. cleanup deletes the legacy jar, then the
        // integrity walk downloads keeper.jar, then the cache is marked
        // clean against the (keeper-only) manifest with this ignored set.
        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, ignored, null, null)
        assertTrue(!Files.exists(clientDir.resolve("mods/FoamFix.jar")),
            "stale top-level disabled jar must be removed on first sync")
        assertTrue(Files.exists(clientDir.resolve("mods/keeper.jar")),
            "non-ignored jar must be preserved")
        val requestsAfterFirstSync = requests.get()

        // Reintroduce the stale jar (simulating an external write between
        // launches), then sync again. Manifest hash and ignored set are
        // both unchanged from the previous run AND every manifest entry
        // is present on disk at the right size, so the cache short-circuit
        // fires: no HTTP requests should be made. The fix's whole point is
        // that cleanup still runs before that short-circuit, removing the
        // reintroduced stale jar even though the integrity walk gets
        // skipped.
        Files.write(clientDir.resolve("mods/FoamFix.jar"), "legacy-bytes-again".toByteArray())
        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, ignored, null, null)

        assertTrue(!Files.exists(clientDir.resolve("mods/FoamFix.jar")),
            "cleanup must run on the cache-hot path too; a reintroduced disabled jar must not survive a subsequent sync")
        assertEquals(requestsAfterFirstSync, requests.get(),
            "the second sync must hit the cache short-circuit (zero new HTTP requests); " +
                "if this fails the test silently exercised the slow path instead of the cache-hot path it claims to validate")
    }

    @Test
    fun `cleanup deletes disabled jar from version subdir alongside top-level`() = runBlocking {
        // The cleanup walk must hit both `mods/` directly and `mods/{mc}/`
        // -- some mods appear in both locations during the upstream
        // transition period, and missing either side would leave Forge
        // loading the disabled mod from whichever copy survived.
        val files = mapOf("mods/keeper.jar" to "keep me".toByteArray())
        val manifest = manifestOf(files)
        val (svc, _) = newService(files)

        Files.createDirectories(clientDir.resolve("mods/1.12.2"))
        Files.write(clientDir.resolve("mods/FoamFix.jar"), "top".toByteArray())
        Files.write(clientDir.resolve("mods/1.12.2/FoamFix.jar"), "sub".toByteArray())
        Files.write(clientDir.resolve("mods/keeper.jar"), files.values.first())

        svc.processSession(sessionWith(manifest), "Industrial", clientDir,
            null, setOf("FoamFix.jar"), null, null)

        assertTrue(!Files.exists(clientDir.resolve("mods/FoamFix.jar")),
            "top-level disabled jar must be removed")
        assertTrue(!Files.exists(clientDir.resolve("mods/1.12.2/FoamFix.jar")),
            "version-subdir disabled jar must be removed")
        assertTrue(Files.exists(clientDir.resolve("mods/keeper.jar")),
            "non-ignored jar must survive cleanup")
    }

    // ── Smarty swap: strict verification + helper injection ───────────────

    @Test
    fun `strict mod check deletes foreign jar, keeps manifest jar and injected helper`() = runBlocking {
        val files = mapOf("mods/keeper.jar" to "keep me".toByteArray())
        val manifest = manifestOf(files)
        val (svc, _) = newService(files)

        // A user-added jar the manifest never lists -- strict mode must remove it.
        Files.createDirectories(clientDir.resolve("mods"))
        Files.write(clientDir.resolve("mods/foreign.jar"), "i should not be here".toByteArray())

        // The open-smrt helper the launcher injects in Smarty's place.
        val helper = workDir / "helpers" / "open-smrt-network-1.12.jar"
        Files.createDirectories(helper.parent)
        Files.write(helper, "open-smrt bytes".toByteArray())

        svc.processSession(
            sessionWith(manifest), "Industrial", clientDir,
            null, null, null, null,
            injectModJar = helper, strictModCheck = true,
        )

        assertTrue(Files.exists(clientDir.resolve("mods/keeper.jar")),
            "manifest jar must survive strict check")
        assertTrue(!Files.exists(clientDir.resolve("mods/foreign.jar")),
            "foreign jar absent from manifest must be pruned")
        assertTrue(Files.exists(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "injected helper must be present and exempt from the strict prune")
        assertEquals("open-smrt bytes",
            Files.readString(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "injected helper bytes must match the resolved source")
    }

    @Test
    fun `without strict check a foreign jar survives while the helper still injects`() = runBlocking {
        val files = mapOf("mods/keeper.jar" to "keep me".toByteArray())
        val manifest = manifestOf(files)
        val (svc, _) = newService(files)

        Files.createDirectories(clientDir.resolve("mods"))
        Files.write(clientDir.resolve("mods/foreign.jar"), "left alone".toByteArray())

        val helper = workDir / "helpers" / "open-smrt-network-1.12.jar"
        Files.createDirectories(helper.parent)
        Files.write(helper, "open-smrt bytes".toByteArray())

        svc.processSession(
            sessionWith(manifest), "Industrial", clientDir,
            null, null, null, null,
            injectModJar = helper, strictModCheck = false,
        )

        assertTrue(Files.exists(clientDir.resolve("mods/foreign.jar")),
            "foreign jar must survive when strict check is off")
        assertTrue(Files.exists(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "helper must inject regardless of strict check")
    }

    @Test
    fun `helper injection is idempotent across re-sync`() = runBlocking {
        val files = mapOf("mods/keeper.jar" to "keep me".toByteArray())
        val manifest = manifestOf(files)
        val (svc, _) = newService(files)

        val helper = workDir / "helpers" / "open-smrt-network-1.12.jar"
        Files.createDirectories(helper.parent)
        Files.write(helper, "open-smrt bytes".toByteArray())

        repeat(2) {
            svc.processSession(
                sessionWith(manifest), "Industrial", clientDir,
                null, null, null, null,
                injectModJar = helper, strictModCheck = true,
            )
        }

        assertEquals("open-smrt bytes",
            Files.readString(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "second sync must leave the helper intact, not duplicate or corrupt it")
    }

    @Test
    fun `swapped Smarty in manifest still allows the cache to short-circuit`() = runBlocking {
        // The upstream manifest lists Smarty; we ignore it (swap) and the jar
        // never lands on disk. The disk-sanity walk must not treat that
        // deliberate absence as a reason to refetch on every launch -- otherwise
        // the default-on swap would kill the cache for every SmartyCraft server.
        val files = mapOf(
            "mods/keeper.jar" to "keep me".toByteArray(),
            "mods/Smarty-1.12.2.jar" to "surveillance".toByteArray(),
        )
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        val helper = workDir / "helpers" / "open-smrt-network-1.12.jar"
        Files.createDirectories(helper.parent)
        Files.write(helper, "open-smrt bytes".toByteArray())
        val ignored = setOf("Smarty-1.12.2.jar")

        svc.processSession(
            sessionWith(manifest), "Industrial", clientDir,
            null, ignored, null, null,
            injectModJar = helper, strictModCheck = true,
        )
        val afterFirst = requests.get()
        assertTrue(!Files.exists(clientDir.resolve("mods/Smarty-1.12.2.jar")),
            "Smarty must not be downloaded when swapped")
        assertTrue(Files.exists(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "helper present after first sync")

        svc.processSession(
            sessionWith(manifest), "Industrial", clientDir,
            null, ignored, null, null,
            injectModJar = helper, strictModCheck = true,
        )

        assertEquals(afterFirst, requests.get(),
            "second swapped sync must hit the cache -- zero new fetches")
        assertTrue(!Files.exists(clientDir.resolve("mods/Smarty-1.12.2.jar")),
            "Smarty stays gone on the cache-hot path")
        assertTrue(Files.exists(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "helper stays put on the cache-hot path")
    }

    @Test
    fun `strict prune keeps the helper by glob even when nothing is injected this launch`() = runBlocking {
        // The stability fix: a launch where the resolver couldn't refresh the
        // helper passes injectModJar = null, but helperKeepGlobs still protects
        // the on-disk helper from strict verification. Without it, strict mode
        // would delete the helper and the join would lose its network mod.
        val files = mapOf("mods/keeper.jar" to "keep me".toByteArray())
        val manifest = manifestOf(files)
        val (svc, _) = newService(files)

        Files.createDirectories(clientDir.resolve("mods"))
        Files.write(clientDir.resolve("mods/open-smrt-network-1.12.jar"), "helper bytes".toByteArray())
        Files.write(clientDir.resolve("mods/foreign.jar"), "delete me".toByteArray())

        svc.processSession(
            sessionWith(manifest), "Industrial", clientDir,
            null, null, null, null,
            injectModJar = null, strictModCheck = true,
            helperKeepGlobs = listOf("open-smrt-network*.jar"),
        )

        assertTrue(Files.exists(clientDir.resolve("mods/open-smrt-network-1.12.jar")),
            "helper must survive strict prune via the keep-glob with no injection this launch")
        assertTrue(!Files.exists(clientDir.resolve("mods/foreign.jar")),
            "foreign jar is still pruned")
        assertTrue(Files.exists(clientDir.resolve("mods/keeper.jar")),
            "manifest jar kept")
    }

    @Test
    fun `strict prune matches the manifest path, not the basename`() = runBlocking {
        // The manifest places Foo.jar in a version subdir. A stray top-level
        // mods/Foo.jar with the same basename is NOT what the server asked for
        // and Forge would load it as a duplicate -- strict verification must
        // prune it even though a jar of that name appears in the manifest.
        val files = mapOf("mods/1.12.2/Foo.jar" to "the real foo".toByteArray())
        val manifest = manifestOf(files)
        val (svc, _) = newService(files)

        Files.createDirectories(clientDir.resolve("mods"))
        Files.write(clientDir.resolve("mods/Foo.jar"), "stray duplicate".toByteArray())

        svc.processSession(
            sessionWith(manifest), "Industrial", clientDir,
            null, null, null, null,
            injectModJar = null, strictModCheck = true,
        )

        assertTrue(Files.exists(clientDir.resolve("mods/1.12.2/Foo.jar")),
            "the manifest jar at its declared path is kept")
        assertTrue(!Files.exists(clientDir.resolve("mods/Foo.jar")),
            "a same-name jar at a non-manifest path is pruned (no basename free pass)")
    }

    // ── Network failure ───────────────────────────────────────────────────

    @Test
    fun `processSession surfaces upstream HTTP failure as exception (no silent partial state)`() = runBlocking {
        val files = mapOf("mods/foo.jar" to "doesnt matter".toByteArray())
        val manifest = manifestOf(files)
        // Override: server returns 400 for everything. processSession should
        // throw, the user sees the failure, partial cache state must NOT be
        // marked clean (otherwise next launch would silently skip integrity
        // walk and the missing file would never be retried).
        val svc = newServiceFailing()

        assertFails {
            svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)
        }
        // Cache must NOT be marked clean -- verify by checking no cache file landed.
        // ManifestCache writes under `manifest-cache/Industrial.json`; absence
        // means the next launch will try again, which is the correct behavior.
        val cacheFile = workDir / "manifest-cache" / "Industrial.json"
        assertTrue(!Files.exists(cacheFile),
            "failed sync must NOT mark manifest as cleanly-synced")
    }

    // ── Hostile manifest ───────────────────────────────────────────────────

    @Test
    fun `a manifest entry climbing out of the instance writes nothing outside it`() = runBlocking {
        // The manifest is a document the server sends, so its paths are the
        // server's to choose. Nested `..` keys flatten to `mods/../../..`,
        // whose first segment still reads as a known root directory, and
        // Path.resolve does not normalise -- the write used to land wherever
        // the entry pointed. The realistic target is a startup file.
        val payload = "#!/bin/sh\necho pwned".toByteArray()
        val escaping = "mods/../../../pwned.sh"
        val (svc, _) = newService(mapOf(escaping to payload))

        assertFails {
            svc.processSession(
                session = sessionWith(manifestOf(mapOf(escaping to payload))),
                serverId = "Industrial",
                targetDir = clientDir,
                extraCheckSum = null,
                ignoredFiles = null,
                messageUI = null,
                progressUI = null,
            )
        }

        // clientDir is <workDir>/clients/Industrial, so three levels up is
        // workDir's parent -- outside the sandbox entirely. Assert against the
        // resolved location rather than trusting the exception alone.
        val escaped = clientDir.resolve(escaping).normalize()
        assertTrue(!Files.exists(escaped), "a manifest entry wrote outside the instance: $escaped")
    }

    @Test
    fun `an absolute manifest entry lands inside the instance, not at its own path`() = runBlocking {
        // Not a traversal on this platform, and worth pinning as such: the
        // leading empty segment is not a known root directory, so normalizePath
        // strips it as a server-name prefix and what remains is relative. The
        // boundary check still stands behind that for the shapes it does not
        // neutralise -- a Windows drive-qualified path splits into one segment
        // and stays absolute. Refusal itself is covered in PathBoundaryTest.
        val marker = workDir / "absolute-escape.txt"
        val payload = "x".toByteArray()
        val entry = marker.toString()
        val manifest = FileManifest(files = mapOf(entry to FileData(md5 = md5Hex(payload), size = payload.size.toLong())))
        val (svc, _) = newService(mapOf(entry to payload))

        runCatching {
            svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)
        }

        assertTrue(!Files.exists(marker), "an absolute entry wrote to its own path")
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * @return svc + a counter that increments per HTTP request to the mock.
     *         The counter lets tests assert the cache short-circuit empirically
     *         instead of poking ManifestCache state.
     */
    private fun newService(files: Map<String, ByteArray>): Pair<FileDownloadService, AtomicInteger> {
        val counter = AtomicInteger(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    counter.incrementAndGet()
                    val path = request.url.encodedPath
                    val match = files.entries.firstOrNull { (relPath, _) ->
                        path.endsWith("/$relPath") || path.endsWith(relPath)
                    }
                    if (match != null) {
                        respond(
                            content = ByteReadChannel(match.value),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/octet-stream"),
                        )
                    } else {
                        respondBadRequest()
                    }
                }
            }
        }
        return service(HttpClientProvider { client }) to counter
    }

    private fun newServiceFailing(): FileDownloadService {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel.Empty,
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf("Content-Type", "text/plain"),
                    )
                }
            }
        }
        return service(HttpClientProvider { client })
    }

    private fun service(provider: HttpClientProvider): FileDownloadService {
        val protectedPaths = ProtectedPaths(workDir / "protected-paths.json", json)
        val manifestCache = ManifestCache(workDir / "manifest-cache", json)
        // Default config -- clientFilesBase resolves to a fake URL but the
        // MockEngine matches by path-suffix so the host is irrelevant.
        return FileDownloadService(provider, protectedPaths, manifestCache, ServerProtocolConfig())
    }

    private fun sessionWith(manifest: FileManifest) = SessionData(
        playerName = "TestUser",
        accessToken = "fake-token",
        uuid = "00000000-0000-0000-0000-000000000000",
        uid = "1",
        fileManifest = manifest,
    )

    /**
     * Builds a flat FileManifest from a path -> bytes map, computing each
     * entry's real MD5 so the integrity gate matches downloaded content.
     * Splits paths into directory components so the manifest mirrors what
     * the server returns (`{"directories": {"mods": {"files": {...}}}}`)
     * -- flat `files` would also work but the recursive shape catches more
     * regressions (the flatten code path).
     */
    private fun manifestOf(files: Map<String, ByteArray>): FileManifest {
        // Group path -> FileData into a tree keyed on the path components.
        data class Node(
            val files: MutableMap<String, FileData> = mutableMapOf(),
            val dirs: MutableMap<String, Node> = mutableMapOf(),
        )
        val root = Node()
        for ((path, bytes) in files) {
            val parts = path.split('/')
            var here = root
            for (i in 0 until parts.size - 1) {
                here = here.dirs.getOrPut(parts[i]) { Node() }
            }
            here.files[parts.last()] = FileData(md5 = md5Hex(bytes), size = bytes.size.toLong())
        }
        fun toManifest(n: Node): FileManifest = FileManifest(
            files = n.files,
            directories = n.dirs.mapValues { toManifest(it.value) },
        )
        return toManifest(root)
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
