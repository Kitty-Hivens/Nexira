package hivens.ui.widgets.modules

import hivens.packs.PacksModule
import hivens.ui.HostKeys
import hivens.ui.JarKeys
import hivens.ui.background.BackgroundSettings
import hivens.ui.followModuleReloads
import hivens.ui.jarKeysOf
import hivens.ui.moduleKeys
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.ContributedServices
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jar modules bring keys the way the launcher brings them in: a jar switched on in
 * the module list counts as declared, a jar read again answers with its new code, a
 * jar switched off leaves its keys missing, and a jar whose own code throws while
 * declaring is switched off the way one that crashed the interface is.
 */
class JarModuleKeysTest {

    private lateinit var tmp: Path
    private lateinit var folder: Path
    private lateinit var scope: CoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val fixtureJar: Path = Path.of(requireNotNull(System.getProperty("nexira.test.fixtureModuleJar")))
    private val consumerJar: Path = Path.of(requireNotNull(System.getProperty("nexira.test.packsConsumerJar")))

    private val greeting = SourceKey<String>("fixture.greeting")
    private val shelf = "packs-consumer.shelf"

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("jar-module-keys-test-")
        folder = tmp.resolve("widgets").createDirectories()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Files.walk(tmp).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { val _ = runCatching { Files.deleteIfExists(it) } } }
    }

    private fun modules() = WidgetModules(
        directory = folder,
        stateFile = tmp.resolve("widget-modules.json"),
        json      = json,
        builtIn   = GeneratedWidgetRegistry,
        scope     = scope,
        host      = DesktopWidgetModuleHost(folder, shadowDir = tmp.resolve("shadow"), offered = offeredApis()),
    )

    /** The host's keys with the modules [modules] has on, as the launcher builds them at a start. */
    private fun keys(modules: WidgetModules, named: Set<String> = emptySet()): HostKeys {
        val data = uiDataRegistry(
            activity         = MutableStateFlow(emptyList()),
            notifications    = MutableStateFlow(emptyList()),
            doNotDisturb     = MutableStateFlow(false),
            sslBypass        = flowSource(MutableStateFlow(false)),
            background       = MutableStateFlow(BackgroundSettings()),
            accountsRevision = MutableStateFlow(0),
        )
        return HostKeys(data, uiCommandRegistry({}, {}, {}, {}), ContributedServices()).also {
            moduleKeys(it, named, compiled = listOf(PacksModule), jars = jarKeysOf(modules), onCrash = modules::switchOffAfterCrash)
        }
    }

    private fun app(keys: HostKeys): WidgetScope = koinApplication {
        modules(
            module {
                single { WidgetScope.app(keys.sources, keys.appServices(KoinLevelServicesHost.app(this), Dispatchers.Unconfined), Dispatchers.Unconfined, keys.commands) }
            },
        )
    }.koin.get()

    private fun awaitTrue(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            if (System.nanoTime() > until) throw AssertionError("$what never happened")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a jar switched on brings its keys with no name in the document, and switched off it brings none`() {
        consumerJar.copyTo(folder.resolve("packs-consumer.jar"))
        val modules = modules()
        assertNotNull(keys(modules).sources.declarationOf(shelf), "the shelf keys of a jar that is on are in")

        modules.setEnabled("packs-consumer", enabled = false)
        assertNull(keys(modules).sources.declarationOf(shelf), "a jar switched off brings nothing at the next start")
    }

    @Test
    fun `a jar read again answers with its new code, and one switched off leaves its keys missing`() {
        fixtureJar.copyTo(folder.resolve("fixture.jar"))
        val modules = modules()
        val keys = keys(modules)
        val app = app(keys)
        followModuleReloads(modules.state, keys, app, scope)
        val before = assertNotNull(app.find(greeting))
        assertEquals("hello from the jar", before.state.value)

        modules.reload()
        awaitTrue("the old source dropped") { app.find(greeting) !== before }
        val after = assertNotNull(app.find(greeting))
        assertEquals("hello from the jar", after.state.value)
        assertTrue(after !== before, "made again by the new load, not kept from the closed one")

        modules.setEnabled("fixture", enabled = false)
        awaitTrue("the key gone with the module") { app.current(greeting) == null }
    }

    @Test
    fun `a jar whose own code throws while declaring is switched off and told, not crashing the host`() {
        val crashed = CopyOnWriteArrayList<Pair<String, Throwable>>()
        val broken = object : KeyContribution {
            override val id = "broken.keys"
            override val label = "Broken"
            override fun declare(keys: ModuleKeys) = TODO("written later")
        }
        val data = uiDataRegistry(
            activity         = MutableStateFlow(emptyList()),
            notifications    = MutableStateFlow(emptyList()),
            doNotDisturb     = MutableStateFlow(false),
            sslBypass        = flowSource(MutableStateFlow(false)),
            background       = MutableStateFlow(BackgroundSettings()),
            accountsRevision = MutableStateFlow(0),
        )
        val keys = HostKeys(data, uiCommandRegistry({}, {}, {}, {}), ContributedServices())
        moduleKeys(keys, emptySet(), compiled = emptyList(), jars = listOf(JarKeys("broken", "Broken", "broken.keys") { broken })) { id, cause ->
            crashed += id to cause
        }
        assertEquals("broken", crashed.single().first)
        assertIs<NotImplementedError>(crashed.single().second)
    }
}
