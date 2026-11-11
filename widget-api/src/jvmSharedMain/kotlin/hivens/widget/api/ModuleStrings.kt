package hivens.widget.api

/**
 * The words a module brings with it, in as many languages as it ships.
 *
 * A module cannot add to the launcher's own string tables: those are compiled into it, one class
 * per language. It carries its own instead, as one `i18n/<tag>.properties` file per language in its
 * jar (UTF-8, `key=text`, [tag] being the launcher's language tag such as `ru`, `en`, `de`, `ja`).
 * Anything the module declares for a person to read, a label or a hint, is a key into these.
 *
 * A key with no entry is shown as itself. That is deliberate: a module in one language can put the
 * plain text where a key goes and ship no files at all, and a key somebody forgot to translate is
 * visible rather than blank.
 */
class ModuleStrings(private val tables: Map<String, Map<String, String>> = emptyMap()) {

    /** The language tags this module has words for. */
    val languages: Set<String> get() = tables.keys

    /**
     * The text for [key] in [locale], else in English, else [key] itself. `{0}`, `{1}` and so on in
     * the text are replaced by [args] in order, so a module can phrase a sentence around a value.
     */
    fun get(key: String, locale: String, vararg args: Any?): String {
        val text = tables[locale]?.get(key) ?: tables[FALLBACK]?.get(key) ?: key
        if (args.isEmpty()) return text
        return args.foldIndexed(text) { index, acc, arg -> acc.replace("{$index}", arg.toString()) }
    }

    companion object {
        /** Spoken by whoever the module has no words for, as the launcher's own English is. */
        const val FALLBACK = "en"

        val EMPTY = ModuleStrings()
    }
}
