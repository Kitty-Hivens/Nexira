package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.test.stopThenDelete
import hivens.ui.ModuleAppLevel
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.BootAnswers
import hivens.widget.api.BootValues
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenWords
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a module compiled into the launcher answers of its app sources before the host is up, through
 * the one mechanism every such module uses, shown on a module of the test's own that is neither the
 * themes nor the language module: a volume, kept in its settings domain.
 */
class BootAnswersTest {

    private val dir: Path = Files.createTempDirectory("boot-answers")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Serializable
    data class VolumeSettings(val level: Int = 3)

    /** The volume, in `volume.json`, and in the former settings file as `volume` before that. */
    object VolumeDomain : SettingsDomain<VolumeSettings>("volume", VolumeSettings.serializer()) {
        override fun defaults() = VolumeSettings()

        override fun imported(legacy: JsonObject, current: VolumeSettings?): VolumeSettings? =
            (legacy["volume"] as? JsonPrimitive)?.intOrNull?.let { VolumeSettings(it) }
    }

    private val level = SourceKey<Int>("test.volume.level", ScopeLevel.App)

    private val settingsKey = SourceKey<VolumeSettings>("test.volume.settings", ScopeLevel.App)

    private val updateKey = CommandKey<SettingsChange<VolumeSettings>>("test.volume.settings.update", ScopeLevel.App)

    /** The keys of the volume module: the level, from its settings. */
    private val volumeKeys = object : KeyContribution {
        override val id = "test.volume.keys"
        override val label = "Volume"
        override fun declare(keys: ModuleKeys) {
            keys.settings(VolumeDomain, settingsKey, updateKey)
            keys.source(level, sample = { 3 }) { app ->
                val store = app.services.get<SettingsStores>().of(VolumeDomain)
                flowSource(store.value.map { it.level }.stateIn(app.coroutineScope, SharingStarted.Eagerly, store.value.value.level))
            }
        }
    }

    /**
     * The volume module: its keys answer the level from its settings, and before the boot it answers the
     * same from the same files, or [fixed] where that is given.
     */
    private inner class Volume(override val id: String = "test.volume", private val answers: Boolean = true, private val fixed: Int? = null) : CompiledModule {
        override val name: String = "volume"
        override val contributions: List<ModuleContribution> = listOf(volumeKeys)
        override val boot: BootAnswers? = if (!answers) null else BootAnswers { context ->
            BootValues.of { put(level, fixed ?: context.settings(VolumeDomain).level) }
        }
    }

    /** A module whose answer before the boot throws, as a broken one would. */
    private object Broken : CompiledModule {
        override val id = "test.broken"
        override val name = "broken"
        override val contributions: List<ModuleContribution> = emptyList()
        override val boot = BootAnswers { error("the module's files are not what it expects") }
    }

    /** What [modules] answer before the boot, with a document that names every one of them. */
    private fun boot(vararg modules: CompiledModule): BootValues = bootValues(dir, modules.toList(), named = modules.map { it.id }.toSet())

    private fun writeVolume(value: Int) {
        dir.resolve(Storage.SETTINGS_DIR).createDirectories().resolve("volume.json").writeText("""{"schema":1,"value":{"level":$value}}""")
    }

    @Test
    fun `a module answers its source before the boot as its key does once it is up, from the same files`() {
        writeVolume(7)
        val module = Volume()

        val before = boot(module)[level]
        val after = ModuleAppLevel(dir, listOf(module), scope).use { it.value(level) }

        assertEquals(7, before)
        assertEquals(before, after)
    }

    @Test
    fun `before the boot the settings are read as the host opens them, from the former file too, and nothing is written`() {
        dir.resolve(Storage.SETTINGS_FILE).writeText("""{"volume":9}""")
        val module = Volume()

        assertEquals(9, boot(module)[level])
        assertFalse(dir.resolve(Storage.SETTINGS_DIR).resolve("volume.json").exists(), "reading before the boot writes nothing")
        assertEquals(9, ModuleAppLevel(dir, listOf(module), scope).use { it.value(level) })
    }

    @Test
    fun `a module the document leaves out answers nothing before the boot, as its keys answer nothing after`() {
        writeVolume(7)
        val module = Volume()

        ModuleAppLevel.writeDocument(dir, listOf("someone.else"))
        assertNull(bootValues(dir, listOf(module))[level])
        assertNull(ModuleAppLevel(dir, listOf(module), scope, named = documentModules(dir)).use { it.app.find(level) })

        ModuleAppLevel.writeDocument(dir, listOf(module.id))
        assertEquals(7, bootValues(dir, listOf(module))[level], "named, it answers")
    }

