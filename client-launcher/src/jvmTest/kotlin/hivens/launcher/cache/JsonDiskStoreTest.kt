package hivens.launcher.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonDiskStoreTest {

    @Serializable
    private data class Dto(val id: String, val n: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var dir: Path
    private lateinit var store: JsonDiskStore<Dto>

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("json-disk-store-test-")
        store = JsonDiskStore(dir.resolve("ns"), Dto.serializer(), json)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun onlyJsonFile(): Path =
        Files.list(dir.resolve("ns")).use { s -> s.filter { it.toString().endsWith(".json") }.findFirst().get() }

    @Test
    fun `write then read round-trips with the stored timestamp`() {
        store.write("https://x/v1/packs", Dto("a", 7), storedAtMillis = 1234)
        val got = store.read("https://x/v1/packs")
        assertEquals(Dto("a", 7), got?.value)
        assertEquals(1234, got?.storedAtMillis)
    }

    @Test
    fun `read of a missing key returns null`() {
        assertNull(store.read("nope"))
    }

    @Test
    fun `read of a missing directory returns null, not a crash`() {
        val fresh = JsonDiskStore(dir.resolve("never-created"), Dto.serializer(), json)
        assertNull(fresh.read("k"))
    }

    @Test
    fun `corrupt entry returns null and self-heals by deleting the file`() {
        store.write("k", Dto("a", 1), 1)
        val file = onlyJsonFile()
        Files.writeString(file, "{ this is not valid json")
        assertNull(store.read("k"), "corrupt entry must read as a miss")
        assertTrue(!Files.exists(file), "corrupt file must be deleted so it doesn't re-fail")
    }

    @Test
    fun `wrong schema version reads as a miss and is deleted`() {
        store.write("k", Dto("a", 1), 1)
        val file = onlyJsonFile()
        Files.writeString(file, """{"schema_version":999,"key":"k","stored_at":1,"value":{"id":"a","n":1}}""")
        assertNull(store.read("k"))
        assertTrue(!Files.exists(file))
    }

    @Test
    fun `distinct keys map to distinct files and do not collide`() {
        store.write("https://x/v1/packs/aaa", Dto("aaa", 1), 1)
        store.write("https://x/v1/packs/bbb", Dto("bbb", 2), 2)
        assertEquals(Dto("aaa", 1), store.read("https://x/v1/packs/aaa")?.value)
        assertEquals(Dto("bbb", 2), store.read("https://x/v1/packs/bbb")?.value)
        val count = Files.list(dir.resolve("ns")).use { it.count() }
        assertEquals(2, count, "two keys -> two files")
    }

    @Test
    fun `a very long url key does not blow PATH_MAX`() {
        val longKey = "https://x/v1/packs/" + "a".repeat(5_000)
        store.write(longKey, Dto("long", 1), 1)
        assertEquals(Dto("long", 1), store.read(longKey)?.value)
    }

    @Test
    fun `delete and clear remove entries`() {
        store.write("k1", Dto("a", 1), 1)
        store.write("k2", Dto("b", 2), 2)
        store.delete("k1")
        assertNull(store.read("k1"))
        assertEquals(Dto("b", 2), store.read("k2")?.value)
        store.clear()
        assertNull(store.read("k2"))
    }
}
