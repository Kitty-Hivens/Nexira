// The pages host keeps the page levels of its composition, which only the host and the modules it trusts reach.
@file:OptIn(HostApi::class)

package hivens.pages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import hivens.pages.api.HistoryEntry
import hivens.pages.api.PagesHostProps
import hivens.pages.api.PagesKeys
import hivens.pages.api.ScreenView
import hivens.pages.api.levelFor
import hivens.ui.nx.NxSwap
import hivens.widget.api.HostApi
import hivens.widget.api.LocalLevelHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.PageLevels
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotId
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * The page shown, drawn with the swap between pages. Each screen's content stands in this widget's slot named by the
 * screen's id, and a screen a module brought that the slot holds nothing for is drawn with the module's widget. Each
 * page is drawn on a level of its own and with its own saved state, kept while the page can still be brought back
 * ([PagesKeys.Kept]), so a scroll or an open tab is where it was left.
 *
 * Nothing is shown before a page is: where nothing says what is shown it draws the screen the composition starts on.
 */
@Widget(
    id          = "pages.host",
    displayName = "widget.pages.host",
    removable   = false,
    openSlots   = true,
    propsClass  = PagesHostProps::class,
)
@Composable
fun PagesHostWidget(instance: WidgetInstance) {
    val shown = rememberScopedSource(PagesKeys.Shown).valueOrNull()
    val start = rememberScopedSource(PagesKeys.Start).valueOrNull()
    val entry = shown ?: start?.let { HistoryEntry(it) } ?: return
    val screens = rememberScopedSource(PagesKeys.Screens).valueOrNull().orEmpty()
    val kept = rememberScopedSource(PagesKeys.Kept).valueOrNull()
    val retention = rememberSaveableStateHolder()
    val pages = LocalScope.current?.nearest(ScopeLevel.Composition)?.pages
    LaunchedEffect(pages, kept, entry.page) { pages?.keep((kept ?: emptySet()) + entry.page) }
    NxSwap(target = entry, modifier = Modifier.fillMaxSize(), label = "page", contentKey = { it.page }) { page ->
        Page(retention, pages, page) { ScreenContent(instance, page, screens.firstOrNull { it.id == page.screen }) }
    }
}

/**
 * What [entry]'s screen shows: the host's own slot for it where that holds something, or the widget of the module
 * that brought it otherwise. A screen a person made always draws its slot, empty or not, so the editor has somewhere
 * to put what is dropped on it.
 */
@Composable
private fun ScreenContent(host: WidgetInstance, entry: HistoryEntry, screen: ScreenView?) {
    val slot = SlotId(entry.screen.value)
    val own = host.children[slot]?.widgets?.isNotEmpty() == true
    val kind = screen?.kind
    if (own || kind == null) {
        SlotRenderer(host, slot, Modifier.fillMaxSize())
        return
    }
    val descriptor = LocalWidgetRegistry.current[kind] ?: return
    val drawn = remember(kind, entry.screen) { WidgetInstance(kind, "${host.instanceId}.${entry.screen.value}") }
    descriptor.Render(drawn)
}

/** One page: its saved state and its level, held under [entry]'s page while it is drawn. */
@Composable
private fun Page(retention: SaveableStateHolder, pages: PageLevels?, entry: HistoryEntry, content: @Composable () -> Unit) {
    retention.SaveableStateProvider(entry.page) {
        val level = pages?.let {
            val _ = remember(it, entry.page) { it.hold(entry.page) }
            it.levelFor(entry)
        }
        LocalLevelHost.current(level, content)
    }
}
