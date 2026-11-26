package hivens.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import hivens.ui.nx.NxSwap
import hivens.ui.widgets.LevelHost
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalSingleSlotHost
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.PageLevels
import hivens.widget.api.SingleSlotHost
import hivens.widget.api.SlotRenderer
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.widget.model.History
import hivens.widget.model.HistoryEntry
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.ScopeLevel
import hivens.widget.model.contentAt
import hivens.widget.model.heldOwnAnywhere
import hivens.widget.model.holdsOwn

/**
 * The config [history] shows of the composition [name] in [document], in the layout that fits the area it is drawn
 * in. Nothing where the composition or the config is not in the document.
 *
 * Drawn at one place whichever config it is, so a widget the composition has once stands as the same node in every
 * config that has it and keeps its state across a change. What a config holds as its own in a slot its widget says holds
 * a page ([hivens.widget.api.WidgetDescriptor.pages]) is drawn on pages ([PagedSlot]): it is the part that swaps and
 * the part that reads what the config was opened with. Any other slot is drawn where it stands, at the composition's
 * level, and changes in place.
 */
@Composable
internal fun ConfigView(name: CompositionName, document: Document, history: History, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val composition = document.compositions[name] ?: return@BoxWithConstraints
        val width = maxWidth.value
        val height = maxHeight.value
        val layoutOf = { config: ConfigId -> composition.configs[config]?.layoutFor(width, height)?.id }
        val pages = rememberConfigPages(document, history, layoutOf)
        val config = history.current.config
        val layout = layoutOf(config) ?: return@BoxWithConstraints
        CompositionLocalProvider(LocalDocument provides document, LocalSingleSlotHost provides pages) {
            SlotRenderer(NodePath(name, NodeRoot.Layout(config, layout)), Modifier.fillMaxSize())
        }
    }
}

/**
 * How a slot of a widget the composition has once is drawn here. A slot that holds a page and that some config holds as
 * its own is drawn through [PagedSlot], each config's own content on a page of that config's, the shared content on
 * none. Any other is drawn where it stands, so what stands in it keeps its node across a change of config.
 */
@Composable
private fun rememberConfigPages(document: Document, history: History, layoutOf: (ConfigId) -> LayoutId?): SingleSlotHost {
    val retention = rememberSaveableStateHolder()
    val registry = LocalWidgetRegistry.current
    val pages = LocalScope.current?.nearest(ScopeLevel.Composition)?.pages
    val reachable = history.reachable.mapTo(HashSet()) { it.page }
    LaunchedEffect(pages, reachable) { pages?.keep(reachable) }
    val now = rememberUpdatedState(Shown(document, history, layoutOf))
    return remember(retention, pages, registry) {
        { path, modifier, slot ->
            val (doc, shown, layouts) = now.value
            if (!doc.holdsPage(path, registry) || !doc.heldOwnAnywhere(path)) {
                slot(path, modifier)
            } else {
                // The same slot under the root of the config an entry shows.
                val at = { entry: HistoryEntry -> layouts(entry.config)?.let { path.copy(root = NodeRoot.Layout(entry.config, it)) } }
                val own = { entry: HistoryEntry -> at(entry)?.let(doc::holdsOwn) == true }
                PagedSlot(retention, pages, shown, slotKey = path.nested.toString(), modifier, own = own) { entry ->
                    at(entry)?.let { slot(it, Modifier) }
                }
            }
        }
    }
}

/** Whether the widget [path] is a slot of declares that slot holds a page. */
private fun Document.holdsPage(path: NodePath, registry: WidgetRegistry): Boolean {
    val segment = path.nested.lastOrNull() ?: return false
    val parent = path.parentPath?.let(::contentAt)?.widgets?.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return false
    return segment.slot in registry[parent.kind]?.pages.orEmpty()
}

private data class Shown(val document: Document, val history: History, val layoutOf: (ConfigId) -> LayoutId?)

/**
 * One slot as the entry [history] stands on draws it, swapped with the swap primitive's transition for the next.
 *
 * Content [own] says is an entry's own is drawn on that entry's page: in its own saveable state and page level, both
 * held under the entry's page, so a scroll position, an open tab or a chosen category is kept for as long as the page
 * is in the history, and what the page was opened with is read from its first pass. Two entries of one page are one
 * content, which the entry changes in place: drawn side by side during the swap they held one saved state twice, which
 * throws. Content that is no entry's own is one content across entries and never swaps.
 *
 * [slotKey] tells this slot's saved state from another slot's on the same page.
 */
@Composable
internal fun PagedSlot(
    retention: SaveableStateHolder,
    pages: PageLevels?,
    history: History,
    slotKey: String,
    modifier: Modifier = Modifier,
    own: (HistoryEntry) -> Boolean,
    content: @Composable (HistoryEntry) -> Unit,
) {
    NxSwap(
        target     = history.current,
        modifier   = modifier,
        label      = "config",
        contentKey = { if (own(it)) it.page else SHARED },
    ) { entry ->
        if (own(entry)) Page(retention, pages, entry, "${entry.page}|$slotKey") { content(entry) } else content(entry)
    }
}

/** The key of content that is no entry's own, which every such entry shares. */
private object SHARED

/**
 * One page: its saveable state under [saveKey] and its page level, held under the [entry]'s page. The content holds
 * its level from the moment it composes, so a page that left the history keeps its level until it has finished fading
 * out, and one that just arrived cannot be swept by another leaving in its first frame.
 */
@Composable
private fun Page(
    retention: SaveableStateHolder,
    pages: PageLevels?,
    entry: HistoryEntry,
    saveKey: String,
    content: @Composable () -> Unit,
) {
    retention.SaveableStateProvider(saveKey) {
        val level = pages?.let {
            val _ = remember(it, entry.page) { it.hold(entry.page) }
            it.levelFor(entry)
        }
        LevelHost(level, content)
    }
}
