package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.history.api.History
import hivens.pages.PagesHostWidget
import hivens.pages.api.HistoryEntry
import hivens.pages.api.PageKeys
import hivens.pages.api.PagesHost
import hivens.pages.api.PagesKeys
import hivens.pages.api.ScreenId
import hivens.pages.api.ScreenView
import hivens.pages.api.Show
import hivens.pages.api.read
import hivens.ui.navigation.LauncherScreens
import hivens.ui.navigation.PackSettingsPage
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.ProvideScoped
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pages host inside the one config of a composition: the frame around it is one node whatever screen shows, each
 * screen's content is made for its page, on its page's level and with its page's saved state, a widget of the frame
 * follows the screen shown by the places it names, and a screen the host holds nothing for draws what its module brought.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PagesHostTest {

    /** An app level the composition's level stands under, reading a page's entry and the pack page's note. */
    private val app: WidgetScope = koinApplication {
        val data = WidgetDataRegistry().apply {
            declare(PageKeys.Entry)
            registerFactory(PackSettingsPage.key) { page -> page.read(PackSettingsPage) }
        }
        modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined) } })
    }.koin.get()

    /** How many times each widget was made, by name. */
    private val made = HashMap<String, Int>()

    /** Whether each widget, by name, was drawn on a page's level, on every pass it was drawn on. */
    private val onPage = HashMap<String, MutableSet<Boolean>>()

    /** What the pack's page read of its own entry and of its note, on each pass it was drawn on. */
    private val seen = ArrayList<Pair<HistoryEntry?, String?>>()

    private val frame = WidgetKind("test.frame")
    private val leaf = WidgetKind("test.leaf")
    private val saving = WidgetKind("test.saving")
    private val reader = WidgetKind("test.reader")
    private val body = SlotId("body")
    private val side = SlotId("side")
    private val bottom = SlotId("bottom")

    private val brought = ScreenId("brought")
    private val notes = ScreenId("notes")
    private val main = ConfigId("main")
    private val composition = CompositionName("test")

    private fun leaf(id: String, shownOn: List<String>? = null) = SlotContent(listOf(WidgetInstance(leaf, id, shownOn = shownOn)))

    private val host = WidgetInstance(
        PagesHost.KIND,
        "host",
        children = mapOf(
            SlotId(LauncherScreens.Home.value) to leaf("on-home"),
            SlotId(LauncherScreens.Library.value) to leaf("on-library"),
            SlotId(LauncherScreens.Pack.value) to SlotContent(listOf(WidgetInstance(reader, "pack-reader"), WidgetInstance(saving, "pack-saving"))),
            SlotId(LauncherScreens.PackVersions.value) to leaf("on-versions"),
            SlotId(notes.value) to SlotContent(),
        ),
    )

    private val document = Document(
        start = composition,
        compositions = mapOf(
            composition to Composition(
                start = main,
                configs = mapOf(
                    main to Config(
                        layouts = listOf(
                            ConfigLayout(
                                LayoutId("main"),
                                SlotContent(
                                    listOf(
                                        WidgetInstance(
                                            frame,
                                            "frame",
                                            children = mapOf(
                                                body to SlotContent(listOf(host)),
                                                side to leaf("block"),
                                                bottom to leaf("only-on-library", shownOn = listOf(LauncherScreens.Library.value)),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val screens = listOf(LauncherScreens.Home, LauncherScreens.Library, LauncherScreens.Pack, LauncherScreens.PackVersions)
        .map { ScreenView(it, it.value, owner = "test") } +
        ScreenView(brought, "brought", owner = "test", kind = leaf) +
        ScreenView(notes, "Notes", owner = null, made = true)

    private inner class Counted(override val kind: WidgetKind) : WidgetDescriptor {
        override val displayName = kind.value
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            remember(instance.instanceId) { made.merge(instance.instanceId, 1, Int::plus) }
            onPage.getOrPut(instance.instanceId) { HashSet() } += LocalScope.current?.level == ScopeLevel.Page
            Box(Modifier.size(4.dp))
        }
    }

    private inner class Saving : WidgetDescriptor {
        override val kind = saving
        override val displayName = "saving"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            val visits by rememberSaveable { mutableStateOf(0) }
            Box(Modifier.size((4 + visits).dp))
        }
    }

    private inner class Reader : WidgetDescriptor {
        override val kind = reader
        override val displayName = "reader"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            seen += rememberScopedSource(PageKeys.Entry).valueOrNull() to rememberScopedSource(PackSettingsPage.key).valueOrNull()
        }
    }

    private object Frame : WidgetDescriptor {
        override val kind = WidgetKind("test.frame")
        override val displayName = "frame"
        override val removable = false
        override val slots = listOf(SlotId("body"), SlotId("side"), SlotId("bottom"))

        @Composable
        override fun Render(instance: WidgetInstance) {
            Box(Modifier.fillMaxSize()) {
                SlotRenderer(instance, SlotId("body"))
                SlotRenderer(instance, SlotId("side"))
                SlotRenderer(instance, SlotId("bottom"))
            }
        }
    }

    private object Host : WidgetDescriptor {
        override val kind = PagesHost.KIND
        override val displayName = "host"
        override val removable = false
        override val openSlots = true

        @Composable
        override fun Render(instance: WidgetInstance) = PagesHostWidget(instance)
    }

    private val registry = object : WidgetRegistry {
        private val kinds = listOf(Frame, Host, Counted(leaf), Saving(), Reader()).associateBy { it.kind }

        override fun all(): Map<WidgetKind, WidgetDescriptor> = kinds

        override fun get(kind: WidgetKind): WidgetDescriptor? = kinds[kind]
    }

    /** Draws the composition from [start] through each of [moves], letting each swap finish, and answers what was thrown. */
    private fun visit(start: History = History(LauncherScreens.Home), vararg moves: History.() -> History): List<String> {
        var history by mutableStateOf(start)
        // An image scene hands what a window would throw to its coroutine context instead.
        val errors = ArrayList<Throwable>()
        onEventThread {
            val scene = ImageComposeScene(200, 200, Density(1f), Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> errors += e }) {
                CompositionLocalProvider(LocalWidgetRegistry provides registry, LocalScope provides app) {
                    ChildLevel(ScopeLevel.Composition) {
                        ProvideScoped(PagesKeys.Screens, screens) {
                            ProvideScoped(PagesKeys.Shown, history.current) {
                                ProvideWhere(main) {
                                    ConfigView(composition, document, main, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
            try {
                var t = 0L
                fun frames() = repeat(40) {
                    t += FRAME_NANOS
                    scene.render(t).close()
                }
                frames()
                moves.forEach { move ->
                    history = history.move()
                    Snapshot.sendApplyNotifications()
                    frames()
                }
            } finally {
                scene.close()
            }
        }
        return errors.map { it.toString() }
    }

    private fun visit(vararg moves: History.() -> History): List<String> = visit(History(LauncherScreens.Home), *moves)

    // The frame

    @Test
    fun `a widget of the frame is one node whatever screen shows`() {
        assertEquals(emptyList(), visit({ show(Show(LauncherScreens.Library)) }, { back() }))
        assertEquals(1, made["block"], "made once: $made")
    }

    @Test
    fun `a widget of the frame shown on another screen is not drawn`() {
        visit()
        assertEquals(null, made["only-on-library"], "$made")
    }

    @Test
    fun `a widget of the frame shown on one screen is drawn there only`() {
        visit({ show(Show(LauncherScreens.Library)) }, { back() })
        assertEquals(1, made["only-on-library"], "made on the library and nowhere else: $made")
    }

    // The screens

    @Test
    fun `a screen's content is made for its page, and on its page's level`() {
        visit({ show(Show(LauncherScreens.Library)) })
        assertEquals(1, made["on-home"], "$made")
        assertEquals(1, made["on-library"], "$made")
        assertEquals<Set<Boolean>?>(setOf(true), onPage["on-library"], "a screen's content is on its page")
        assertEquals<Set<Boolean>?>(setOf(false), onPage["block"], "the frame is on no page")
    }

    @Test
    fun `a screen the host holds nothing for draws what its module brought`() {
        visit({ show(Show(brought)) })
        assertEquals(1, made["host.brought"], "$made")
    }

    @Test
    fun `a screen a person made draws its slot, empty as it is`() {
        val before = visit()
        val after = visit({ show(Show(notes)) })
        assertEquals(emptyList(), before + after)
        assertEquals(null, made.keys.firstOrNull { it.startsWith("host.") }, "nothing is drawn in its place: $made")
    }

    // Pages of one screen

    private val pack = History(LauncherScreens.Home).show(LauncherScreens.pack("a"))
    private val noted = pack.rewrite(mapOf(PackSettingsPage.of("Version")))

    // A pack page with its settings noted, and a history that comes to stand on the same page without the note: two
    // entries with one page, so the swap drew both while it faded from one to the other, each under the same saved
    // state, and the window went down.
    @Test
    fun `two entries of one page in a row do not take the window down`() {
        val errors = visit(noted, { rewrite(mapOf(PackSettingsPage.id to null)) }, { show(LauncherScreens.packVersions("a")) })
        assertEquals(emptyList(), errors)
        assertEquals(1, made["on-versions"], "$made")
    }

    @Test
    fun `a page reads the values it was opened with from its first pass`() {
        assertEquals(emptyList(), visit(pack))
        assertEquals(listOf<Pair<HistoryEntry?, String?>>(pack.current to null), seen.distinct())
        seen.clear()
        assertEquals(emptyList(), visit(noted))
        assertEquals(listOf<Pair<HistoryEntry?, String?>>(noted.current to "Version"), seen.distinct())
    }

    // The note is made in the press that leads away, so the page is never drawn with it before it comes back.
    @Test
    fun `a page come back to reads what it noted as it left, from the first pass`() {
        val errors = visit(
            pack,
            { rewrite(mapOf(PackSettingsPage.of("Version"))).show(LauncherScreens.packVersions("a")) },
            { back() },
        )
        assertEquals(emptyList<String>(), errors)
        assertEquals(null, seen.first().second)
        val back = seen.dropWhile { it.second == null }
        assertEquals(listOf<Pair<HistoryEntry?, String?>>(noted.current to "Version"), back.distinct())
        assertEquals(setOf(pack.current, noted.current), seen.map { it.first }.toSet())
        assertEquals(1, seen.filter { it.first == noted.current }.map { it.second }.distinct().size, "no pass with the new entry read the old note")
    }
}
