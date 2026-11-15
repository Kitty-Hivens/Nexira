package hivens.i18n

import hivens.i18n.api.LanguageInfo
import hivens.i18n.api.Strings
import hivens.i18n.api.Translator
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("hivens.i18n.Catalog")

/**
 * Every word of every namespace in every installed language, as one load left them. Never
 * changed once made: a change of words or of language makes another.
 */
internal class Catalog(
    /** Installed languages by tag, in the order they are listed. */
    val languages: Map<String, LanguageInfo>,
    /** Words by namespace, then by language tag, then by key. */
    private val tables: Map<String, Map<String, Map<String, String>>>,
    /** What reading the files found wrong and worked around. */
    loadProblems: List<String> = emptyList(),
) {
    private val chainProblems = LinkedHashSet<String>()

    private val declared: Map<String, List<String>> = declaredChains(languages, chainProblems)

    private val chains: Map<String, List<String>> = declared.mapValues { (_, chain) -> if (ENGLISH in chain) chain else chain + ENGLISH }

    /** What the load found wrong and worked around, the lookup chains it had to cut among it. */
    val problems: List<String> = loadProblems + chainProblems

    private val rules: Map<String, PluralRule> = languages.mapValues { (_, language) -> PluralRules.of(language.plural) }

    private val parsed = ConcurrentHashMap<String, Message>()

    private val malformed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The lookup chain of [tag], English alone for a tag no language answers to. */
    fun chainOf(tag: String): List<String> = chains[tag] ?: listOf(ENGLISH)

    /** The languages [tag] reads in, best first, without the English every lookup ends in unless it is named. */
    fun declaredChainOf(tag: String): List<String> = declared[tag] ?: listOf(ENGLISH)

    /** The words of [namespace] in [tag], or null where there are none. */
    fun table(namespace: String, tag: String): Map<String, String>? = tables[namespace]?.get(tag)

    /** The namespaces any language has words for. */
    val namespaces: Set<String> get() = tables.keys

    fun translator(tag: String): Translator = CatalogTranslator(this, languages[tag] ?: languages[ENGLISH] ?: FALLBACK_ENGLISH)

    /** [raw], the text of [key] in [tag], written with [args]. */
    fun format(namespace: String, tag: String, key: String, raw: String, args: Array<out Any?>): String {
        if (Message.isPlain(raw)) return raw
        val id = "$tag\u0000$namespace\u0000$key"
        val message = parsed[id] ?: try {
            Message.parse(raw).also { parsed[id] = it }
        } catch (e: MessageSyntaxException) {
            if (malformed.add(id)) log.warn("Translations: '{}' in '{}' ({}) is not a valid message, shown as written: {}", key, namespace, tag, e.message)
            return raw
        }
        return message.format(args, rules[tag] ?: PluralRules.of(PLURAL_FALLBACK))
    }

    companion object {
        private val FALLBACK_ENGLISH = LanguageInfo(ENGLISH, "English", "one-other")
        private const val PLURAL_FALLBACK = "other"
    }
}

private class CatalogTranslator(private val catalog: Catalog, override val language: LanguageInfo) : Translator {
    private val chain = catalog.chainOf(language.tag)
    private val locale: Locale = Locale.forLanguageTag(language.tag)
    private val byNamespace = ConcurrentHashMap<String, Strings>()

    override fun strings(namespace: String): Strings = byNamespace.getOrPut(namespace) { CatalogStrings(namespace) }

    override fun inLanguage(tag: String): Translator =
        if (tag == language.tag || tag !in catalog.languages) this else catalog.translator(tag)

    private inner class CatalogStrings(override val namespace: String) : Strings {
        override val language: String get() = this@CatalogTranslator.language.tag
        override val locale: Locale get() = this@CatalogTranslator.locale

        override fun text(key: String, vararg args: Any?): String {
            chain.forEach { tag ->
                catalog.table(namespace, tag)?.get(key)?.let { return catalog.format(namespace, tag, key, it, args) }
            }
            return key
        }

        override fun has(key: String): Boolean = chain.any { tag -> catalog.table(namespace, tag)?.containsKey(key) == true }
    }
}
