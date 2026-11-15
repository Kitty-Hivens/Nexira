package hivens.ui.settings

import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyStore
import hivens.i18n.I18nSettingsDomain
import hivens.i18n.api.I18nSettings
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.launcher.SettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The host's side of the modules' settings: one file per domain, defaults where there is
 * nothing to read, a file of a newer build left alone for that domain only, changes applied
 * in order and written whole, and the old settings file read once and never written.
 */
class SettingsDomainFilesTest {

    @Serializable
    data class Prefs(val volume: Int = 5, val name: String = "default")

    private object PrefsDomain : SettingsDomain<Prefs>("test.prefs", Prefs.serializer()) {
        override fun defaults() = Prefs()
    }

    /** The same domain at a later schema, which this build would not write over. */
    private object OtherDomain : SettingsDomain<Prefs>("test.other", Prefs.serializer()) {
        override fun defaults() = Prefs(volume = 1)
    }

    /** A domain the former file held one field of, `volume`, and not its name. */
    private object MovedDomain : SettingsDomain<Prefs>("test.moved", Prefs.serializer()) {
        override fun defaults() = Prefs()
        override fun imported(legacy: JsonObject, current: Prefs?): Prefs? =
            (legacy["volume"] as? JsonPrimitive)?.intOrNull?.let { volume -> (current ?: defaults()).copy(volume = volume) }
        override fun mirrored(value: Prefs): Map<String, JsonElement> = mapOf("volume" to JsonPrimitive(value.volume))
    }

    private lateinit var data: Path
    private val dir get() = data.resolve("settings")
    private val legacy get() = data.resolve("settings.json")

    @BeforeTest
    fun setUp() {
        data = Files.createTempDirectory("settings-domains-")
        NewerBuildData.reset()
    }

    @AfterTest
    fun tearDown() {
        data.toFile().deleteRecursively()
        NewerBuildData.reset()
    }

    private fun files() = SettingsDomainFiles(dir, legacy)

    @Test
    fun `a domain with no file reads its defaults`() {
        val store = files().of(PrefsDomain)
        assertEquals(Prefs(), store.value.value)
        assertFalse(store.readOnly)
        assertFalse(dir.resolve("test.prefs.json").exists(), "reading writes nothing")
    }

