package hivens.ui.widgets.modules

import hivens.themes.ThemeManager
import hivens.themes.api.ThemeContribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Hands [manager] the themes every jar module [modules] carry, as they change: a module that
 * loads brings its themes, one loaded again brings its new ones, and one that leaves takes its
 * own with it. Which theme each becomes, and under which id and name, is the themes module's.
 */
fun followModuleThemes(modules: StateFlow<WidgetModules.Snapshot>, manager: ThemeManager, scope: CoroutineScope) {
    var handed = emptySet<String>()
    fun apply(snapshot: WidgetModules.Snapshot) {
        val byModule = snapshot.of<ThemeContribution>().groupBy({ it.moduleId }, { it.item })
        byModule.forEach { (id, themes) -> manager.contribute(id, themes) }
        (handed - byModule.keys).forEach(manager::withdraw)
        handed = byModule.keys
    }
    apply(modules.value)
    scope.launch { modules.collect(::apply) }
}
