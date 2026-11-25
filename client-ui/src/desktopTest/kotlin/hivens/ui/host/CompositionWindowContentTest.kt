package hivens.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.launcher.platform.PlatformPaths
import hivens.themes.Themes
import hivens.ui.CompositionContent
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.levelServicesModule
import hivens.ui.navigation.CompositionHistories
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxThemed
import hivens.ui.uiCommandRegistry
import hivens.ui.utils.ConsoleSettingsStore
import hivens.ui.utils.GameConsoleService
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.modules.NoWidgetModuleHost
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.Document
import hivens.widget.model.LayoutGraph
import hivens.widget.model.StandardDocument
import kotlin.test.AfterTest
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a window of a composition draws inside itself, drawn off screen with exactly what such a window is handed: the
 * launcher's graph and the kernel's locals. The console's window once drew with less than its widget reads and took
 * the interface down every time it opened, and again at every restart, since the window stays open across one.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CompositionWindowContentTest {

    private val dir = Files.createTempDirectory("composition-window-")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val json = Json { ignoreUnknownKeys = true }
    private val paths = PlatformPaths(
        osName = "linux",
        home = dir,
        bootstrapDataDir = { null },
        env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
    )
    private val console = GameConsoleService(paths)

    /** The launcher's graph as far as a window drawing widgets reaches into it. */
    private val graph = koinApplication {
        modules(
            levelServicesModule,
            module {
                single { console }
                single { ConsoleSettingsStore(dir.resolve("console.json"), json, scope) }
                single { CompositionHistories() }
                single<Document> { StandardDocument.load() }
                single { WidgetModules(dir.resolve("widgets"), dir.resolve("widget-modules.json"), json, GeneratedWidgetRegistry, scope, NoWidgetModuleHost) }
                single { LayoutGraphRepository(dir.resolve("layout-graph.json"), json, scope) { LayoutGraph.EMPTY } }
                single { WidgetServiceRegistry() }
                single { WidgetDataRegistry() }
                single { uiCommandRegistry({}, {}, {}) }
                single { WidgetStateStore(dir.resolve("widget-state.json"), json, scope) }
                single { WidgetScope.app(get(), KoinLevelServicesHost.app(this), Dispatchers.Unconfined, get()) }
            },
        )
    }

    @AfterTest
    fun tearDown() {
        graph.close()
        runBlocking { console.close() }
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the kernel's locals are the graph's own`() {
        val seen = ArrayList<Any?>()
        onEventThread {
            val scene = ImageComposeScene(10, 10, density = Density(1f)) {
                HostKoin(graph) {
                    KernelLocals {
                        seen += LocalWidgetRegistry.current
                        seen += LocalWidgetDataRegistry.current
                        seen += LocalWidgetCommandRegistry.current
                        seen += LocalWidgetServiceRegistry.current
                        seen += LocalWidgetStateHost.current
                        seen += LocalScope.current
                        seen += LocalLayoutGraph.current
                    }
                }
            }
            try {
                scene.render().close()
            } finally {
                scene.close()
            }
        }
        val koin = graph.koin
        assertEquals(
            listOf<Any?>(
                koin.get<WidgetModules>().state.value.registry, koin.get<WidgetDataRegistry>(), koin.get<WidgetCommandRegistry>(),
                koin.get<WidgetServiceRegistry>(), koin.get<WidgetStateStore>(), koin.get<WidgetScope>(), LayoutGraph.EMPTY,
            ),
            seen,
        )
    }

    @Test
    fun `the console's composition draws the console inside the kernel's locals and nothing more`() {
        val errors = ArrayList<Throwable>()
        var inked = 0
        val start = StandardDocument.load().compositions.getValue(LauncherConfigs.ConsoleComposition).start
        onEventThread {
            val scene = ImageComposeScene(600, 400, density = Density(1f), coroutineContext = Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> errors += e }) {
                HostKoin(graph) {
                    KernelLocals {
                        ShippedLanguageProvider("en") {
                            NxThemed(Themes.default, dark = true) {
                                CompositionContent(LauncherConfigs.ConsoleComposition, start)
                            }
                        }
                    }
                }
            }
            try {
                val image = scene.render(scene.settle(6))
                val bitmap = Bitmap.makeFromImage(image)
                try {
                    val info = bitmap.imageInfo
                    val pixels = checkNotNull(bitmap.readPixels(info, info.minRowBytes, 0, 0))
                    inked = (pixels.indices step 4).count { pixels[it + 3] != 0.toByte() }
                } finally {
                    bitmap.close()
                    image.close()
                }
            } finally {
                scene.close()
            }
        }
        assertEquals(emptyList(), errors.map { it.toString() })
        assertTrue(inked > 2_000, "the console itself was drawn ($inked pixels)")
    }
}
