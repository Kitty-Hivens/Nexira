package hivens.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.Screen
import hivens.ui.editor.EditModeController
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.layout.LayoutGraphRepository
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.navigation.NavState
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.profile.ProfileSelection
import hivens.ui.widgets.profile.ProfileSelectionSource
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalMisplacedWidget
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.ProvideScoped
import hivens.widget.api.ProvideScopedCommand
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.missingNeeds
import hivens.widget.api.rememberLatestCommand
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SourceKey
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import org.jetbrains.skia.Bitmap
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface contexts as sources and commands: each composition keeps its own,
 * each page its own, a widget asks what it needs, and one standing where something
 * it needs has no answer draws the placeholder instead of taking the window down.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScopedKeysTest {

    private val data = WidgetDataRegistry().apply {
        register(SourceKey<String>("test.app"), flowSource(MutableStateFlow("app")))
        registerFactory(ProfileSelectionSource) { flowSource(MutableStateFlow(ProfileSelection())) }
        declare(Sources.Navigation)
        declare(SourceKey<String>("test.page.owned", ScopeLevel.Page))
    }

    private val commands = WidgetCommandRegistry().apply {
        declare(Commands.Back)
        declare(Commands.Navigate)
    }

    private val app: WidgetScope = koinApplication {
        modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) } })
    }.koin.get()

    private fun draw(between: (ImageComposeScene) -> Unit = {}, content: @Composable () -> Unit) = onEventThread {
        val scene = ImageComposeScene(200, 200, density = Density(1f)) {
            CompositionLocalProvider(
                LocalWidgetDataRegistry provides data,
                LocalWidgetCommandRegistry provides commands,
                LocalScope provides app,
            ) { content() }
        }
        try {
            scene.settle(4)
            between(scene)
        } finally {
            scene.close()
        }
    }

    /** Reads [key] and counts how often it was composed to do so. */
    @Composable
    private fun CountingReader(key: SourceKey<String>, counts: MutableMap<String, Int>) {
        counts[key.id] = (counts[key.id] ?: 0) + 1
        val _ = rememberScopedSource(key)
    }

    @Test
    fun `a new value recomposes the readers of its key and no other reader`() {
        val first = SourceKey<String>("test.first", ScopeLevel.Composition)
        val second = SourceKey<String>("test.second", ScopeLevel.Composition)
        val counts = HashMap<String, Int>()
        val value = mutableStateOf("a")
        var before: Map<String, Int> = emptyMap()
        draw(
            between = { scene ->
                before = counts.toMap()
                value.value = "b"
                Snapshot.sendApplyNotifications()
                scene.settle(4)
            },
        ) {
            ChildLevel(ScopeLevel.Composition) {
                ProvideScoped(first, value.value) {
                    ProvideScoped(second, "constant") {
                        CountingReader(first, counts)
                        CountingReader(second, counts)
                    }
                }
            }
        }
        assertEquals((before["test.first"] ?: 0) + 1, counts["test.first"], "the reader of the changed key sees the change")
        assertEquals(before["test.second"], counts["test.second"], "a reader of another key below the same provider is left alone")
    }

    @Test
    fun `two windows keep their own history and fire their own commands`() {
        val read = HashMap<String, NavState?>()
        val fire = HashMap<String, (() -> Unit)?>()
        val fired = ArrayList<String>()
        draw {
            listOf("a" to Screen.Home, "b" to Screen.Library).forEach { (name, screen) ->
                ChildLevel(ScopeLevel.Window) {
                    ChildLevel(ScopeLevel.Composition) {
                        val back = rememberLatestCommand<Unit> { fired += name }
                        ProvideScoped(Sources.Navigation, NavState(screen, listOf(screen), canGoBack = false, canGoForward = false)) {
                            ProvideScopedCommand(Commands.Back, back) {
                                ChildLevel(ScopeLevel.Page) {
                                    read[name] = rememberScopedSource(Sources.Navigation).valueOrNull()
                                    fire[name] = rememberScopedAction(Commands.Back)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(Screen.Home, read["a"]?.current)
        assertEquals(Screen.Library, read["b"]?.current)
        assertNotNull(fire["a"]).invoke()
        assertEquals(listOf("a"), fired, "the other window's history was left alone")
        assertNotNull(fire["b"]).invoke()
        assertEquals(listOf("a", "b"), fired)
    }

    @Test
    fun `a composition key is missing outside the composition that provides it`() {
        var outside: NavState? = NavState(Screen.Home, emptyList(), canGoBack = false, canGoForward = false)
        var navigate: ((Screen) -> Unit)? = {}
        draw {
            ChildLevel(ScopeLevel.Window) {
                ChildLevel(ScopeLevel.Composition) {
                    outside = rememberScopedSource(Sources.Navigation).valueOrNull()
                    navigate = rememberScopedCommand(Commands.Navigate)
                }
            }
        }
        assertNull(outside, "a composition level with nothing put over it has no history to show")
        assertNull(navigate, "and no command to move one")
    }

    @Test
    fun `two pages each make their own selection, and the shell has none`() {
        val made = HashMap<String, ProfileSelection?>()
        draw {
            ChildLevel(ScopeLevel.Composition) {
                ChildLevel(ScopeLevel.Page) { made["first"] = rememberScopedSource(ProfileSelectionSource).valueOrNull() }
                ChildLevel(ScopeLevel.Page) { made["second"] = rememberScopedSource(ProfileSelectionSource).valueOrNull() }
                made["shell"] = rememberScopedSource(ProfileSelectionSource).valueOrNull()
            }
        }
        val first = assertNotNull(made["first"])
        val second = assertNotNull(made["second"])
        assertNotSame(first, second)
        assertNull(made["shell"], "the shell is drawn outside any page")
    }

    @Test
    fun `missing needs are answered the way a read would answer them`() {
        val answers = HashMap<String, List<String>>()
        val needs = listOf("test.app", "nav.state", "test.page.owned", "profile.category", "nav.back", "nobody.declares")
        draw {
            ChildLevel(ScopeLevel.Composition) {
                answers["composition"] = missingNeeds(needs)
                ProvideScoped(Sources.Navigation, NavState(Screen.Home, emptyList(), canGoBack = false, canGoForward = false)) {
                    ChildLevel(ScopeLevel.Page) {
                        answers["page"] = missingNeeds(needs)
                        ProvideScoped(SourceKey<String>("test.page.owned", ScopeLevel.Page), "here") {
                            ProvideScopedCommand(Commands.Back, command<Unit> { }) {
                                answers["provided"] = missingNeeds(needs)
                            }
                        }
                    }
                }
            }
        }
        assertEquals(
            listOf("nav.state", "test.page.owned", "profile.category", "nav.back", "nobody.declares"),
            answers["composition"],
        )
        assertEquals(listOf("test.page.owned", "nav.back", "nobody.declares"), answers["page"])
        assertEquals(listOf("nobody.declares"), answers["provided"], "an id nothing declares is missing everywhere")
    }

    // ── The placeholder ───────────────────────────────────────────────

    /** A controller over a layout file of its own, for an editor that is on. */
    private fun editController(): EditModeController {
        val dir = Files.createTempDirectory("scoped-keys-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = LayoutGraphRepository(dir.resolve("layout-graph.json"), Json { ignoreUnknownKeys = true }, scope) { LayoutGraph.EMPTY }
        return EditModeController(repo, scope)
    }

    private val kind = WidgetKind("test.needy")

    /** A widget whose body must never run where its page is not: it throws if it does. */
    private object Needy : WidgetDescriptor {
        override val kind = WidgetKind("test.needy")
        override val displayName = "Needy"
        override val removable = true
        override val needs = listOf("test.page.owned")

        @Composable
        override fun Render(instance: WidgetInstance) {
            val value = rememberScopedSource(SourceKey<String>("test.page.owned", ScopeLevel.Page)).valueOrNull()
            checkNotNull(value) { "composed where its page is not" }
        }
    }

    private val registry = object : WidgetRegistry {
        override fun all() = mapOf(kind to Needy)
        override fun get(kind: WidgetKind) = Needy.takeIf { it.kind == kind }
    }

    private val graph = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(widgets = listOf(WidgetInstance(kind = kind, instanceId = "one")))),
            ),
        ),
    )

    @Test
    fun `a widget missing what it needs hands its place to the placeholder`() {
        var placed: List<String>? = null
        draw {
            CompositionLocalProvider(
                LocalLayoutGraph provides graph,
                LocalWidgetRegistry provides registry,
                LocalMisplacedWidget provides { descriptor, _, missing ->
                    if (descriptor === Needy) placed = missing
                },
            ) {
                ChildLevel(ScopeLevel.Composition) { SlotRenderer(SurfaceId("s"), SlotId("main")) }
            }
        }
        assertEquals(listOf("test.page.owned"), placed)
    }

    @Test
    fun `the same widget draws itself where its page provides what it needs`() {
        var placed: List<String>? = null
        draw {
            CompositionLocalProvider(
                LocalLayoutGraph provides graph,
                LocalWidgetRegistry provides registry,
                LocalMisplacedWidget provides { _, _, missing -> placed = missing },
            ) {
                ChildLevel(ScopeLevel.Composition) {
                    ChildLevel(ScopeLevel.Page) {
                        ProvideScoped(SourceKey<String>("test.page.owned", ScopeLevel.Page), "here") {
                            SlotRenderer(SurfaceId("s"), SlotId("main"))
                        }
                    }
                }
            }
        }
        assertNull(placed, "the body ran, which throws anywhere its page is missing")
    }

    @Test
    fun `the launcher's warning card draws in the theme in the editor, and nothing throws`() {
        val editing = EditModeState.On(SurfaceId("s"), editController())
        val inked = onEventThread {
            val scene = ImageComposeScene(240, 80, density = Density(1f)) {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                            LocalLayoutGraph provides graph,
                            LocalWidgetRegistry provides registry,
                            LocalMisplacedWidget provides MisplacedWidget,
                            LocalEditMode provides editing,
                        ) {
                            Box(Modifier.fillMaxSize()) { SlotRenderer(SurfaceId("s"), SlotId("main")) }
                        }
                    }
                }
            }
            try {
                val image = scene.render(scene.settle(6))
                val bitmap = Bitmap.makeFromImage(image)
                try {
                    val info = bitmap.imageInfo
                    val pixels = assertNotNull(bitmap.readPixels(info, info.minRowBytes, 0, 0))
                    (pixels.indices step 4).count { pixels[it + 3] != 0.toByte() }
                } finally {
                    bitmap.close()
                    image.close()
                }
            } finally {
                scene.close()
            }
        }
        assertTrue(inked > 200, "the placeholder drew next to nothing ($inked pixels)")
    }
}
