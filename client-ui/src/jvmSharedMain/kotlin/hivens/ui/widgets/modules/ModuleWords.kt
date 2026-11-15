package hivens.ui.widgets.modules

import hivens.i18n.I18nEngine
import hivens.widget.api.ModuleStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What a module ships, as the language module takes it from an owner: its own words under its id,
 * and the words it carries for other namespaces under theirs. Where it also wrote files for its own
 * namespace by name, `i18n/<its id>/<tag>.properties`, its plain `i18n/<tag>.properties` are laid
 * over them key by key.
 */
fun ModuleStrings.wordsOf(moduleId: String): Map<String, Map<String, Map<String, String>>> {
    val own = namespaces[moduleId].orEmpty()
    val merged = (own.keys + tables.keys).associateWith { tag -> own[tag].orEmpty() + tables[tag].orEmpty() }
    return (namespaces - moduleId + (moduleId to merged)).filterValues { it.isNotEmpty() }
}

/** Hands this engine what the module [moduleId] ships: its words and the languages it adds. */
fun I18nEngine.take(moduleId: String, strings: ModuleStrings) = contribute(moduleId, strings.wordsOf(moduleId), strings.languageDeclarations)

/**
 * Hands [engine] the words of every jar module [modules] carry, as they change: a module that
 * loads brings its words, one loaded again brings its new ones, and one that leaves takes its own
 * with it, the ones it carried for other namespaces among them.
 */
fun followModuleWords(modules: StateFlow<WidgetModules.Snapshot>, engine: I18nEngine, scope: CoroutineScope) {
    var handed = emptySet<String>()
    fun apply(snapshot: WidgetModules.Snapshot) {
        snapshot.words.forEach { (id, words) -> engine.take(id, words) }
        (handed - snapshot.words.keys).forEach(engine::withdraw)
        handed = snapshot.words.keys
    }
    apply(modules.value)
    scope.launch { modules.collect(::apply) }
}
