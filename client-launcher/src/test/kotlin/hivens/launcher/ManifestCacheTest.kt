package hivens.launcher

import hivens.core.data.FileData
import hivens.core.data.FileManifest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestCacheTest {

    private lateinit var cacheDir: Path
    private lateinit var cache: ManifestCache
    private val json = Json { encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        cacheDir = Files.createTempDirectory("manifest-cache-test")
        cacheDir.toFile().deleteOnExit()
        cache = ManifestCache(cacheDir, json)
    }

    @AfterTest
    fun tearDown() {
        cacheDir.toFile().deleteRecursively()
    }

    @Test
    fun `isClean returns false on a fresh cache`() {
        assertFalse(cache.isClean("Industrial", "abc"))
    }

    @Test
    fun `markClean then isClean with matching hash returns true`() {
        cache.markClean("Industrial", "abc123")
        assertTrue(cache.isClean("Industrial", "abc123"))
    }

    @Test
    fun `isClean returns false when hash differs`() {
        cache.markClean("Industrial", "abc123")
        assertFalse(cache.isClean("Industrial", "different"))
    }

    @Test
    fun `cache is per-server — Industrial mark doesn't satisfy Create check`() {
        cache.markClean("Industrial", "abc123")
        assertFalse(cache.isClean("Create", "abc123"))
    }

    @Test
    fun `invalidate drops the cached entry`() {
        cache.markClean("Industrial", "abc")
        assertTrue(cache.isClean("Industrial", "abc"))
        cache.invalidate("Industrial")
        assertFalse(cache.isClean("Industrial", "abc"))
    }

    @Test
    fun `hashOf is deterministic for identical input`() {
        val a = cache.hashOf("""{"foo":"bar"}""")
        val b = cache.hashOf("""{"foo":"bar"}""")
        assertEquals(a, b)
    }

    @Test
    fun `hashOf differs for different input`() {
        val a = cache.hashOf("""{"foo":"bar"}""")
        val b = cache.hashOf("""{"foo":"baz"}""")
        assertFalse(a == b)
    }

    @Test
    fun `serverId with traversal characters is sanitised`() {
        // Defensive — server id is from upstream config but we still don't
        // want a malicious value to escape the cache dir.
        cache.markClean("../etc/passwd", "abc")
        // Cache file lands in cacheDir, not above it. We can't directly
        // observe the sanitised filename without opening implementation
        // details, but we can confirm the file shows up under cacheDir:
        val files = Files.list(cacheDir).use { it.toList() }
        assertEquals(1, files.size)
        assertTrue(files[0].parent == cacheDir, "sanitised file must stay inside cacheDir")
    }

    @Test
    fun `expired entry returns false even when hash matches`() {
        // Manually write an entry with synced_at way in the past.
        Files.createDirectories(cacheDir)
        val entry = """{"hash":"abc","syncedAt":1000}"""  // year 1970
        Files.writeString(cacheDir.resolve("Industrial.json"), entry)
        assertFalse(
            cache.isClean("Industrial", "abc"),
            "entry older than TTL_MS must be treated as a cache miss"
        )
    }

    @Test
    fun `loadManifest returns null on a fresh cache`() {
        assertNull(cache.loadManifest("Industrial"))
    }

    @Test
    fun `loadManifest returns null when entry was written without manifest content`() {
        // 2.2.9 entries (hash + timestamp only) must remain readable but
        // surface as "no offline data" rather than crashing the offline path.
        cache.markClean("Industrial", "abc123")
        assertNull(cache.loadManifest("Industrial"))
    }

    @Test
    fun `markClean with manifest persists content for loadManifest`() {
        val sample = FileManifest(
            files = mapOf("a.jar" to FileData(md5 = "deadbeef", size = 100)),
            directories = mapOf(
                "libs" to FileManifest(files = mapOf("b.jar" to FileData(md5 = "cafef00d", size = 200))),
            ),
        )
        cache.markClean("Industrial", "abc123", sample)
        val loaded = cache.loadManifest("Industrial")
        assertNotNull(loaded)
        assertEquals(sample, loaded)
    }

    @Test
    fun `loadManifest ignores TTL — stale-but-present manifest is still returned`() {
        // Offline-launch fallback intentionally serves expired entries:
        // a stale file list is strictly better than launching with empty
        // classpath (which is what triggered the original bug).
        Files.createDirectories(cacheDir)
        val ancient = """{"hash":"abc","syncedAt":1000,"manifest":{"directories":{},"files":{"x.jar":{"md5":"abc","size":1}}}}"""
        Files.writeString(cacheDir.resolve("Industrial.json"), ancient)
        val loaded = cache.loadManifest("Industrial")
        assertNotNull(loaded)
        assertEquals(1, loaded.files.size)
    }

    // ── Disk-sanity gate (#184) ────────────────────────────────────────────

    @Test
    fun `isClean invalidates entry when diskSanityCheck returns false`() {
        // Hash matches and the entry is fresh, but the caller knows the
        // disk lost the files (data dir moved without the cache, manual
        // rm, partial restore). The cache must yield to disk reality.
        cache.markClean("Industrial", "abc")
        assertFalse(
            cache.isClean("Industrial", "abc") { false },
            "diskSanityCheck=false must override an otherwise-valid cache",
        )
    }

    @Test
    fun `isClean honours diskSanityCheck even on the same call shape`() {
        // The lambda is the only thing different — must flip the result.
        cache.markClean("Industrial", "abc")
        assertTrue(cache.isClean("Industrial", "abc") { true })
        assertFalse(cache.isClean("Industrial", "abc") { false })
    }

    @Test
    fun `diskSanityCheck is NOT consulted when the hash gate already failed`() {
        // Optimization the production code relies on — the lambda may walk
        // the filesystem; don't bother running it when we've already
        // decided to refresh because of a hash mismatch.
        cache.markClean("Industrial", "abc")
        var called = false
        val result = cache.isClean("Industrial", "different-hash") {
            called = true
            true
        }
        assertFalse(result)
        assertFalse(called, "hash mismatch should short-circuit before disk-sanity work")
    }
}
