package hivens.ui.host

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.launcher.platform.PlatformPaths
import hivens.ui.ShellLook
import hivens.ui.i18n.NexiraStrings
import hivens.ui.screens.ConsoleWindow
import hivens.ui.uiCommandRegistry
import hivens.ui.utils.GameConsoleService
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.LevelHost
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The launcher's graph reaches the composition through [HostKoin] and nothing else:
 * what is injected inside it, at the top and below a level the way the shell draws its
 * widgets, is the graph's own instance, the console window draws inside the graph it
 * is handed, and Koin's global context holds nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HostKoinTest {

    private class Service

    /**
     * The console window as the shell opens it, handed the graph and nothing around it.
     * It injects its service before it opens a window, and with the console hidden it
     * opens none, so it draws off screen: a console that took its Koin from the caller
     * would find none here.
     */
    @Test
    fun `the console window resolves from the graph it is handed, with nothing around it`() {
        val dir = Files.createTempDirectory("host-koin-console-")
        val paths = PlatformPaths(
            osName = "linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        val console = GameConsoleService(paths)
        val graph = koinApplication { modules(module { single { console } }) }
        try {
            assertFalse(console.shouldShowConsole, "the premise: a hidden console opens no window")
            val scene = ImageComposeScene(40, 40, density = Density(1f)) {
                ConsoleWindow(ShellLook(words = NexiraStrings.translator("en")), graph)
            }
            try {
                scene.render()
            } finally {
                scene.close()
            }
            assertNull(GlobalContext.getOrNull())
        } finally {
            graph.close()
            runBlocking { console.close() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `what the shell injects resolves from the host's graph, and nothing is global`() {
        val service = Service()
        val graph = koinApplication {
            modules(
                module {
                    single { service }
                    single { WidgetScope.app(WidgetDataRegistry(), KoinLevelServicesHost.app(this), Dispatchers.Unconfined, uiCommandRegistry({}, {}, {})) }
                },
            )
        }
        var top: Service? = null
        var koin: Koin? = null
        var underLevel: Service? = null
        try {
            val app: WidgetScope = graph.koin.get()
            val scene = ImageComposeScene(40, 40, density = Density(1f)) {
                HostKoin(graph) {
                    top = koinInject()
                    koin = getKoin()
                    LevelHost(app) { underLevel = koinInject() }
                }
            }
            try {
                scene.render()
            } finally {
                scene.close()
            }
            assertSame(service, top)
            assertSame(graph.koin, koin)
            assertSame(service, underLevel)
            assertNull(GlobalContext.getOrNull(), "the host's graph was registered globally")
        } finally {
            graph.close()
        }
    }
}
