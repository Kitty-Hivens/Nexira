package hivens.launcher

import hivens.core.api.model.ServerProfile
import hivens.core.api.model.ServerSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonServerListCacheStoreTest {

    private lateinit var tmpDir: Path
    private lateinit var file: Path
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("servers-cache-test")
        tmpDir.toFile().deleteOnExit()
        file = tmpDir.resolve("servers-cache.json")
    }

    @AfterTest
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `missing file -- load returns empty without raising`() {
        assertFalse(Files.exists(file))
        val store = JsonServerListCacheStore(file, json)
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun `save then reload returns the same servers`() = runBlocking {
        val store = JsonServerListCacheStore(file, json)
        val servers = listOf(
            sampleServer("industrial", "Industrial"),
            sampleServer("survival", "Survival"),
        )
        store.save(servers)

        val reloaded = JsonServerListCacheStore(file, json)
        assertEquals(servers, reloaded.load())
    }

    @Test
    fun `save replaces previous contents, does not append`() = runBlocking {
        val store = JsonServerListCacheStore(file, json)
        store.save(listOf(sampleServer("a", "Alpha")))
        store.save(listOf(sampleServer("b", "Beta")))

        val reloaded = JsonServerListCacheStore(file, json).load()
        assertEquals(1, reloaded.size)
        assertEquals("b", reloaded[0].name)
    }

    @Test
    fun `save with empty list persists empty`() = runBlocking {
        val store = JsonServerListCacheStore(file, json)
        store.save(listOf(sampleServer("a", "Alpha")))
        store.save(emptyList())

        // Production code never calls save() with empty -- the call site
        // guards on `if (servers.isNotEmpty())` so a transient outage
        // does not wipe the cache. This test pins the contract that the
        // store itself does NOT add a defensive empty-guard; the guard
        // belongs to the caller. If a future caller wants to wipe the
        // cache, save(emptyList()) is the way.
        val reloaded = JsonServerListCacheStore(file, json).load()
        assertEquals(emptyList(), reloaded)
    }

    @Test
    fun `corrupt file -- load returns empty rather than crashing`() {
        Files.writeString(file, "{this is not valid json")
        val store = JsonServerListCacheStore(file, json)
        // Loud failure here would block tray init at startup. The cache
        // recoverably degrades to "no seed" instead -- the live fetch
        // still runs and the cache rewrites on success.
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun `corrupt file is overwritten on next successful save`() = runBlocking {
        Files.writeString(file, "{garbage")
        val store = JsonServerListCacheStore(file, json)
        store.save(listOf(sampleServer("a", "Alpha")))

        val reloaded = JsonServerListCacheStore(file, json).load()
        assertEquals(1, reloaded.size)
    }

    @Test
    fun `atomic write does not leave a tmp sibling behind`() = runBlocking {
        val store = JsonServerListCacheStore(file, json)
        store.save(listOf(sampleServer("a", "Alpha")))

        val tmp = file.resolveSibling("${file.fileName}.tmp")
        assertFalse(Files.exists(tmp), "tmp file should be moved into place, not left behind")
    }

    @Test
    fun `persisted file is wrapped in versioned envelope`() = runBlocking {
        val store = JsonServerListCacheStore(file, json)
        store.save(listOf(sampleServer("a", "Alpha")))
        val text = Files.readString(file)
        assertTrue("schema_version" in text, "expected schema_version envelope in $text")
        assertTrue("servers" in text, "expected servers array in $text")
    }

    @Test
    fun `save creates parent directories if absent`() = runBlocking {
        val nested = tmpDir.resolve("nested/deep/servers-cache.json")
        val store = JsonServerListCacheStore(nested, json)
        store.save(listOf(sampleServer("a", "Alpha")))
        assertTrue(Files.exists(nested))
    }

    @Test
    fun `NoOp load returns empty and save is a no-op`() = runBlocking {
        val noop = ServerListCacheStore.NoOp
        assertEquals(emptyList(), noop.load())
        noop.save(listOf(sampleServer("a", "Alpha")))
        assertEquals(emptyList(), noop.load())
    }

    private fun sampleServer(id: String, title: String) = ServerProfile(
        name      = id,
        title     = title,
        version   = "1.12.2",
        ip        = "127.0.0.1",
        port      = 25566,
        assetDir  = id,
        source    = ServerSource.Smartycraft,
    )
}
