package hivens.ui.editor

import hivens.i18n.api.Strings
import hivens.pages.api.HistoryEntry
import hivens.pages.api.PagesHost
import hivens.pages.api.ScreenId
import hivens.pages.api.ScreenView
import hivens.ui.chrome.staticCrumbLabel
import hivens.ui.navigation.LauncherScreens
import hivens.widget.model.Document
import hivens.widget.model.NodePath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.contentAt
import hivens.widget.model.holdsOwn
import hivens.widget.model.isSingleSlot

/** Which screens an edit made in one place changes. */
enum class EditReach {
    /** Every screen: the place is the frame around the pages, or a record the composition keeps once. */
    EveryScreen,

    /** Only the screen shown: the place is in the pages host's slot for it. */
    ThisScreen,
}

/**
 * Whom an edit of [widget], standing in the slot at [path], reaches. A widget the composition has once is one record
 * for every screen. Anything else reaches only the screen shown where a slot of the pages host is above it, nearer than
 * any slot shared through such a record. Everything else is the frame, drawn around whichever screen shows.
 */
fun Document.reachOf(path: NodePath, widget: WidgetInstance? = null): EditReach {
    val composition = compositions[path.composition]
    if (widget != null && composition?.singles?.containsKey(widget.kind) == true) return EditReach.EveryScreen
    for (depth in path.nested.size downTo 1) {
        val slot = path.copy(nested = path.nested.take(depth))
        if (isSingleSlot(slot) && !holdsOwn(slot)) return EditReach.EveryScreen
        if (containerOf(slot)?.kind == PagesHost.KIND) return EditReach.ThisScreen
    }
    return EditReach.EveryScreen
}

/** Whether [path] is inside a slot of the pages host: a place one screen holds. */
fun Document.onPage(path: NodePath): Boolean =
    (1..path.nested.size).any { depth -> containerOf(path.copy(nested = path.nested.take(depth)))?.kind == PagesHost.KIND }

/** The widget whose slot [path] names, or null for a root or a path that leads nowhere. */
private fun Document.containerOf(path: NodePath): WidgetInstance? {
    val parent = path.parentPath ?: return null
    val step = path.nested.last()
    return contentAt(parent)?.widgets?.firstOrNull { it.instanceId == step.parentInstanceId }
}

/**
 * What a screen of the launcher is called where a widget says where it shows: the name a person gave it, made or
 * renamed, else what the top bar calls it, the pages that open one thing by the kind of thing they open. An id no
 * screen has is shown as itself.
 */
fun placeLabel(id: String, screens: List<ScreenView>, s: Strings): String {
    val screen = ScreenId(id)
    screens.firstOrNull { it.id == screen && it.owner == null }?.title?.takeIf { it.isNotBlank() }?.let { return it }
    return when (screen) {
        LauncherScreens.Pack -> s.text("configPack")
        LauncherScreens.CataloguePack -> s.text("configCataloguePack")
        LauncherScreens.Project -> s.text("configProject")
        LauncherScreens.Build -> s.text("configBuild")
        else -> staticCrumbLabel(HistoryEntry(screen), s)?.takeIf { it.isNotBlank() } ?: id
    }
}
