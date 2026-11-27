package hivens.ui.editor.presets

import hivens.ui.customization.CustomizationSettings
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.DocumentCodec
import hivens.widget.model.LayoutId
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val launcher = CompositionName("launcher")

    /** A one-config interface holding [widgets] at its root. */
    private fun interfaceWith(vararg widgets: WidgetInstance, modules: List<String> = emptyList()) = Document(
        modules = modules,
        start = launcher,
        compositions = mapOf(
            launcher to Composition(
                start = ConfigId("home"),
                configs = mapOf(ConfigId("home") to Config(layouts = listOf(ConfigLayout(LayoutId("main"), SlotContent(widgets.toList()))))),
            ),
        ),
    )

    /** A file written by hand, for the paths that have to start from bytes on disk. */
    private fun envelope(name: String, document: Document? = interfaceWith()) = PresetEnvelope(
        name          = name,
        createdAt     = 1_700_000_000L,
        document      = document?.let { json.parseToJsonElement(DocumentCodec.write(it)) },
        customization = CustomizationSettings(),
    )

    private fun PresetRepository.save(name: String) = save(name, interfaceWith(), CustomizationSettings())

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
    fun `a name saved before the digest and saved again since is two rows, each read and deleted from its own file`() {
        Files.createDirectories(tmp.resolve("presets"))
        val before = interfaceWith(WidgetInstance(WidgetKind("notes"), "notes-before"))
        Files.writeString(tmp.resolve("presets/____.json"), json.encodeToString(PresetEnvelope.serializer(), envelope("Ночь", before)))
        val repo = newRepo()
        val since = interfaceWith(WidgetInstance(WidgetKind("notes"), "notes-since"))
        repo.save("Ночь", since, CustomizationSettings())

        val rows = repo.list()
        assertEquals(listOf("Ночь", "Ночь"), rows.map { it.name })
        assertEquals(2, rows.map { it.sourcePath }.toSet().size, "the panel keys its rows by file, so they must differ")
        val legacy = rows.single { it.sourcePath.name == "____.json" }
        assertEquals(before, repo.load(legacy)?.document, "Apply on the older row applies the older file")
        assertEquals(since, repo.load(rows.single { it != legacy })?.document)

        assertTrue(repo.delete(legacy))
        assertEquals(listOf(since), repo.list().map { repo.load(it)?.document })
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

    /**
     * The state collector keeps what these ids own, so a widget a preset brings
     * back returns with its note or checklist rather than empty.
     */
    @Test
    fun `every instance id a saved preset names is reported, nested ones included`() {
        val repo = newRepo()
        val inner = WidgetInstance(WidgetKind("notes"), "notes-1")
        val group = WidgetInstance(
            kind       = WidgetKind("container.group"),
            instanceId = "group-1",
            children   = mapOf(SlotId("body") to SlotContent(listOf(inner))),
        )
        repo.save("A", interfaceWith(group), CustomizationSettings())
        repo.save("B", interfaceWith(WidgetInstance(WidgetKind("checklist"), "check-1")), CustomizationSettings())

        assertEquals(setOf("group-1", "notes-1", "check-1"), repo.referencedInstanceIds())
    }

    @Test
    fun `save round-trips through load, the whole interface but not the modules`() {
        val repo = newRepo()
        val saved = interfaceWith(WidgetInstance(WidgetKind("notes"), "notes-1"), modules = listOf("hivens.themes"))
        repo.save("Music mode", saved, CustomizationSettings())
        val back = assertNotNull(repo.load("Music mode"))
        assertEquals("Music mode", back.name)
        assertEquals(saved.copy(modules = emptyList()), back.document, "which modules run is the launcher's, not the arrangement's")
    }

    @Test
    fun `a preset a newer build wrote is not applied, and stays listed`() {
        val repo = newRepo()
        Files.createDirectories(tmp.resolve("presets"))
        val newer = envelope("Future", document = null).copy(document = JsonObject(mapOf("schema" to JsonPrimitive(99), "start" to JsonPrimitive("launcher"))))
        Files.writeString(tmp.resolve("presets/Future.json"), json.encodeToString(PresetEnvelope.serializer(), newer))
        assertNull(repo.load("Future"))
        assertEquals(listOf("Future"), repo.list().map { it.name })
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
    fun `a preset made before the interface was a document is listed as older, and never applied`() {
        val repo = newRepo()
        Files.createDirectories(tmp.resolve("presets"))
        val older = envelope("Ancient", document = null).copy(
            schemaVersion = 9,
            graph = JsonObject(mapOf("surfaces" to JsonObject(mapOf("home.new" to JsonObject(mapOf("instance_id" to JsonPrimitive("note-old"))))))),
        )
        Files.writeString(tmp.resolve("presets/Ancient.json"), json.encodeToString(PresetEnvelope.serializer(), older))
        assertNull(repo.load("Ancient"))
        assertEquals(listOf(true), repo.list().map { it.older }, "the file is left where it is, and says why it cannot be applied")
        assertEquals(setOf("note-old"), repo.referencedInstanceIds(), "and the state of what it names is kept")
    }

    @Test
    fun `corrupt file returns null on load instead of throwing`() {
        val repo = newRepo()
        Files.createDirectories(tmp.resolve("presets"))
        Files.writeString(tmp.resolve("presets/Bad.json"), "{this is not json")
        assertNull(repo.load("Bad"))
    }
}
