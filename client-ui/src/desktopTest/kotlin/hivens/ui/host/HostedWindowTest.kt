package hivens.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.catalogue.CatalogueModule
import hivens.catalogue.OpenProjects
import hivens.catalogue.api.CatalogueApi
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.ProjectSource
import hivens.ui.HostKeys
import hivens.ui.background.BackgroundSettings
import hivens.ui.chrome.WindowCapabilities
import hivens.ui.levelServicesModule
import hivens.ui.moduleKeys
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.MainWindowRequests
import hivens.ui.navigation.NavRequests
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.Commands
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.Sources
import hivens.widget.api.ContributedServices
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.pages.api.Show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Windows as the shells host them, drawn off screen: each registered with the host,
 * each with a level of its own that carries its state, its commands and what lives
 * per window. The main window and the console are drawn side by side, as the
 * desktop draws them. The OS windows around them need a display and are not here.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HostedWindowTest {

    private class FakeWindow(width: Int) : WindowBackend {
        val live = MutableStateFlow(
            HostWindowState(width = width, height = 600, maximized = false, minimized = false, focused = false, allowed = WindowCapabilities.Everything),
        )
        override val state: StateFlow<HostWindowState> = live
        override val commands: Set<WindowCommand> = WindowCommand.entries.toSet()
        val ran = ArrayList<WindowCommand>()
        var closed = 0

        override fun run(command: WindowCommand) {
            ran += command
        }

        override fun raise() = Unit

        override fun close() {
            closed++
        }
    }

    /**
     * The launcher's own registries and per-window services under an app level, the way
     * the shell has them, with the catalogue module's keys brought in, whose project is
     * the one the rail of each window describes.
     */
    private fun koin(): Koin = koinApplication {
        modules(
            levelServicesModule,
            module {
                single {
                    val keys = HostKeys(
                        sources = uiDataRegistry(
                            activity         = MutableStateFlow(emptyList()),
                            notifications    = MutableStateFlow(emptyList()),
                            doNotDisturb     = MutableStateFlow(false),
                            sslBypass        = flowSource(MutableStateFlow(false)),
                            background       = MutableStateFlow(BackgroundSettings()),
                        ),
                        commands = uiCommandRegistry({}, {}, {}),
                        services = ContributedServices(),
                    )
                    moduleKeys(keys, named = setOf(CatalogueApi.ID), compiled = listOf(CatalogueModule), jars = emptyList())
                    WidgetScope.app(
                        registry = keys.sources,
                        services = keys.appServices(KoinLevelServicesHost.app(this), Dispatchers.Unconfined),
                        context  = Dispatchers.Unconfined,
                        commands = keys.commands,
                        access   = LauncherHostAccess,
                    )
                }
                single { MainWindowRequests() }
            },
        )
    }.koin

    private fun draw(koin: Koin, frames: Int = 4, content: @Composable () -> Unit): ImageComposeScene {
        val scene = ImageComposeScene(400, 300, density = Density(1f)) {
            CompositionLocalProvider(LocalScope provides koin.get<WidgetScope>(), content = content)
        }
        scene.settle(frames)
        return scene
    }

    /** What one window's widgets see. */
    private class Seen {
        var state: HostWindowState? = null
        var maximize: (() -> Unit)? = null
        var openProject: OpenProjects? = null
        var project: OpenProject? = null
        var nav: NavRequests? = null
        var level: WidgetScope? = null
    }

    @Composable
    private fun Reader(seen: Seen) {
        seen.state = rememberScopedSource(Sources.Window).valueOrNull()
        seen.maximize = rememberScopedAction(Commands.MaximizeWindow)
        seen.openProject = LocalScope.current?.services?.get<OpenProjects>()
        seen.project = rememberScopedSource(CatalogueKeys.OpenProject).valueOrNull()
        seen.nav = koinInject<NavRequests>()
        seen.level = LocalScope.current
    }

    @Test
    fun `each window's widgets read the state of their own window`() {
        val koin = koin()
        val registry = WindowRegistry()
        val main = FakeWindow(width = 1000)
        val console = FakeWindow(width = 640)
        val inMain = Seen()
        val inConsole = Seen()
        onEventThread {
            val scene = draw(koin) {
                HostedWindow(WindowId.Main, WindowOwner.Launcher, main, registry) { Reader(inMain) }
                HostedWindow(WindowId.Console, WindowOwner.Launcher, console, registry) { Reader(inConsole) }
            }
            try {
                assertEquals(1000, inMain.state?.width)
                assertEquals(640, inConsole.state?.width)

                main.live.value = main.live.value.copy(width = 1200, maximized = true)
                scene.settle(4)
                assertEquals(1200, inMain.state?.width)
                assertEquals(true, inMain.state?.maximized)
                assertEquals(640, inConsole.state?.width, "the console's widgets never see the main window move")

                assertNotNull(inConsole.maximize).invoke()
                assertEquals(listOf(WindowCommand.Maximize), console.ran)
                assertTrue(main.ran.isEmpty(), "a command goes to the window it was fired in")

                assertEquals(listOf(WindowId.Main, WindowId.Console), registry.windows.value.map { it.id })
            } finally {
                scene.close()
            }
        }
        assertTrue(registry.windows.value.isEmpty(), "both windows left the registry with their composition")
    }

    @Test
    fun `two windows do not share the open project, the surface families or the navigation`() {
        val koin = koin()
        val registry = WindowRegistry()
        val inMain = Seen()
        val inConsole = Seen()
        onEventThread {
            val scene = draw(koin) {
                HostedWindow(WindowId.Main, WindowOwner.Launcher, FakeWindow(1000), registry) { Reader(inMain) }
                HostedWindow(WindowId.Console, WindowOwner.Launcher, FakeWindow(640), registry) { Reader(inConsole) }
            }
            try {
                val mainState = assertNotNull(inMain.openProject)
                assertNotSame(mainState, inConsole.openProject)
                assertSame(mainState, inMain.level?.services?.get<OpenProjects>(), "the window's widgets and its level agree")

                val page = Any()
                mainState.claim(page)
                mainState.publish(page, OpenProject(targetKey = "catalogue:sodium", title = "Sodium", slug = "sodium", source = ProjectSource.Catalogue))
                scene.settle(4)
                assertEquals("Sodium", inMain.project?.title)
                assertNull(inConsole.project, "the console's rail has no project open")

                assertNotSame(inMain.nav, inConsole.nav)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `a notification action lands in the main window and raises it when it is not in front`() {
        val koin = koin()
        val registry = WindowRegistry()
        val main = FakeWindow(1000)
        val inMain = Seen()
        val inConsole = Seen()
        var raised = 0
        val collected = CoroutineScope(Dispatchers.Unconfined)
        val toMain = ArrayList<Show>()
        val toConsole = ArrayList<Show>()
        onEventThread {
            val scene = draw(koin) {
                HostedWindow(WindowId.Main, WindowOwner.Launcher, main, registry) {
                    MainWindowNavigation(raise = { raised++ })
                    Reader(inMain)
                }
                HostedWindow(WindowId.Console, WindowOwner.Launcher, FakeWindow(640), registry) { Reader(inConsole) }
            }
            try {
                collected.launch { assertNotNull(inMain.nav).requests.collect { toMain += it } }
                collected.launch { assertNotNull(inConsole.nav).requests.collect { toConsole += it } }

                koin.get<MainWindowRequests>().open(LauncherConfigs.packVersions("industrial"))
                scene.settle(2)
                assertEquals(listOf(LauncherConfigs.packVersions("industrial")), toMain)
                assertTrue(toConsole.isEmpty(), "the console never takes what was meant for the main window")
                assertEquals(1, raised)

                main.live.value = main.live.value.copy(focused = true)
                scene.settle(2)
                koin.get<MainWindowRequests>().open(Show(LauncherConfigs.Home))
                scene.settle(2)
                assertEquals(2, toMain.size)
                assertEquals(1, raised, "a window already in front is not raised again")
            } finally {
                scene.close()
                collected.cancel()
            }
        }
    }

    @Test
    fun `a request made while no main window listens waits for one, and the raise comes after it is handed over`() {
        val koin = koin()
        val registry = WindowRegistry()
        val requests = koin.get<MainWindowRequests>()
        // Made before any shell is up, as a notification action during a restart is.
        requests.open(LauncherConfigs.packVersions("industrial"))
        val inMain = Seen()
        var raised = 0
        val collected = CoroutineScope(Dispatchers.Unconfined)
        val toMain = ArrayList<Show>()
        onEventThread {
            val scene = draw(koin) {
                HostedWindow(WindowId.Main, WindowOwner.Launcher, FakeWindow(1000), registry) {
                    MainWindowNavigation(raise = { raised++ })
                    Reader(inMain)
                }
            }
            try {
                assertEquals(0, raised, "nothing was handed over while the back stack did not listen, so nothing was raised")
                // The back stack starts listening, the way AppRoot does once it is drawn.
                collected.launch { assertNotNull(inMain.nav).requests.collect { toMain += it } }
                scene.settle(2)
                assertEquals(listOf(LauncherConfigs.packVersions("industrial")), toMain)
                assertEquals(1, raised)
            } finally {
                scene.close()
                collected.cancel()
            }
        }
    }

    @Test
    fun `a window the registry refuses has no host to hold anything against`() {
        val koin = koin()
        val registry = WindowRegistry(WindowPolicy.Single("one window"))
        var inMain: HostWindow? = null
        var inSecond: HostWindow? = HostWindow(WindowId("unset"), WindowOwner.Launcher, CompositionId("unset"), registry)
        onEventThread {
            val scene = draw(koin) {
                HostedWindow(WindowId.Main, WindowOwner.Launcher, FakeWindow(1000), registry) { inMain = LocalHostWindow.current }
                HostedWindow(WindowId.Console, WindowOwner.Launcher, FakeWindow(640), registry) { inSecond = LocalHostWindow.current }
            }
            try {
                assertEquals(WindowId.Main, inMain?.id)
                assertNull(inSecond, "refused, so its pickers run without a hold")
                assertEquals(listOf(WindowId.Main), registry.windows.value.map { it.id })
            } finally {
                scene.close()
            }
        }
    }

    /** Whoever draws the window, a widget on a page of the main window here, its level stands under its owner. */
    @Test
    fun `a window stands under its owner and not where it was opened from`() {
        val koin = koin()
        val registry = WindowRegistry()
        val inMain = Seen()
        val inPicker = Seen()
        val inConsole = Seen()
        var opener: WidgetScope? = null
        onEventThread {
            val scene = draw(koin) {
                HostedWindow(WindowId.Main, WindowOwner.Launcher, FakeWindow(1000), registry) {
                    Reader(inMain)
                    val main = assertNotNull(LocalHostWindow.current).composition
                    ChildLevel(ScopeLevel.Composition) {
                        opener = LocalScope.current
                        ChildLevel(ScopeLevel.Page) {
                            HostedWindow(WindowId("picker"), WindowOwner.Composition(main), FakeWindow(500), registry) { Reader(inPicker) }
                            HostedWindow(WindowId.Console, WindowOwner.Launcher, FakeWindow(640), registry) { Reader(inConsole) }
                        }
                    }
                }
            }
            try {
                val picker = assertNotNull(inPicker.level)
                val console = assertNotNull(inConsole.level)
                assertSame(opener, picker.parent, "the picker stands under the composition that opened it")
                assertNull(picker.nearest(ScopeLevel.Page), "and not under the page it was opened from")
                assertSame(koin.get<WidgetScope>(), console.parent, "the console stands under the app")

                assertEquals(3, registry.windows.value.size, "none of them was refused")
                assertEquals(1000, inMain.state?.width)
                assertEquals(500, inPicker.state?.width)
                assertEquals(640, inConsole.state?.width)
                assertNotSame(inMain.nav, inPicker.nav)
                assertNotSame(inMain.openProject, inPicker.openProject)
            } finally {
                scene.close()
            }
        }
    }

    /**
     * The arrangement the desktop has: the main window and the console are two
     * windows of the application, siblings in its composition (see ShellHost). The
     * main window hidden to the tray stays composed. The main window's composition
     * going, as on a crash restart, takes what it owns and leaves the console.
     */
    @Test
    fun `the console is a sibling of the main window and outlives its composition, what the main window owned does not`() {
        val koin = koin()
        val registry = WindowRegistry()
        val console = FakeWindow(640)
        val picker = FakeWindow(500)
        var mainShown by mutableStateOf(true)
        var mainHost: HostWindow? = null
        onEventThread {
            val scene = draw(koin) {
                if (mainShown) {
                    HostedWindow(WindowId.Main, WindowOwner.Launcher, FakeWindow(1000), registry) { mainHost = LocalHostWindow.current }
                }
                HostedWindow(WindowId.Console, WindowOwner.Launcher, console, registry) {}
            }
            try {
                val main = assertNotNull(mainHost)
                val opened = registry.register(WindowId("picker"), WindowOwner.Composition(main.composition), CompositionId("picker"), picker, CoroutineScope(Dispatchers.Unconfined))
                assertTrue(opened is Registration.Opened)

                mainShown = false
                scene.settle(4)

                assertEquals(listOf(WindowId.Console), registry.windows.value.map { it.id })
                assertEquals(1, picker.closed, "the picker went with the main window's composition")
                assertEquals(0, console.closed)
            } finally {
                scene.close()
            }
        }
    }
}
