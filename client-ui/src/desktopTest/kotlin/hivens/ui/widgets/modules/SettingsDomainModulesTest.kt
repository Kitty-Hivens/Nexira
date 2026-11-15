package hivens.ui.widgets.modules

import hivens.config.Storage
import hivens.i18n.I18nModule
import hivens.i18n.I18nSettingsDomain
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.I18nSettings
import hivens.i18n.api.LanguageInfo
import hivens.ui.HostKeys
import hivens.ui.background.BackgroundSettings
import hivens.ui.followModuleReloads
import hivens.ui.i18n.NexiraStrings
import hivens.ui.jarKeysOf
import hivens.ui.moduleKeys
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.ContributedServices
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsStores
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.CommandKey
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings domains declared by modules as the launcher brings them in: one by a module
 * compiled in (the language module's), one by a jar (the fixture's count), each read and
 * changed through its own keys, each in its own file, and one going away leaving the
 * other as it was.
 */
class SettingsDomainModulesTest {

    private lateinit var tmp: Path
    private lateinit var folder: Path
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val fixtureJar: Path = Path.of(requireNotNull(System.getProperty("nexira.test.fixtureModuleJar")))

    private val count = SourceKey<Int>("fixture.count")
    private val updateCount = CommandKey<SettingsChange<Int>>("fixture.count.update")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("settings-domain-modules-")
        folder = tmp.resolve("widgets").createDirectories()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fixtureJar.copyTo(folder.resolve("fixture.jar"))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private fun modules() = WidgetModules(
        directory = folder,
        stateFile = tmp.resolve("widget-modules.json"),
        json      = json,
        builtIn   = GeneratedWidgetRegistry,
        scope     = scope,
        host      = DesktopWidgetModuleHost(folder, shadowDir = tmp.resolve("shadow"), offered = offeredApis(), apiPackages = offeredApiPackages()),
    )

    private val domains by lazy { SettingsDomainFiles(tmp.resolve(Storage.SETTINGS_DIR), tmp.resolve(Storage.SETTINGS_FILE)) }

    /** The host's keys and app level, the language module named and the jars [modules] has on brought in. */
    private fun app(modules: WidgetModules): Pair<HostKeys, WidgetScope> {
        val engine = NexiraStrings.engine(null, "en")
        val keys = HostKeys(
            uiDataRegistry(
                activity      = MutableStateFlow(emptyList()),
                notifications = MutableStateFlow(emptyList()),
                doNotDisturb  = MutableStateFlow(false),
                sslBypass     = flowSource(MutableStateFlow(false)),
                background    = MutableStateFlow(BackgroundSettings()),
            ),
            uiCommandRegistry({}, {}, {}),
            ContributedServices(),
        ).also {
            moduleKeys(it, setOf(I18nApi.ID), compiled = listOf(I18nModule), jars = jarKeysOf(modules), onCrash = modules::switchOffAfterCrash)
        }
        val app = koinApplication {
            modules(
                module {
                    single<SettingsStores> { domains }
                    single { engine }
                    single {
                        WidgetScope.app(keys.sources, keys.appServices(KoinLevelServicesHost.app(this), Dispatchers.Unconfined), Dispatchers.Unconfined, keys.commands)
                    }
                },
            )
        }.koin.get<WidgetScope>()
        return keys to app
    }

    @Test
    fun `a compiled module and a jar each keep a domain of their own`() {
        val (_, app) = app(modules())

        assertEquals(0, app.current(count), "the jar's domain starts at its defaults")
        assertNotNull(app.findCommand(updateCount)).run { it + 2 }
        assertEquals(2, app.current(count))

        assertNotNull(app.findCommand(I18nKeys.UpdateSettings)).run { it.copy(language = "ru") }
        assertEquals(I18nSettings("ru"), app.current(I18nKeys.Settings))

        assertTrue(tmp.resolve("settings/fixture.count.json").exists(), "the jar's domain has its own file")
        assertTrue(tmp.resolve("settings/i18n.json").exists(), "and the compiled module's its own")
        val fresh = SettingsDomainFiles(tmp.resolve(Storage.SETTINGS_DIR), tmp.resolve(Storage.SETTINGS_FILE))
        assertEquals(I18nSettings("ru"), fresh.peek(I18nSettingsDomain))
    }

    @Test
    fun `a jar switched off takes its keys with it and leaves the other domains working`() {
        val modules = modules()
        val (keys, app) = app(modules)
        followModuleReloads(modules.state, keys, app, scope)
        assertNotNull(app.findCommand(updateCount)).run { it + 1 }

        modules.setEnabled("fixture", false)
        val until = System.nanoTime() + 5_000_000_000L
        while (app.find(count) != null && System.nanoTime() < until) Thread.sleep(10)
        assertNull(app.find(count), "the jar's keys are gone with it")

        assertNotNull(app.findCommand(I18nKeys.UpdateSettings)).run { it.copy(language = "de") }
        assertEquals("de", assertNotNull(app.current(I18nKeys.Settings)).language, "the language module's domain is unaffected")
        val kept = Json.parseToJsonElement(Files.readString(tmp.resolve("settings/fixture.count.json"))).jsonObject
        assertEquals("1", kept["value"].toString(), "and the jar's file keeps what it last wrote")
    }

    @Test
    fun `a jar's words for another namespace reach the launcher's words`() {
        val words = modules().state.value.words.getValue("fixture")
        assertEquals("Vom Fixture zu den Worten des Launchers gelegt", words.namespaces.getValue(NexiraStrings.NAMESPACE).getValue("de").getValue("fixture.inLauncher"))
        val engine = NexiraStrings.engine(null, "de").apply { contribute("fixture", words.wordsOf("fixture")) }
        assertEquals("Vom Fixture zu den Worten des Launchers gelegt", engine.strings(NexiraStrings.NAMESPACE).text("fixture.inLauncher"))
        assertEquals("Fixture Effect", engine.strings("fixture").text("fixture.label"), "its own words read in English where it has no German")
    }

    @Test
    fun `a jar can add a language, which falls back as it declares`() {
        val words = modules().state.value.words.getValue("fixture")
        assertEquals(mapOf("name" to "Українська", "plural" to "east-slavic", "fallback" to "ru"), words.languageDeclarations["uk"])
        val engine = NexiraStrings.engine(null, "uk").apply { take("fixture", words) }
        assertEquals(LanguageInfo("uk", "Українська", "east-slavic", listOf("ru")), engine.current.value.language)
        assertEquals("Покладено фікстурою до слів лаунчера", engine.strings(NexiraStrings.NAMESPACE).text("fixture.inLauncher"))
        assertEquals(NexiraStrings.shipped("ru").text("navBack"), engine.strings(NexiraStrings.NAMESPACE).text("navBack"), "what it lacks reads in Russian")
        assertEquals(listOf("uk", "ru"), engine.readingOrder)
        engine.withdraw("fixture")
        assertEquals("en", engine.current.value.language.tag, "the language leaves with the jar")
    }
}
