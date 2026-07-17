package hivens.launcher

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XodusPackRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repos = mutableListOf<XodusPackRepository>()
    private val dirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        repos.forEach { it.close() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempData() = Files.createTempDirectory("registry").also { dirs.add(it) }

    private fun repo(dataDir: Path) =
        XodusPackRepository(dataDir.resolve("db"), dataDir.resolve("packs.json"), json).also { repos.add(it) }

    private fun instance(id: String) = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Mirror, "pack", "2026.01.01"),
        displayName = id,
        instanceDirName = id,
        createdAtEpoch = 0L,
    )

    @Test
    fun `put get list delete round-trip`() = runTest {
        val r = repo(tempData())
        r.put(instance("a"))
        r.put(instance("b"))
        assertEquals("a", r.get("a")?.id)
        assertEquals(setOf("a", "b"), r.list().map { it.id }.toSet())
        r.delete("a")
        assertNull(r.get("a"))
        assertEquals(listOf("b"), r.list().map { it.id })
    }

    @Test
    fun `observe emits current state`() = runTest {
        val r = repo(tempData())
        r.put(instance("x"))
        assertEquals(listOf("x"), r.observe().first().map { it.id })
    }

    @Test
    fun `data survives a reopen`() = runTest {
        val d = tempData()
        repo(d).put(instance("keep"))
        repos.first().close() // release the db lock before reopening the same dir
        val reopened = XodusPackRepository(d.resolve("db"), d.resolve("packs.json"), json).also { repos.add(it) }
        assertEquals(listOf("keep"), reopened.list().map { it.id })
    }

    @Test
    fun `migrates a legacy packs json on first open`() = runTest {
        val d = tempData()
        Files.writeString(
            d.resolve("packs.json"),
            """{"schema_version":1,"instances":[${json.encodeToString(PackInstance.serializer(), instance("old"))}]}""",
        )
        val r = repo(d)
        assertEquals(listOf("old"), r.list().map { it.id })
        assertTrue(Files.exists(d.resolve("packs.json.migrated")))
        assertTrue(!Files.exists(d.resolve("packs.json")))
    }

    @Test
    fun `an unreadable packs json is not marked migrated and retries next launch`() = runTest {
        val d = tempData()
        Files.writeString(d.resolve("packs.json"), "{ not valid json")
        val r1 = repo(d)
        assertTrue(r1.list().isEmpty())
        assertTrue(Files.exists(d.resolve("packs.json")))          // kept, NOT renamed
        assertTrue(!Files.exists(d.resolve("packs.json.migrated")))
        r1.close()

        // Repaired file: the retry migrates it instead of having lost the data.
        Files.writeString(
            d.resolve("packs.json"),
            """{"schema_version":1,"instances":[${json.encodeToString(PackInstance.serializer(), instance("recovered"))}]}""",
        )
        val r2 = XodusPackRepository(d.resolve("db"), d.resolve("packs.json"), json).also { repos.add(it) }
        assertEquals(listOf("recovered"), r2.list().map { it.id })
        assertTrue(Files.exists(d.resolve("packs.json.migrated")))
    }

    @Test
    fun `a failed write rolls back the in-memory state`() = runTest {
        val r = repo(tempData())
        r.put(instance("a"))
        r.close() // closing the env makes the next write throw
        r.put(instance("b"))
        assertNull(r.get("b"))
        assertEquals(listOf("a"), r.list().map { it.id })
    }
}
