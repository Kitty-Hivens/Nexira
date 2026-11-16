package hivens.i18n

import hivens.widget.api.ModuleStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What an owner ships, as the engine takes it: its own words under its id, and the words it carries for
 * other namespaces under theirs. Where it also wrote files for its own namespace by name,
 * `i18n/<its id>/<tag>.properties`, its plain `i18n/<tag>.properties` are laid over them key by key.
 */
fun ModuleStrings.wordsOf(moduleId: String): Map<String, Map<String, Map<String, String>>> {
    val own = namespaces[moduleId].orEmpty()
    val merged = (own.keys + tables.keys).associateWith { tag -> own[tag].orEmpty() + tables[tag].orEmpty() }
    return (namespaces - moduleId + (moduleId to merged)).filterValues { it.isNotEmpty() }
}

/** Hands this engine what [owner] ships: its words and the languages it adds. */
fun I18nEngine.take(owner: String, strings: ModuleStrings) = contribute(owner, strings.wordsOf(owner), strings.languageDeclarations)

/**
 * Follows the words every owner ships, [words], on [scope], this engine having taken in [taken] already:
 * an owner that comes brings its words, one loaded again brings its new ones, and one that leaves takes
 * its own with it, the ones it carried for other namespaces among them. [taken] is the very map the
 * engine was made from, not [words] read again, which may hold what came since and was never taken.
 */
internal fun I18nEngine.followWords(words: StateFlow<Map<String, ModuleStrings>>, taken: Map<String, ModuleStrings>, scope: CoroutineScope) {
    var handed = taken
    scope.launch {
        words.collect { now ->
            now.forEach { (owner, strings) -> if (handed[owner] !== strings) take(owner, strings) }
            (handed.keys - now.keys).forEach(::withdraw)
            handed = now
        }
    }
}
