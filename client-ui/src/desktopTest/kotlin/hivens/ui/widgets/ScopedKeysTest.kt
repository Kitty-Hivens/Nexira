package hivens.ui.widgets

import hivens.ui.STANDING_ROOT
import hivens.ui.documentOf
import hivens.widget.api.LocalDocument
import hivens.widget.model.CompositionName
import hivens.widget.model.Document
import hivens.ui.layout.DocumentStore
import hivens.ui.editor.DocumentEditor
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
import hivens.themes.Themes
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.theme.NxThemed
import hivens.ui.widgets.profile.ProfileSelection
import hivens.ui.widgets.profile.ProfileSelectionSource
import hivens.widget.api.CompositionKeys
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
import hivens.widget.model.History
import hivens.widget.model.ScopeLevel
import hivens.widget.model.Show
import hivens.widget.model.SlotContent
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
        declare(CompositionKeys.History)
        declare(SourceKey<String>("test.page.owned", ScopeLevel.Page))
    }

    private val commands = WidgetCommandRegistry().apply {
        declare(CompositionKeys.Back)
        declare(CompositionKeys.Show)
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
        val read = HashMap<String, History?>()
        val fire = HashMap<String, (() -> Unit)?>()
        val fired = ArrayList<String>()
        draw {
            listOf("a" to LauncherConfigs.Home, "b" to LauncherConfigs.Library).forEach { (name, config) ->
                ChildLevel(ScopeLevel.Window) {
                    ChildLevel(ScopeLevel.Composition) {
                        val back = rememberLatestCommand<Unit> { fired += name }
                        ProvideScoped(CompositionKeys.History, History(config)) {
                            ProvideScopedCommand(CompositionKeys.Back, back) {
                                ChildLevel(ScopeLevel.Page) {
                                    read[name] = rememberScopedSource(CompositionKeys.History).valueOrNull()
                                    fire[name] = rememberScopedAction(CompositionKeys.Back)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(LauncherConfigs.Home, read["a"]?.current?.config)
        assertEquals(LauncherConfigs.Library, read["b"]?.current?.config)
        assertNotNull(fire["a"]).invoke()
        assertEquals(listOf("a"), fired, "the other window's history was left alone")
        assertNotNull(fire["b"]).invoke()
        assertEquals(listOf("a", "b"), fired)
    }

    @Test
    fun `a composition key is missing outside the composition that provides it`() {
        var outside: History? = History(LauncherConfigs.Home)
        var show: ((Show) -> Unit)? = {}
        draw {
            ChildLevel(ScopeLevel.Window) {
                ChildLevel(ScopeLevel.Composition) {
                    outside = rememberScopedSource(CompositionKeys.History).valueOrNull()
                    show = rememberScopedCommand(CompositionKeys.Show)
                }
            }
        }
        assertNull(outside, "a composition level with nothing put over it has no history to show")
        assertNull(show, "and no command to move one")
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
                ProvideScoped(CompositionKeys.History, History(LauncherConfigs.Home)) {
                    ChildLevel(ScopeLevel.Page) {
                        answers["page"] = missingNeeds(needs)
                        ProvideScoped(SourceKey<String>("test.page.owned", ScopeLevel.Page), "here") {
                            ProvideScopedCommand(CompositionKeys.Back, command<Unit> { }) {
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

    /** An editor over a document of its own, for an editor that is on. */
    private fun editor(): DocumentEditor {
        val dir = Files.createTempDirectory("scoped-keys-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val document = Document(start = CompositionName("test"))
        return DocumentEditor(DocumentStore(dir.resolve("interface.json"), { document }), scope)
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

    private val graph = documentOf(SlotContent(widgets = listOf(WidgetInstance(kind = kind, instanceId = "one"))))

    @Test
    fun `a widget missing what it needs hands its place to the placeholder`() {
        var placed: List<String>? = null
        draw {
            CompositionLocalProvider(
                LocalDocument provides graph,
                LocalWidgetRegistry provides registry,
                LocalMisplacedWidget provides { descriptor, _, missing ->
                    if (descriptor === Needy) placed = missing
                },
            ) {
                ChildLevel(ScopeLevel.Composition) { SlotRenderer(STANDING_ROOT) }
            }
        }
        assertEquals(listOf("test.page.owned"), placed)
    }

    @Test
    fun `the same widget draws itself where its page provides what it needs`() {
        var placed: List<String>? = null
        draw {
            CompositionLocalProvider(
                LocalDocument provides graph,
                LocalWidgetRegistry provides registry,
                LocalMisplacedWidget provides { _, _, missing -> placed = missing },
            ) {
                ChildLevel(ScopeLevel.Composition) {
                    ChildLevel(ScopeLevel.Page) {
                        ProvideScoped(SourceKey<String>("test.page.owned", ScopeLevel.Page), "here") {
                            SlotRenderer(STANDING_ROOT)
                        }
                    }
                }
            }
        }
        assertNull(placed, "the body ran, which throws anywhere its page is missing")
    }

    @Test
    fun `the launcher's warning card draws in the theme in the editor, and nothing throws`() {
        val editing = EditModeState.On(editor())
        val inked = onEventThread {
            val scene = ImageComposeScene(240, 80, density = Density(1f)) {
                ShippedLanguageProvider("en") {
                    NxThemed(Themes.default, dark = true) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                            LocalDocument provides graph,
                            LocalWidgetRegistry provides registry,
                            LocalMisplacedWidget provides MisplacedWidget,
                            LocalEditMode provides editing,
                        ) {
                            Box(Modifier.fillMaxSize()) { SlotRenderer(STANDING_ROOT) }
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
