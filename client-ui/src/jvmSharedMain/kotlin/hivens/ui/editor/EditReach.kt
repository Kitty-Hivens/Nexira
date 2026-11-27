package hivens.ui.editor

import hivens.i18n.api.Strings
import hivens.ui.chrome.staticCrumbLabel
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.navigation.LauncherScreens
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.pages.api.HistoryEntry
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.WidgetInstance
import hivens.widget.model.holdsOwn
import hivens.widget.model.isSingleSlot
import hivens.ui.navigation.asScreen

/** Which configs an edit made in one place changes. */
enum class EditReach {
    /** Every config of the composition: the place is the composition's, shared by all of them. */
    EveryScreen,

    /** Only the config shown: the place is held by it alone. */
    ThisScreen,
}

/**
 * Whom an edit of [widget], standing in the slot at [path], reaches. A widget the composition has once is one record
 * for every config. Anything else is stored where the innermost slot of such a widget above it keeps its content: in the
 * shared record, or in the config that holds the slot as its own. A slot with no such widget above it is the config's.
 */
fun Document.reachOf(path: NodePath, widget: WidgetInstance? = null): EditReach {
    val composition = compositions[path.composition]
    if (widget != null && composition?.singles?.containsKey(widget.kind) == true) return EditReach.EveryScreen
    for (depth in path.nested.size downTo 1) {
        val slot = path.copy(nested = path.nested.take(depth))
        if (isSingleSlot(slot)) return if (holdsOwn(slot)) EditReach.ThisScreen else EditReach.EveryScreen
    }
    return if (path.root is NodeRoot.Layout) EditReach.ThisScreen else EditReach.EveryScreen
}

/**
 * What a config of the launcher is called in the editor: a made screen by its name, a shipped one by what the top bar
 * calls it, and the pages named by what they open by the kind of page they are.
 */
fun Document.configLabel(id: ConfigId, s: Strings): String {
    compositions[LauncherConfigs.Composition]?.configs?.get(id)?.title?.takeIf { it.isNotBlank() }?.let { return it }
    return when (id.asScreen()) {
        LauncherScreens.Pack -> s.text("configPack")
        LauncherScreens.CataloguePack -> s.text("configCataloguePack")
        LauncherScreens.Project -> s.text("configProject")
        LauncherScreens.Build -> s.text("configBuild")
        else -> staticCrumbLabel(HistoryEntry(id.asScreen()), s)?.takeIf { it.isNotBlank() } ?: id.value
    }
}