    @Test
    fun `a change is written to the domain's own file and read back by the next start`() {
        files().of(PrefsDomain).update { it.copy(volume = 9, name = "mine") }
        val file = dir.resolve("test.prefs.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, root.getValue("schema").jsonPrimitive.int)
        assertEquals(Prefs(9, "mine"), files().of(PrefsDomain).value.value)
        assertEquals(Prefs(9, "mine"), files().peek(PrefsDomain))
    }

    @Test
    fun `a file that cannot be read gives the defaults, and the next change replaces it`() {
        Files.createDirectories(dir)
        dir.resolve("test.prefs.json").writeText("{ not json")
        val store = files().of(PrefsDomain)
        assertEquals(Prefs(), store.value.value)
        assertFalse(store.readOnly)
        store.update { it.copy(volume = 2) }
        assertEquals(Prefs(volume = 2), files().of(PrefsDomain).value.value)
    }

    @Test
    fun `a value of the wrong shape gives the defaults`() {
        Files.createDirectories(dir)
        dir.resolve("test.prefs.json").writeText("""{"schema":1,"value":{"volume":"loud"}}""")
        assertEquals(Prefs(), files().of(PrefsDomain).value.value)
    }

    @Test
    fun `a file of a newer build is read and never written, for that domain alone`() {
        Files.createDirectories(dir)
        val newer = """{"schema":7,"value":{"volume":3,"name":"theirs","later":true}}"""
        dir.resolve("test.prefs.json").writeText(newer)
        val files = files()
        val store = files.of(PrefsDomain)
        assertTrue(store.readOnly)
        assertEquals(Prefs(3, "theirs"), store.value.value, "what it understands is read")
        store.update { it.copy(volume = 4) }
        assertEquals(4, store.value.value.volume, "the change holds for the session")
        assertEquals(newer, dir.resolve("test.prefs.json").readText(), "and is not written")
        assertTrue(ReadOnlyStore.Settings in NewerBuildData.affected())

        val other = files.of(OtherDomain)
        assertFalse(other.readOnly, "another domain is not touched by it")
        other.update { it.copy(volume = 8) }
        assertEquals(Prefs(volume = 8), files().of(OtherDomain).value.value)
    }

    @Test
    fun `changes from many threads are applied one at a time and the file holds the last`() {
        val writer = Executors.newSingleThreadExecutor()
        val files = SettingsDomainFiles(dir, legacy) { _, block -> writer.execute(block) }
        val store = files.of(PrefsDomain)
        val threads = 8
        val each = 250
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(each) { store.update { it.copy(volume = it.volume + 1) } }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        writer.shutdown()
        assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS))
        val expected = Prefs().volume + threads * each
        assertEquals(expected, store.value.value.volume, "no change was lost")
        assertEquals(expected, files().peek(PrefsDomain).volume, "the file holds the last value")
    }

    @Test
    fun `the language is taken from the old settings file until the domain has a file of its own`() {
        legacy.writeText("""{"locale":"de","isDarkTheme":false}""")
        assertEquals(I18nSettings("de"), files().peek(I18nSettingsDomain), "nothing saved yet, so every start reads it")
        assertEquals(I18nSettings("de"), files().of(I18nSettingsDomain).value.value)
        assertFalse(dir.resolve("i18n.json").exists(), "reading writes nothing")
    }

    private fun legacyJson() = Json.parseToJsonElement(legacy.readText()).jsonObject

    @Test
    fun `a choice is mirrored into the old file, only its own field, for an older build to read`() {
        legacy.writeText("""{"locale":"en","isDarkTheme":false,"javaPath":"/opt/jdk"}""")
        files().of(I18nSettingsDomain).update { it.copy(language = "de") }
        val old = legacyJson()
        assertEquals("de", old.getValue("locale").jsonPrimitive.content)
        assertEquals("false", old.getValue("isDarkTheme").jsonPrimitive.content, "every other field is left as it was")
        assertEquals("/opt/jdk", old.getValue("javaPath").jsonPrimitive.content)
    }

    @Test
    fun `a choice made by an older build after a downgrade is taken back on the next upgrade`() {
        legacy.writeText("""{"locale":"en","isDarkTheme":false}""")
        // The new build: the person picks German.
        files().of(I18nSettingsDomain).update { it.copy(language = "de") }
        // Back on a build from before the module: it reads German and the person picks Japanese there.
        assertEquals("de", legacyJson().getValue("locale").jsonPrimitive.content)
        legacy.writeText("""{"locale":"ja","isDarkTheme":false}""")
        // Up again: Japanese is the last choice.
        val store = files().of(I18nSettingsDomain)
        assertEquals(I18nSettings("ja"), store.value.value)
        assertEquals(I18nSettings("ja"), files().peek(I18nSettingsDomain), "and stays it at the next start")
        // A later choice on the new build is the choice again, in both files.
        store.update { it.copy(language = "ru") }
        assertEquals(I18nSettings("ru"), files().peek(I18nSettingsDomain))
        assertEquals("ru", legacyJson().getValue("locale").jsonPrimitive.content)
    }

    @Test
    fun `a change an older build made is laid over the domain's own value, so what the old file never held is kept`() {
        legacy.writeText("""{"volume":5}""")
        files().of(MovedDomain).update { Prefs(volume = 6, name = "mine") }
        // A build from before the domain changes the one field it knows.
        legacy.writeText("""{"volume":2}""")
        assertEquals(Prefs(volume = 2, name = "mine"), files().of(MovedDomain).value.value)
        assertEquals(Prefs(volume = 2, name = "mine"), files().peek(MovedDomain), "and is written so")
    }

    @Test
    fun `the old file left as the domain wrote it does not override the domain`() {
        legacy.writeText("""{"locale":"en"}""")
        files().of(I18nSettingsDomain).update { it.copy(language = "de") }
        assertEquals(I18nSettings("de"), files().peek(I18nSettingsDomain))
    }

    @Test
    fun `through the settings service the mirror reaches the copy it holds, so its next save keeps it`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        val service = SettingsService(json, legacy)
        val files = SettingsDomainFiles(dir, LegacySettingsThroughService(legacy, service, json))
        files.of(I18nSettingsDomain).update { it.copy(language = "de") }
        assertEquals("de", service.getSettings().locale)
        service.saveSettings(service.getSettings().copy(isDarkTheme = false))
        assertEquals("de", legacyJson().getValue("locale").jsonPrimitive.content, "another setting saved later keeps the language")
        assertEquals(I18nSettings("de"), files().peek(I18nSettingsDomain))
    }

    @Test
    fun `a domain file that cannot be read takes what the old file holds, and is left as it is`() {
        legacy.writeText("""{"locale":"ru"}""")
        Files.createDirectories(dir)
        val broken = "{ not json"
        dir.resolve("i18n.json").writeText(broken)
        assertEquals(I18nSettings("ru"), files().of(I18nSettingsDomain).value.value)
        assertEquals(I18nSettings("ru"), files().peek(I18nSettingsDomain))
        assertEquals(broken, dir.resolve("i18n.json").readText(), "nothing is written until a change")
    }

    @Test
    fun `a module loaded again with an older schema over a newer build's file writes nothing`() {
        val newer = object : SettingsDomain<Prefs>("test.prefs", Prefs.serializer()) {
            override fun defaults() = Prefs()
            override val schema = 2
        }
        val files = files()
        files.of(newer).update { it.copy(volume = 7) }
        val written = dir.resolve("test.prefs.json").readText()
        val older = files.of(PrefsDomain)
        assertTrue(older.readOnly, "the file is the newer schema's, as a start would find")
        assertEquals(7, older.value.value.volume)
        older.update { it.copy(volume = 1) }
        assertEquals(written, dir.resolve("test.prefs.json").readText())
    }

    @Test
    fun `an old settings file with no language gives the defaults`() {
        legacy.writeText("""{"isDarkTheme":false}""")
        assertEquals(I18nSettings(), files().peek(I18nSettingsDomain))
    }

    @Test
    fun `one store per domain while the same declaration is asked for, and a module loaded again keeps its value`() {
        val files = files()
        val first = files.of(PrefsDomain)
        assertSame(first, files.of(PrefsDomain))
        first.update { it.copy(name = "kept") }
        val again = object : SettingsDomain<Prefs>("test.prefs", Prefs.serializer()) {
            override fun defaults() = Prefs()
        }
        val reloaded = files.of(again)
        assertNotSame<Any>(first, reloaded)
        assertEquals("kept", reloaded.value.value.name)
    }

    @Test
    fun `a change that changes nothing writes nothing`() {
        val store = files().of(PrefsDomain)
        store.update(SettingsChange { it })
        assertFalse(dir.resolve("test.prefs.json").exists())
    }
}
