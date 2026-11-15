package hivens.i18n

import hivens.i18n.api.LanguageInfo

/**
 * The languages the launcher ships, in the order the picker lists them.
 *
 * Japanese carries its own alpha marker, in Latin so it reads whatever language the
 * picker is being read in: the translation was not written by a native speaker, and a
 * warning in the list is better than a surprise once the interface has switched.
 */
object ShippedLanguages {
    val all: List<LanguageInfo> = listOf(
        LanguageInfo("ru", "Русский", "east-slavic"),
        LanguageInfo("en", "English", "one-other"),
        LanguageInfo("de", "Deutsch", "one-other"),
        LanguageInfo("ja", "日本語 (alpha)", "other"),
    )

    val tags: Set<String> get() = all.mapTo(LinkedHashSet()) { it.tag }
}

/**
 * What a `language.properties` file declares about its language, any of it absent:
 *
 * - `name`, what the language calls itself.
 * - `plural`, the plural rule family (see [PluralRules]).
 * - `fallback`, the languages looked up next, by tag, separated by commas.
 */
internal data class LanguageDeclaration(val name: String?, val plural: String?, val fallbacks: List<String>?) {
    companion object {
        const val FILE = "language.properties"

        fun of(entries: Map<String, String>): LanguageDeclaration = LanguageDeclaration(
            name = entries["name"]?.trim()?.takeIf { it.isNotEmpty() },
            plural = entries["plural"]?.trim()?.takeIf { it.isNotEmpty() },
            fallbacks = entries["fallback"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
        )
    }
}

/** The English every lookup chain ends in, whatever a language declares. */
internal const val ENGLISH = "en"

private val TAG = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

/** Whether [tag] can name a language, and with it a folder. */
internal fun isLanguageTag(tag: String): Boolean = TAG.matches(tag)

/**
 * The languages each language in [languages] falls back to, itself first, depth first: a
 * language's first fallback and everything that one falls back to come before its second.
 * English, which every lookup ends in, is added by the reader and is here only where named.
 *
 * A language that names itself, names one not installed, or closes a cycle has that step cut
 * and is otherwise read as declared, and each cut is said in [problems].
 */
internal fun declaredChains(languages: Map<String, LanguageInfo>, problems: MutableCollection<String>): Map<String, List<String>> =
    languages.keys.associateWith { tag ->
        val chain = mutableListOf(tag)
        fun visit(current: String, path: List<String>) {
            languages[current]?.fallbacks.orEmpty().forEach { next ->
                when {
                    next == current -> problems += "language '$current' falls back to itself, which is ignored"
                    next !in languages -> problems += "language '$current' falls back to '$next', which is not installed and is skipped"
                    next in path -> problems += "language '$current' falls back to '$next', which closes a cycle (${(path + next).joinToString(" -> ")}), so that step is cut"
                    next in chain -> Unit
                    else -> {
                        chain += next
                        visit(next, path + next)
                    }
                }
            }
        }
        visit(tag, listOf(tag))
        chain
    }
