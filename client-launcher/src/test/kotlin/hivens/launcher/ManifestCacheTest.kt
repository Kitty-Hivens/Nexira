package hivens.launcher

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
