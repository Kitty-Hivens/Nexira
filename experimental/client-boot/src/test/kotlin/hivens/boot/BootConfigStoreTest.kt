package hivens.boot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract of the file that is read before anything else exists: no read
 * path throws, a first run is told apart from damage, and neither a rewrite nor
 * a failed read costs the user a key or a file.
 */
class BootConfigStoreTest {

    private val dir: Path = createTempDirectory("boot-config")

    private fun storeOver(name: String, contents: String? = null): BootConfigStore {
        val file = dir.resolve(name)
        contents?.let { file.writeText(it) }
        return BootConfigStore(file) { path, text -> path.writeText(text) }
    }

    @Test
    fun `no file at all is a first run, not damage`() {
        // The caller seeds this from the bundled default. Reporting it as broken
        // would put every fresh install into an error state.
        assertEquals(BootState.Absent, storeOver("absent.json").state())
    }

    @Test
    fun `a truncated file is damage, and nothing is guessed from it`() {
        // Half a write, the shape a crash mid-save leaves behind. This build has
        // no idea what is installed, so naming modules would turn one broken
        // file into a run of load failures.
        val state = storeOver("truncated.json", """{"bootstrap": ["config",""").state()
        assertTrue(state is BootState.Unreadable)
    }

    @Test
    fun `a declared bootstrap is taken as written`() {
        val state = storeOver("declared.json", """{"bootstrap": ["config", "recovery"]}""").state()
        assertEquals(listOf("config", "recovery"), (state as BootState.Loaded).config.bootstrap)
    }

    @Test
    fun `an empty bootstrap is a launcher that loads nothing, and is left that way`() {
        // Syntactically fine and deliberate: someone emptied it to get a bare
        // core. Substituting a guess here would override that on purpose.
        val state = storeOver("empty.json", """{"bootstrap": []}""").state()
        assertTrue((state as BootState.Loaded).config.bootstrap.isEmpty())
    }

    @Test
    fun `one malformed module entry does not cost the others`() {
        val state = storeOver(
            "mixed.json",
            """{"modules": [{"id": "theme"}, 7, {"noId": true}, {"id": "", "enabled": true}, {"id": "editor", "enabled": false}]}""",
        ).state()
        val config = (state as BootState.Loaded).config
        assertEquals(listOf("theme", "editor"), config.modules.map { it.id })
        assertTrue(config.modules.first { it.id == "theme" }.enabled)
        assertFalse(config.modules.first { it.id == "editor" }.enabled)
    }

    @Test
    fun `a module with no enabled flag is wanted`() {
        val state = storeOver("bare.json", """{"modules": [{"id": "theme"}]}""").state()
        assertTrue((state as BootState.Loaded).config.modules.single().enabled)
    }

    @Test
    fun `the wanted set drops what is switched off and stays separate from bootstrap`() {
        val config = BootConfig(
            bootstrap = listOf("config", "recovery"),
            modules = listOf(ModuleEntry("theme"), ModuleEntry("editor", enabled = false), ModuleEntry("shell")),
        )
        assertEquals(listOf("theme", "shell"), config.wantedModules())
    }

    @Test
    fun `a write preserves keys this build does not model`() {
        // The downgrade case: a newer build wrote fields with no field here, and
        // rewriting must not be how the user loses them.
        val file = dir.resolve("newer.json")
        file.writeText(
            """{"bootstrap": ["config"], "modules": [{"id": "theme"}], "schema": 9, "channels": {"beta": true}}""",
        )
        val store = BootConfigStore(file) { path, text -> path.writeText(text) }

        val loaded = (store.state() as BootState.Loaded).config
        store.write(loaded.copy(modules = listOf(ModuleEntry("theme", enabled = false))))

        val written = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(9, written["schema"]!!.jsonPrimitive.int)
        assertTrue("beta" in (written["channels"] as JsonObject))
        val reread = BootConfigStore(file) { _, _ -> }.state() as BootState.Loaded
        assertFalse(reread.config.modules.single().enabled)
    }

    @Test
    fun `reading a damaged file leaves it exactly as it was`() {
        // The loader falls back to the bundled default here; what it must not do
        // is repair itself by deleting the evidence. The damaged file is the
        // user's only lead to what they had.
        val file = dir.resolve("damaged.json")
        val original = """{"bootstrap": ["config", "shell"], "modul"""
        file.writeText(original)

        val store = BootConfigStore(file) { _, _ -> error("a read must not write") }
        assertTrue(store.state() is BootState.Unreadable)

        assertEquals(original, Files.readString(file))
    }
}
