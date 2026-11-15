package hivens.widget.api

/**
 * The words a module brings with it, in as many languages as it ships, for the launcher's language
 * module to look up.
 *
 * A jar carries them as files, read by the loader: `i18n/<tag>.properties` for the module's own
 * words, and `i18n/<namespace>/<tag>.properties` for words it adds to another namespace, a
 * translation of the launcher's own or of another module's (UTF-8, `key=text`, [tables] by language
 * tag such as `ru`, `en`, `de`, `ja`). A module compiled into the launcher hands them over as this.
 * Anything the module declares for a person to read, a label or a hint, is a key into its own words.
 *
 * Lookup is not done here: the language module registers [tables] under the module's id as its
 * namespace, and [namespaces] under theirs, and answers every key in the person's language, then the
 * languages that one falls back to, then English, then the key itself. A key with no entry is shown as
 * itself, so a module in one language can put the plain text where a key goes and ship no files at all.
 * The text is a message, see the language module's API for its syntax: `{0}`, `{1}` and so on are
 * still the arguments in order.
 *
 * Where a module ships its own words both ways, `i18n/<tag>.properties` and
 * `i18n/<its id>/<tag>.properties`, the first wins key by key. A jar can also add a language with
 * `i18n/<tag>/language.properties` ([languageDeclarations]), declared the way a person's own
 * translation folder declares one.
 */
class ModuleStrings @JvmOverloads constructor(
    /** The module's own words, by language tag, then by key. */
    val tables: Map<String, Map<String, String>> = emptyMap(),
    /** Words for other namespaces, by namespace, then by language tag, then by key. */
    val namespaces: Map<String, Map<String, Map<String, String>>> = emptyMap(),
    /** Languages the module adds, by tag, each as its `language.properties` reads: `name`, `plural`, `fallback`. */
    val languageDeclarations: Map<String, Map<String, String>> = emptyMap(),
) {

    /** The language tags this module has its own words for. */
    val languages: Set<String> get() = tables.keys

    /** Whether it carries no words at all. */
    val isEmpty: Boolean get() = tables.isEmpty() && namespaces.isEmpty() && languageDeclarations.isEmpty()

    companion object {
        val EMPTY = ModuleStrings()
    }
}
