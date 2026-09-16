package hivens.ui.editor.presets

import hivens.ui.customization.CustomizationSettings
import hivens.ui.layout.LayoutReconcile
import hivens.widget.model.LayoutGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetRepositoryTest {

    private lateinit var tmp: Path
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; coerceInputValues = true }

    @BeforeTest
    fun setup() {
        tmp = Files.createTempDirectory("preset-repo-test")
    }

    @AfterTest
    fun teardown() {
        Files.walk(tmp).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun newRepo() = PresetRepository(tmp.resolve("presets"), json)

    /** A file written by hand, for the paths that have to start from bytes on disk. */
    private fun envelope(name: String, schema: Int = LayoutReconcile.CURRENT_SCHEMA) = PresetEnvelope(
        schemaVersion = schema,
        name          = name,
        createdAt     = 1_700_000_000L,
        graph         = JsonObject(emptyMap()),
        customization = CustomizationSettings(),
    )

    private fun PresetRepository.save(name: String) = save(name, LayoutGraph.EMPTY, CustomizationSettings())

    @Test
    fun `a preset written before the digest suffix is still readable`() {
        // Upgrade path: the file on disk is at the bare sanitised name.
        Files.createDirectories(tmp.resolve("presets"))
        Files.writeString(
            tmp.resolve("presets/Legacy.json"),
            json.encodeToString(PresetEnvelope.serializer(), envelope("Legacy")),
        )
        val repo = newRepo()

        assertNotNull(repo.load("Legacy"), "an upgrade must not orphan what the user already saved")
        assertEquals(listOf("Legacy"), repo.list().map { it.name })
        assertTrue(repo.delete("Legacy"))
    }

    @Test
    fun `two names that sanitise alike stay two presets`() {
        val repo = newRepo()
        // Every character outside [A-Za-z0-9_-] maps to an underscore, so any two
        // Cyrillic names of equal length used to collapse to one file and the
        // second save destroyed the first.
        repo.save("Ночь")
        repo.save("День")

        val names = repo.list().map { it.name }.toSet()
        assertEquals(setOf("Ночь", "День"), names, "one preset overwrote the other")
        assertNotNull(repo.load("Ночь"))
        assertNotNull(repo.load("День"))
    }

    @Test
    fun `the typed name survives a round trip through the file`() {
        val repo = newRepo()
        repo.save("Тёмная тема")
        assertEquals(listOf("Тёмная тема"), repo.list().map { it.name })
    }

    @Test
    fun `deleting one of two look-alike names leaves the other`() {
        val repo = newRepo()
        repo.save("Ночь")
        repo.save("День")

        assertTrue(repo.delete("Ночь"))

        assertEquals(listOf("День"), repo.list().map { it.name })
        assertNull(repo.load("Ночь"))
        assertNotNull(repo.load("День"))
    }

    @Test
    fun `save round-trips through load`() {
        val repo = newRepo()
        repo.save("Music mode")
        val back = repo.load("Music mode")
        assertNotNull(back)
        assertEquals("Music mode", back.name)
    }

    @Test
    fun `list returns presets newest-first`() {
        val repo = newRepo()
        repo.save("Alpha")
        Thread.sleep(15)
        repo.save("Beta")
        val list = repo.list()
        assertEquals(listOf("Beta", "Alpha"), list.map { it.name })
    }

    @Test
    fun `delete removes the file`() {
        val repo = newRepo()
        repo.save("Doomed")
        assertEquals(listOf("Doomed"), repo.list().map { it.name })
        assertTrue(repo.delete("Doomed"))
        assertEquals(emptyList(), repo.list())
        assertNull(repo.load("Doomed"))
    }

    @Test
    fun `delete of unknown name returns false`() {
        val repo = newRepo()
        assertFalse(repo.delete("ghost"))
    }

    @Test
    fun `sanitization replaces unsafe chars and prevents traversal`() {
        val repo = newRepo()
        repo.save("../../etc/passwd")
        // Sanitized to underscores; original path traversal does not
        // escape the presets dir.
        val files = Files.list(tmp.resolve("presets")).use { it.toList() }
        assertEquals(1, files.size)
        assertTrue(files.first().name.endsWith(".json"))
        assertFalse(files.first().name.contains("/"))
        assertFalse(files.first().name.contains(".."))
    }

    @Test
    fun `export copies the file to destination`() {
        val repo = newRepo()
        repo.save("Backup")
        val dest = tmp.resolve("out/Backup.json")
        Files.createDirectories(dest.parent)
        assertTrue(repo.export("Backup", dest))
        assertTrue(dest.exists())
    }

    @Test
    fun `import round-trips through the presets dir`() {
        val repo = newRepo()
        val external = tmp.resolve("incoming.json")
        Files.writeString(external, json.encodeToString(envelope("Imported")))
        val imported = repo.import(external)
        assertEquals("Imported", imported)
        assertNotNull(repo.load("Imported"))
    }

    @Test
    fun `a preset from before the surface schema is refused rather than half-read`() {
        // It used to be applied: the floor lived in the repository that owns the
        // layout file, and a preset went straight to the merge. Files from before
        // that schema are in people's preset directories now, and applying one
        // dropped every widget's plane on decode without a word, then persisted
        // the result as current.
        val repo = newRepo()
        Files.createDirectories(tmp.resolve("presets"))
        Files.writeString(
            tmp.resolve("presets/Ancient.json"),
            json.encodeToString(PresetEnvelope.serializer(), envelope("Ancient", schema = 7)),
        )
        assertNull(repo.load("Ancient"))
        assertEquals(listOf("Ancient"), repo.list().map { it.name }, "the file is left where it is")
    }

    @Test
    fun `corrupt file returns null on load instead of throwing`() {
        val repo = newRepo()
        Files.createDirectories(tmp.resolve("presets"))
        Files.writeString(tmp.resolve("presets/Bad.json"), "{this is not json")
        assertNull(repo.load("Bad"))
    }
}
