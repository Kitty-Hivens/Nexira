package hivens.launcher

import hivens.core.data.ContentToggle
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

class JsonPackRepositoryTest {

    private lateinit var tmpDir: Path
    private lateinit var file: Path
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("packs-repo-test")
        tmpDir.toFile().deleteOnExit()
        file = tmpDir.resolve("packs.json")
    }

    @AfterTest
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `missing file -- repo starts empty without raising`() = runBlocking {
        assertFalse(Files.exists(file))
        val repo = JsonPackRepository(file, json)
        assertEquals(emptyList(), repo.list())
        assertEquals(emptyList(), repo.observe().first())
    }

    @Test
    fun `put persists the instance and survives a reload`() = runBlocking {
        val repo = JsonPackRepository(file, json)
        val instance = sampleInstance(id = "id-1", name = "Industrial")
        repo.put(instance)

        // Same JVM, fresh repo over the same file: instance is loaded.
        val reloaded = JsonPackRepository(file, json)
        assertEquals(listOf(instance), reloaded.list())
        assertEquals(instance, reloaded.get("id-1"))
    }

    @Test
    fun `put with an existing id replaces, does not duplicate`() = runBlocking {
        val repo = JsonPackRepository(file, json)
        repo.put(sampleInstance(id = "id-1", name = "Old name"))
        repo.put(sampleInstance(id = "id-1", name = "New name"))
        val all = repo.list()
        assertEquals(1, all.size)
        assertEquals("New name", all[0].displayName)
    }

    @Test
    fun `delete removes the instance and persists the removal`() = runBlocking {
        val repo = JsonPackRepository(file, json)
        repo.put(sampleInstance(id = "id-1", name = "A"))
        repo.put(sampleInstance(id = "id-2", name = "B"))
        repo.delete("id-1")

        val reloaded = JsonPackRepository(file, json)
        val ids = reloaded.list().map { it.id }
        assertEquals(listOf("id-2"), ids)
        assertNull(reloaded.get("id-1"))
        assertNotNull(reloaded.get("id-2"))
    }

    @Test
    fun `delete of unknown id is a no-op`() = runBlocking {
        val repo = JsonPackRepository(file, json)
        repo.put(sampleInstance(id = "id-1", name = "A"))
        repo.delete("nope")
        assertEquals(1, repo.list().size)
    }

    @Test
    fun `corrupt file -- repo starts empty rather than crashing`() = runBlocking {
        Files.writeString(file, "{this is not valid json")
        val repo = JsonPackRepository(file, json)
        // Library would see empty rather than the launcher dying on a
        // bad packs.json. Persistence of subsequent writes still
        // works (they overwrite the bad file).
        assertEquals(emptyList(), repo.list())
        repo.put(sampleInstance(id = "id-1", name = "Fresh"))
        val reloaded = JsonPackRepository(file, json)
        assertEquals(1, reloaded.list().size)
    }

    @Test
    fun `observe re-emits after put and delete`() = runBlocking {
        val repo = JsonPackRepository(file, json)
        val flow = repo.observe()
        assertEquals(emptyList(), flow.first())

        repo.put(sampleInstance(id = "id-1", name = "A"))
        assertEquals(1, flow.first().size)

        repo.delete("id-1")
        assertEquals(0, flow.first().size)
    }

    @Test
    fun `persisted file is wrapped in versioned envelope`() = runBlocking {
        val repo = JsonPackRepository(file, json)
        repo.put(sampleInstance(id = "id-1", name = "A"))
        val text = Files.readString(file)
        assertTrue("schema_version" in text, "expected schema_version envelope in $text")
        assertTrue("instances"      in text, "expected instances array in $text")
    }

    private fun sampleInstance(id: String, name: String) = PackInstance(
        id              = id,
        packRef         = PackReference(origin = PackOrigin.Mirror, id = "Industrial", version = "2026.05.23"),
        displayName     = name,
        instanceDirName = "Industrial-$id",
        createdAtEpoch  = 1_700_000_000L,
        lastPlayedEpochOrZero = 0L,
        pinnedPackVersion     = null,
        runtime               = InstanceRuntime(),
        optionalContent       = emptyList<ContentToggle>(),
        forkedFrom            = null,
        notes                 = "",
    )
}
