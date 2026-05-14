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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * this harness covers the orchestration that ties them together — which has
 * surface for regressions that no helper test could catch.
 *
 * Each test runs in a fresh tempdir. HTTP is mocked at the
 * [io.ktor.client.HttpClient] boundary so we can both serve canned bytes
 * AND assert how many requests were made (the cache short-circuit is
 * verified by call count, not by guessing through state).
 *
 * Per the user's explicit ask: "tests for managing builds — how files
 * download, parse, what should happen in incorrect cases. Should run
 * exclusively on dev machine because files will be guaranteed broken."
 * Tempdir per test enforces that — broken-on-purpose state never escapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileDownloadDiskIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private lateinit var workDir: Path
    private lateinit var clientDir: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-fds-disk-")
        clientDir = workDir / "clients" / "Industrial"
        Files.createDirectories(clientDir)
        // FileDownloadService spawns a progress-UI ticker on Dispatchers.Main
        // unconditionally (the lambda check is null-safe but the launch isn't
        // gated). Use Unconfined so the job runs inline on whatever thread —
        // a TestDispatcher would never auto-advance the monitor's delay() loop
        // and the production `coroutineScope { }` would block forever waiting
        // on the monitor cancellation.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
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
        assertEquals(1, requests.get(), "exactly one refetch — the only stale file")
    }

    @Test
    fun `missing local file is downloaded`() = runBlocking {
        val files = mapOf(
            "mods/foo.jar" to "foo".toByteArray(),
            "mods/bar.jar" to "bar".toByteArray(),
        )
        val manifest = manifestOf(files)
        val (svc, requests) = newService(files)

        // Pre-populate one of two — the other is missing.
        Files.createDirectories(clientDir.resolve("mods"))
        Files.write(clientDir.resolve("mods/foo.jar"), "foo".toByteArray())

        svc.processSession(sessionWith(manifest), "Industrial", clientDir, null, null, null, null)

        assertEquals("bar", Files.readString(clientDir.resolve("mods/bar.jar")))
        assertEquals(1, requests.get(),
            "only the missing file should be fetched — the up-to-date one stays put")
    }

    // ── ProtectedPaths preservation ───────────────────────────────────────

    @Test
    fun `user-edited options_txt is NOT overwritten even when manifest claims a different MD5`() = runBlocking {
        // ProtectedPaths defaults gate options.txt — the user's hand-tuned
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
        // leaving an empty disk + a "clean" cache → game launched with
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
        // Re-sync should have re-downloaded each file — disk-sanity gate
        // must invalidate the otherwise-clean cache.
        assertEquals(firstSyncRequests * 2, requests.get(),
            "post-wipe sync must refetch — cache should NOT short-circuit on missing files")
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
        // Cache must NOT be marked clean — verify by checking no cache file landed.
        // ManifestCache writes under `manifest-cache/Industrial.json`; absence
        // means the next launch will try again, which is the correct behavior.
        val cacheFile = workDir / "manifest-cache" / "Industrial.json"
        assertTrue(!Files.exists(cacheFile),
            "failed sync must NOT mark manifest as cleanly-synced")
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
        // Default config — clientFilesBase resolves to a fake URL but the
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
     * Builds a flat FileManifest from a path → bytes map, computing each
     * entry's real MD5 so the integrity gate matches downloaded content.
     * Splits paths into directory components so the manifest mirrors what
     * the server returns (`{"directories": {"mods": {"files": {...}}}}`)
     * — flat `files` would also work but the recursive shape catches more
     * regressions (the flatten code path).
     */
    private fun manifestOf(files: Map<String, ByteArray>): FileManifest {
        // Group path → FileData into a tree keyed on the path components.
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
