package hivens.launcher.cache

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.cache.CacheConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The network and the disk are asked once per thing that can change, and a
 * failure is never remembered as "no icon".
 */
class ModIconLookupsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: Path = Files.createTempDirectory("icon-lookups")
    private val factory = CacheFactory(dir, Json, scope)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        scope.cancel()
        runCatching { dir.deleteRecursively() }
    }

    private fun caches() = ModIconCaches(
        byHash = factory.createInMemory("h", CacheConfig(ttlMs = Long.MAX_VALUE)),
        byProject = factory.createInMemory("p", CacheConfig(ttlMs = Long.MAX_VALUE)),
        fileHash = factory.createInMemory("f", CacheConfig(ttlMs = Long.MAX_VALUE)),
    )

    private fun version(projectId: String) = ModrinthVersion(id = "v1", projectId = projectId, files = emptyList())

    @Test
    fun `a hash is asked about once, and so is the project it names`() = runBlocking {
        var byHash = 0
        var byProject = 0
        val lookups = ModIconLookups(
            caches(),
            versionByHash = { byHash++; version("proj") },
            projectIcon = { byProject++; "https://cdn/proj.png" },
        )
        repeat(3) { assertEquals("https://cdn/proj.png", lookups.iconForHash("abc")) }
        assertEquals(1, byHash)
        assertEquals(1, byProject)
    }

    @Test
    fun `a file the catalogue does not know is remembered as having no icon`() = runBlocking {
        var calls = 0
        val lookups = ModIconLookups(caches(), versionByHash = { calls++; null }, projectIcon = { "unused" })
        assertNull(lookups.iconForHash("unknown"))
        assertNull(lookups.iconForHash("unknown"))
        assertEquals(1, calls)
    }

    @Test
    fun `a failed lookup is not remembered`() = runBlocking {
        var calls = 0
        val lookups = ModIconLookups(
            caches(),
            versionByHash = { calls++; if (calls == 1) error("rate limited") else version("p") },
            projectIcon = { "https://cdn/p.png" },
        )
        assertFailsWith<IllegalStateException> { lookups.iconForHash("abc") }
        assertEquals("https://cdn/p.png", lookups.iconForHash("abc"))
    }

    @Test
    fun `a file is hashed again only when it changes`() = runBlocking {
        val lookups = ModIconLookups(caches(), versionByHash = { null }, projectIcon = { null })
        val jar = Files.createTempFile(dir, "mod", ".jar")
        Files.write(jar, byteArrayOf(1, 2, 3))
        val first = lookups.sha1(jar)

        // Same bytes are read once: replace them behind the cache's back while
        // keeping size and time, and the remembered hash is still what comes back.
        val time = Files.getLastModifiedTime(jar)
        Files.write(jar, byteArrayOf(9, 9, 9))
        Files.setLastModifiedTime(jar, time)
        assertEquals(first, lookups.sha1(jar))

        // A real change moves the time, and the file is read again.
        Files.setLastModifiedTime(jar, FileTime.fromMillis(time.toMillis() + 5_000))
        val changed = lookups.sha1(jar)
        assertEquals(40, changed?.length)
        assertNotEquals(first, changed)
    }

    @Test
    fun `a file that cannot be read has no hash`() = runBlocking {
        val lookups = ModIconLookups(caches(), versionByHash = { null }, projectIcon = { null })
        assertNull(lookups.sha1(dir.resolve("missing.jar")))
    }
}