    @Test
    fun `a module whose answer fails costs only its own values`() {
        writeVolume(5)

        val boot = boot(Broken, Volume())

        assertEquals(5, boot[level], "the module after the broken one still answers")
        assertEquals(setOf(level.id), boot.ids)
    }

    @Test
    fun `a module whose words or whose answers cannot even be read costs only its own values`() {
        writeVolume(5)
        val wordless = object : CompiledModule {
            override val id = "test.wordless"
            override val name = "wordless"
            override val strings: ModuleStrings get() = error("strings could not be read")
            override val contributions: List<ModuleContribution> = emptyList()
        }
        val unreadable = object : CompiledModule {
            override val id = "test.unreadable"
            override val name = "unreadable"
            override val contributions: List<ModuleContribution> = emptyList()
            override val boot: BootAnswers get() = error("the answers could not be read")
        }

        val boot = boot(wordless, unreadable, Volume())

        assertEquals(5, boot[level])
    }

    @Test
    fun `a module answers only the app sources it declares, and one that answers another's answers nothing`() {
        writeVolume(4)
        val squatter = object : CompiledModule {
            override val id = "test.squatter"
            override val name = "squatter"
            override val contributions: List<ModuleContribution> = emptyList()
            override val boot = BootAnswers { BootValues.of { put(level, 1) } }
        }

        assertEquals(4, boot(squatter, Volume())[level], "the module that declares the key answers it, whatever comes first")
        assertNull(boot(squatter)[level])
    }

    @Test
    fun `a module reads only the settings domains it declares`() {
        writeVolume(4)
        val reader = object : CompiledModule {
            override val id = "test.reader"
            override val name = "reader"
            override val contributions: List<ModuleContribution> = listOf(
                object : KeyContribution {
                    override val id = "test.reader.keys"
                    override val label = "Reader"
                    override fun declare(keys: ModuleKeys) {
                        keys.source(level) { error("never read here") }
                    }
                },
            )
            override val boot = BootAnswers { context -> BootValues.of { put(level, context.settings(VolumeDomain).level) } }
        }

        assertNull(boot(reader)[level], "another module's domain is not its to read")
    }

    @Test
    fun `of two modules that declare a key, the first the build carries keeps its answer`() {
        writeVolume(4)

        assertEquals(1, boot(Volume("test.first", fixed = 1), Volume())[level])
        assertEquals(4, boot(Volume(), Volume("test.first", fixed = 1))[level])
    }

    @Test
    fun `a module answers nothing but app sources, and one that tries answers nothing`() {
        val page = SourceKey<Int>("test.volume.page", ScopeLevel.Page)
        val wrong = object : CompiledModule {
            override val id = "test.wrong"
            override val name = "wrong"
            override val contributions: List<ModuleContribution> = listOf(volumeKeys)
            override val boot = BootAnswers { BootValues.of { put(level, 2); put(page, 2) } }
        }

        val boot = boot(wrong)

        assertNull(boot[level])
        assertNull(boot[page])
    }

    @Test
    fun `a module that answers nothing before the boot is left to the fallbacks, its keys working as ever`() {
        writeVolume(6)
        val quiet = Volume(answers = false)

        assertNull(boot(quiet)[level])
        assertEquals(6, ModuleAppLevel(dir, listOf(quiet), scope).use { it.value(level) })
    }

    @Test
    fun `a module is handed the words the launcher and every compiled module ship, and nothing of a jar's`() {
        var handed: Map<String, ModuleStrings>? = null
        val reader = object : CompiledModule {
            override val id = "test.reader"
            override val name = "reader"
            override val strings = ModuleStrings(mapOf("en" to mapOf("reader" to "Reader")))
            override val contributions: List<ModuleContribution> = emptyList()
            override val boot = BootAnswers { context ->
                handed = context.services.find<TakenWords>()?.all?.value
                BootValues.NONE
            }
        }

        boot(reader, Volume())

        assertEquals(listOf(NexiraStrings.NAMESPACE, "test.reader", "test.volume"), handed?.keys?.toList(), "the launcher's own first, then the build's order")
        assertEquals("Reader", handed?.get("test.reader")?.tables?.get("en")?.get("reader"))
    }

    @Test
    fun `what is answered later holds nothing rather than failing, so the boot window still opens`() {
        val later = answeredLater { error("nothing could be read") }

        assertSame(BootValues.NONE, later.get(5, TimeUnit.SECONDS))
        assertTrue(later.isDone && !later.isCompletedExceptionally)
    }
}
