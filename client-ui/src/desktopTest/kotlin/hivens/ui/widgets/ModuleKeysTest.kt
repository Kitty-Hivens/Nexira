package hivens.ui.widgets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.accounts.AccountsModule
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountsApi
import hivens.packs.PacksModule
import hivens.packs.api.PackKeys
import hivens.packs.api.PacksApi
import hivens.ui.HostKeys
import hivens.ui.background.BackgroundSettings
import hivens.ui.host.LauncherHostAccess
import hivens.ui.moduleKeys
import hivens.ui.modulesNamedBy
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.widget.api.ContributedServices
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.api.rememberCommand
import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document decides which modules bring their keys. One that does not name the
 * packs module leaves the pack keys absent, and a built-in widget that needs them
 * hands its place to the placeholder rather than drawing on nothing or throwing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ModuleKeysTest {

    private val packList = WidgetKind("home.new.packlist")

    private val graph = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(widgets = listOf(WidgetInstance(kind = packList, instanceId = "one")))),
            ),
        ),
    )

    private class Host(named: Set<String>) {
        val data = uiDataRegistry(
            activity         = MutableStateFlow(emptyList()),
            notifications    = MutableStateFlow(emptyList()),
            doNotDisturb     = MutableStateFlow(false),
            sslBypass        = flowSource(MutableStateFlow(false)),
            background       = MutableStateFlow(BackgroundSettings()),
        )
        val commands = uiCommandRegistry({}, {}, {})
        val keys = HostKeys(data, commands, ContributedServices()).also {
            moduleKeys(it, named, compiled = listOf(PacksModule, AccountsModule), jars = emptyList())
        }
        val app: WidgetScope = koinApplication {
            modules(
                module {
                    single {
                        WidgetScope.app(
                            data, keys.appServices(KoinLevelServicesHost.app(this), Dispatchers.Unconfined), Dispatchers.Unconfined,
                            commands, access = LauncherHostAccess,
                        )
                    }
                },
            )
        }.koin.get()
    }

    /** What the placeholder was handed for the pack list, or null where the pack list drew itself. */
    private fun placed(host: Host): List<String>? {
        var placed: List<String>? = null
        var stop: ((String) -> Unit)? = null
        onEventThread {
            val scene = ImageComposeScene(200, 200, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalWidgetDataRegistry provides host.data,
                    LocalWidgetCommandRegistry provides host.commands,
                    LocalScope provides host.app,
                    LocalLayoutGraph provides graph,
                    LocalWidgetRegistry provides GeneratedWidgetRegistry,
                    LocalMisplacedWidget provides { descriptor, _, missing -> if (descriptor.kind == packList) placed = missing },
                ) {
                    ChildLevel(ScopeLevel.Window) {
                        ChildLevel(ScopeLevel.Composition) {
                            SlotRenderer(SurfaceId("s"), SlotId("main"))
                            // The plain app read, which a module widget might use.
                            stop = rememberCommand(PackKeys.StopPack)
                        }
                    }
                }
            }
            try {
                scene.settle(4)
            } finally {
                scene.close()
            }
        }
        // Fired where nothing answers it, it does nothing and says so in the log.
        assertNotNull(stop).invoke("p1")
        return placed
    }

    @Test
    fun `without the packs module named, the pack list stands misplaced for want of the pack keys`() {
        val host = Host(named = emptySet())
        listOf(PackKeys.LIBRARY_ID, PackKeys.LAUNCH_PACK_ID).forEach { assertNull(host.app.declarationOf(it), "$it came in unnamed") }
        val missing = assertNotNull(placed(host), "the pack list drew without its keys")
        assertTrue(PackKeys.LIBRARY_ID in missing && PackKeys.LAUNCH_PACK_ID in missing, "missing: $missing")
    }

    @Test
    fun `with the packs module named and not the accounts module, the pack list needs no session`() {
        val host = Host(named = setOf(PacksApi.ID))
        assertNull(host.app.declarationOf(AccountKeys.SESSION_ID), "the session came in unnamed")
        val missing = assertNotNull(placed(host))
        assertEquals(listOf(Commands.Navigate.id), missing, "the packs show without anybody signed in, and only the history is wanting")
    }

    @Test
    fun `with the packs and accounts modules named, only the history the page provides is wanting`() {
        val missing = assertNotNull(placed(Host(named = setOf(PacksApi.ID, AccountsApi.ID))))
        assertEquals(listOf(Commands.Navigate.id), missing, "the pack and session keys answer, and nothing put the history over the composition")
    }

    @Test
    fun `a document that says nothing takes the bundled default's modules, and one that names none takes none`() {
        val standard = listOf(PacksApi.ID)
        assertEquals(setOf(PacksApi.ID), modulesNamedBy(LayoutGraph()) { standard })
        assertEquals(emptySet(), modulesNamedBy(LayoutGraph(modules = emptyList())) { standard })
        assertEquals(setOf("someone.else"), modulesNamedBy(LayoutGraph(modules = listOf("someone.else"))) { standard })
    }
}
