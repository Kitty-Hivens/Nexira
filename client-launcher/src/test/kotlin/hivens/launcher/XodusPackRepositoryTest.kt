package hivens.launcher

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `a reference that names no pack never reaches the registry`() = runTest {
        val r = repo(tempData())
        r.put(instance("a"))
        val dangling = instance("b").copy(packRef = PackReference(PackOrigin.Mirror, ""))
        assertFailsWith<IllegalArgumentException> { r.put(dangling) }
        assertNull(r.get("b"))
        assertEquals(listOf("a"), r.list().map { it.id })
    }

    @Test
    fun `a blank version written by an older build loads as floating`() = runTest {
        val d = tempData()
        val stored = instance("legacy").copy(
            packRef = PackReference(PackOrigin.Mirror, "pack", ""),
            pinnedPackVersion = "",
        )
        Files.writeString(
            d.resolve("packs.json"),
            """{"schema_version":1,"instances":[${json.encodeToString(PackInstance.serializer(), stored)}]}""",
        )
        val loaded = repo(d).get("legacy")!!
        assertNull(loaded.packRef.version)
        assertNull(loaded.pinnedPackVersion)
        // Repaired on the way in, so a later write of the same instance is accepted.
        repos.first().put(loaded)
        assertEquals(listOf("legacy"), repos.first().list().map { it.id })
    }

    /**
     * Xodus locks the directory for one process, and the registry used to take that
     * lock when it was built and keep it. One that holds it per operation leaves the
     * database free between them, which is what lets the launcher start while a
     * command-line launch is running.
     */
    @Test
    fun `a registry that holds the database per operation leaves it free between them`() = runTest {
        val d = tempData()
        val transient = XodusPackRepository(d.resolve("db"), d.resolve("packs.json"), json, holdOpen = false).also { repos.add(it) }
        transient.put(instance("a"))

        val other = repo(d)

        assertEquals(listOf("a"), other.list().map { it.id })
    }

    /** Built is not opened: a registry nobody has read takes no lock. */
    @Test
    fun `building a registry does not open its database`() = runTest {
        val d = tempData()
        repo(d)
        val reader = repo(d)

        assertEquals(emptyList(), reader.list())
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

    @Test
    fun `an update lands on the record as it is, not as the caller last read it`() = runTest {
        val r = repo(tempData())
        r.put(instance("a"))
        val staleRead = r.get("a")!!
        // Another writer records playtime after the settings window read the record.
        r.put(staleRead.copy(playtimeSeconds = 600))

        r.update("a") { it.copy(notes = "edited") }

        val stored = r.get("a")!!
        assertEquals("edited", stored.notes)
        assertEquals(600, stored.playtimeSeconds, "the edit carried back the playtime of an older read")
    }

    @Test
    fun `concurrent updates each see the one before`() = runTest {
        val r = repo(tempData())
        r.put(instance("a"))

        withContext(Dispatchers.Default) {
            (1..64).map { async { r.update("a") { it.copy(playtimeSeconds = it.playtimeSeconds + 1) } } }.awaitAll()
        }

        assertEquals(64, r.get("a")!!.playtimeSeconds)
    }

    @Test
    fun `an update of an instance that is not installed writes nothing`() = runTest {
        val r = repo(tempData())
        assertNull(r.update("ghost") { it.copy(notes = "x") })
        assertTrue(r.list().isEmpty())
    }

    // A database made before a schema bump, opened by the build after it, then by the
    // build before it again. The stamp has to move, or the older build never learns
    // it is looking at newer data and writes its own shape over it.
    @Test
    fun `a schema bump is stamped on an existing database and an older build reads it read-only`() = runTest {
        val d = tempData()
        fun at(version: Int) =
            XodusPackRepository(d.resolve("db"), d.resolve("packs.json"), json, schemaVersion = version).also { repos.add(it) }

        at(1).apply { put(instance("a")); close() }
        at(2).apply { list(); close() }

        val older = at(1)
        older.put(instance("b"))
        older.close()

        val ids = at(2).list().map { it.id }
        assertEquals(listOf("a"), ids, "the older build did not write over the newer database")
    }
}
