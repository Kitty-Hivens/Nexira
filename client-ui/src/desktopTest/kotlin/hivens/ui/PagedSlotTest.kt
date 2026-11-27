package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.navigation.LauncherScreens
import hivens.ui.navigation.PackSettingsPage
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.LocalScope
import hivens.pages.api.PageKeys
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.history.api.History
import hivens.pages.api.HistoryEntry
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import hivens.pages.api.read

/**
 * A slot drawn on the pages of a composition's history: entries of one page differ by
 * what the page noted on them, and share the page's saved state and level. Content no
 * entry holds as its own is one content across them.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PagedSlotTest {

    /** The slot as a config's own on every entry, unless [own] says otherwise. */
    @Composable
    private fun Router(history: History, own: (HistoryEntry) -> Boolean = { true }, content: @Composable (HistoryEntry) -> Unit) {
        val retention = rememberSaveableStateHolder()
        val pages = LocalScope.current?.nearest(ScopeLevel.Composition)?.pages
        PagedSlot(retention, pages, history, slotKey = "slot", own = own, content = content)
    }

    private val pack = History(LauncherScreens.Home).show(LauncherScreens.pack("a"))
    private val noted = pack.rewrite(mapOf(PackSettingsPage.of("Version")))

    /** Draws [content] for [start], then for what each of [moves] makes of it, and answers what was thrown. */
    private fun route(start: History, vararg moves: History.() -> History, content: @Composable (History) -> Unit): List<String> {
        var history by mutableStateOf(start)
        // An image scene hands what a window would throw to its coroutine context instead.
        val errors = ArrayList<Throwable>()
        onEventThread {
            val scene = ImageComposeScene(200, 200, Density(1f), Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> errors += e }) {
                content(history)
            }
            try {
                var t = 0L
                fun frames() = repeat(40) {
                    t += 16_000_000L
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

    // A pack page with its settings noted, and a history that comes to stand on the same
    // page without the note: two entries with one page, so the swap drew both while it
    // faded from one to the other, each under the same saved state, and the window went down.
    @Test
    fun `two entries of one page in a row do not take the window down`() {
        val drawn = ArrayList<HistoryEntry>()
        val errors = route(noted, { rewrite(mapOf(PackSettingsPage.id to null)) }, { show(LauncherScreens.packVersions("a")) }) { history ->
            Router(history) { entry ->
                val visits by rememberSaveable { mutableStateOf(0) }
                drawn += entry
                Box(Modifier.size((10 + visits).dp))
            }
        }
        assertEquals(emptyList(), errors)
        assertEquals(LauncherScreens.PackVersions, drawn.last().screen)
    }

    /** An app level reading the page's entry and the pack page's note, for [inComposition]. */
    private val app: WidgetScope by lazy {
        val data = WidgetDataRegistry().apply {
            declare(PageKeys.Entry)
            registerFactory(PackSettingsPage.key) { page -> page.read(PackSettingsPage) }
        }
        koinApplication {
            modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Default) } })
        }.koin.get()
    }

    /** [content] inside a composition's level, which keeps the page levels. */
    @Composable
    private fun inComposition(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalScope provides app) {
            ChildLevel(ScopeLevel.Composition, content)
        }
    }

    /** What a page reads of its own entry, as it stands on each pass it is drawn on. */
    private fun reads(start: History, vararg moves: History.() -> History): List<Pair<HistoryEntry?, String?>> {
        val seen = ArrayList<Pair<HistoryEntry?, String?>>()
        val errors = route(start, *moves) { history ->
            CompositionLocalProvider(LocalScope provides app) {
                ChildLevel(ScopeLevel.Composition) {
                    Router(history) { entry ->
                        if (entry.screen == LauncherScreens.Pack) {
                            seen += rememberScopedSource(PageKeys.Entry).valueOrNull() to rememberScopedSource(PackSettingsPage.key).valueOrNull()
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), errors)
        return seen
    }

    @Test
    fun `a page reads the values it was opened with from its first pass`() {
        assertEquals(listOf<Pair<HistoryEntry?, String?>>(pack.current to null), reads(pack).distinct())
        assertEquals(listOf<Pair<HistoryEntry?, String?>>(noted.current to "Version"), reads(noted).distinct())
    }

    // The note is made in the press that leads away, so the page is never drawn with it before it comes back.
    @Test
    fun `a page come back to reads what it noted as it left, from the first pass`() {
        val seen = reads(
            pack,
            { rewrite(mapOf(PackSettingsPage.of("Version"))).show(LauncherScreens.packVersions("a")) },
            { back() },
        )
        assertEquals(null, seen.first().second)
        val back = seen.dropWhile { it.second == null }
        assertEquals(listOf<Pair<HistoryEntry?, String?>>(noted.current to "Version"), back.distinct())
        assertEquals(setOf(pack.current, noted.current), seen.map { it.first }.toSet())
        assertEquals(1, seen.filter { it.first == noted.current }.map { it.second }.distinct().size, "no pass with the new entry read the old note")
    }

    @Test
    fun `content no entry holds as its own is one content and keeps its state across entries`() {
        val counts = ArrayList<Int>()
        val errors = route(pack, { show(LauncherScreens.packVersions("a")) }, { back() }) { history ->
            Router(history, own = { false }) { _ ->
                var composed by remember { mutableStateOf(0) }
                SideEffect { composed++ }
                counts += composed
            }
        }
        assertEquals(emptyList(), errors)
        assertEquals(counts.sorted(), counts, "never started over: $counts")
    }

    @Test
    fun `content that becomes an entry's own swaps onto that entry's page`() {
        val seen = ArrayList<Pair<HistoryEntry, Boolean>>()
        val errors = route(pack, { show(LauncherScreens.packVersions("a")) }) { history ->
            inComposition {
                Router(history, own = { it.screen == LauncherScreens.PackVersions }) { entry ->
                    seen += entry to (LocalScope.current?.level == ScopeLevel.Page)
                }
            }
        }
        assertEquals(emptyList(), errors)
        assertEquals(setOf(false), seen.filter { it.first.screen == LauncherScreens.Pack }.map { it.second }.toSet(), "shared content has no page")
        assertEquals(setOf(true), seen.filter { it.first.screen == LauncherScreens.PackVersions }.map { it.second }.toSet(), "its own content is on its page")
    }
}
