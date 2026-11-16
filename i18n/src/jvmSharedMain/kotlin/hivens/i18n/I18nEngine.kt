package hivens.i18n

import hivens.i18n.api.I18nSettings
import hivens.i18n.api.LanguageInfo
import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.widget.api.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The launcher's words: every namespace's tables in every installed language, and the
 * language they are read in now.
 *
 * Words come from three places, each laid over the one before key by key:
 *
 * 1. What each owner ships for its own namespace, handed in by [contribute] under the
 *    owner's name: the launcher's words under `nexira`, a module's under its id.
 * 2. What an owner ships for another namespace, a translation a module carries for the
 *    launcher's words say, in the order the owners were handed in.
 * 3. The person's own files under [userDir]: `<tag>/<namespace>.properties`, and
 *    `<tag>/language.properties` declaring a language (see [LanguageDeclaration]). Read when
 *    the engine is made, when the language changes and on [reload].
 *
 * Languages are layered the same way: the shipped ones, then those an owner declares with its
 * words, then the person's declarations, each field a later layer names replacing the earlier
 * one. A folder of the person's whose tag nothing declares adds a language too.
 *
 * A key is looked up in the current language, then the languages it falls back to depth first,
 * then English, then shown as itself (see [declaredChains]). What a load finds wrong, a file
 * that cannot be read or a fallback that cannot be followed, is logged once and worked around.
 */
class I18nEngine(
    private val userDir: Path?,
    language: String,
    private val shipped: List<LanguageInfo> = ShippedLanguages.all,
) : Translations {

    private val lock = Any()
    private class Contribution(
        val tables: Map<String, Map<String, Map<String, String>>>,
        val languages: Map<String, Map<String, String>>,
    )

    private val contributions = LinkedHashMap<String, Contribution>()
    private var chosen = language
    private var user = readUser()
    private var said: Set<String> = emptySet()
    private var catalog = build()

    private val _current = MutableStateFlow(catalog.translator(chosen))
    private val _languages = MutableStateFlow(catalog.languages.values.toList())

    override val current: StateFlow<Translator> = _current.asStateFlow()

    override val languages: StateFlow<List<LanguageInfo>> = _languages.asStateFlow()

    /** The language chosen, which may be one not installed: the words are then in English. */
    val language: String get() = synchronized(lock) { chosen }

    /** What the last load found wrong and worked around. */
    val problems: List<String> get() = synchronized(lock) { catalog.problems }

    /**
     * The languages the words are read in now, best first: the current one and those it falls back
     * to, without the English every lookup ends in unless a language names it. For what is fetched in
     * a language from elsewhere, which takes the first of these it has.
     */
    override val readingOrder: List<String> get() = synchronized(lock) { catalog.declaredChainOf(_current.value.language.tag) }

    /**
     * The words [owner] ships, by namespace, then by language tag, then by key, and the languages it
     * adds, by tag, each as a `language.properties` reads, replacing what it handed in before. Its own
     * namespace is the one named like it.
     */
    fun contribute(
        owner: String,
        tables: Map<String, Map<String, Map<String, String>>>,
        languages: Map<String, Map<String, String>> = emptyMap(),
    ) {
        synchronized(lock) {
            val before = contributions[owner]
            if (before != null && before.tables == tables && before.languages == languages) return
            contributions[owner] = Contribution(tables, languages)
            rebuild()
        }
    }

    /** Drops what [owner] handed in, a module that left say. */
    fun withdraw(owner: String) {
        synchronized(lock) {
            if (contributions.remove(owner) == null) return
            rebuild()
        }
    }

    /** Reads the words in [tag] from now on, and the person's files again with them. */
    fun setLanguage(tag: String) {
        synchronized(lock) {
            if (tag == chosen) return
            chosen = tag
            user = readUser()
            rebuild()
        }
    }

    /** Reads the person's files again. */
    fun reload() {
        synchronized(lock) {
            user = readUser()
            rebuild()
        }
    }

    /** Follows the language the person chooses in [store] from now on, on [scope]. */
    fun follow(store: SettingsStore<I18nSettings>, scope: CoroutineScope) {
        setLanguage(store.value.value.language)
        store.value.onEach { setLanguage(it.language) }.launchIn(scope)
    }

    private fun rebuild() {
        catalog = build()
        _languages.value = catalog.languages.values.toList()
        _current.value = catalog.translator(chosen)
    }

    private fun build(): Catalog {
        val languages = LinkedHashMap<String, LanguageInfo>()
        val problems = ArrayList(user.problems)
        shipped.forEach { languages[it.tag] = it }
        val added = sortedMapOf<String, LanguageInfo>()
        fun declare(tag: String, declared: LanguageDeclaration) {
            val known = languages[tag] ?: added[tag]
            val merged = known?.copy(
                nativeName = declared.name ?: known.nativeName,
                plural = declared.plural ?: known.plural,
                fallbacks = declared.fallbacks ?: known.fallbacks,
            ) ?: LanguageInfo(tag, declared.name ?: tag, declared.plural ?: "other", declared.fallbacks.orEmpty())
            if (tag in languages) languages[tag] = merged else added[tag] = merged
        }
        contributions.forEach { (owner, contribution) ->
            contribution.languages.forEach { (tag, entries) ->
                if (isLanguageTag(tag)) declare(tag, LanguageDeclaration.of(entries))
                else problems += "'$owner' declares a language '$tag', which is not a language tag, and it is ignored"
            }
        }
        user.declarations.forEach { (tag, declared) -> declare(tag, declared) }
        user.folders.filter { it !in languages && it !in added }.forEach { tag ->
            declare(tag, LanguageDeclaration(name = null, plural = null, fallbacks = null))
            problems += "the language '$tag' has no ${LanguageDeclaration.FILE}, so it is named '$tag' and uses only 'other' plural forms"
        }
        languages.putAll(added)
        languages.values.forEach { language ->
            if (PluralRules.find(language.plural) == null) {
                problems += "language '${language.tag}' names the plural family '${language.plural}', which is none of " +
                    "${PluralRules.names.joinToString()}, so only its 'other' forms are used"
            }
        }

        val tables = HashMap<String, MutableMap<String, MutableMap<String, String>>>()
        fun lay(namespace: String, byTag: Map<String, Map<String, String>>) {
            byTag.forEach { (tag, words) -> tables.getOrPut(namespace) { HashMap() }.getOrPut(tag) { HashMap() }.putAll(words) }
        }
        contributions.forEach { (owner, contribution) -> contribution.tables[owner]?.let { lay(owner, it) } }
        contributions.forEach { (owner, contribution) ->
            contribution.tables.filterKeys { it != owner }.forEach { (namespace, byTag) -> lay(namespace, byTag) }
        }
        user.tables.forEach { (namespace, byTag) -> lay(namespace, byTag) }

        return Catalog(languages, tables, problems).also { made ->
            val fresh = made.problems.toSet()
            (fresh - said).forEach { log.warn("Translations: {}", it) }
            said = fresh
        }
    }

    /**
     * What the person's folder holds: languages declared, words by namespace then tag, the language
     * folders there are, and what could not be read.
     */
    private class User(
        val declarations: Map<String, LanguageDeclaration> = emptyMap(),
        val tables: Map<String, Map<String, Map<String, String>>> = emptyMap(),
        val folders: Set<String> = emptySet(),
        val problems: List<String> = emptyList(),
    )

    private fun readUser(): User {
        val dir = userDir?.takeIf { it.isDirectory() } ?: return User()
        val declarations = HashMap<String, LanguageDeclaration>()
        val tables = HashMap<String, MutableMap<String, Map<String, String>>>()
        val problems = ArrayList<String>()
        val folders = runCatching { Files.list(dir).use { it.filter(Path::isDirectory).sorted().toList() } }
            .getOrElse {
                problems += "the translations folder $dir could not be read: ${it.message}"
                emptyList()
            }
        val tags = LinkedHashSet<String>()
        folders.forEach { folder ->
            val tag = folder.name
            if (!isLanguageTag(tag)) {
                problems += "the folder '$tag' in $dir is not named by a language tag and is ignored"
                return@forEach
            }
            tags += tag
            val files = runCatching { Files.list(folder).use { it.filter(Path::isRegularFile).sorted().toList() } }
                .getOrElse {
                    problems += "the translations of '$tag' could not be read: ${it.message}"
                    return@forEach
                }
            files.filter { it.name.endsWith(".properties") }.forEach { file ->
                val read = runCatching { PropertiesFiles.readChecked(file) }.getOrElse {
                    problems += "$file could not be read and is skipped: ${it.message}"
                    return@forEach
                }
                if (read.notUtf8) problems += "$file is not all UTF-8, and what is not reads as the replacement character"
                val entries = read.entries
                if (file.name == LanguageDeclaration.FILE) {
                    declarations[tag] = LanguageDeclaration.of(entries)
                } else {
                    tables.getOrPut(file.name.removeSuffix(".properties")) { HashMap() }[tag] = entries
                }
            }
        }
        return User(declarations, tables, tags, problems)
    }

    private companion object {
        val log = LoggerFactory.getLogger(I18nEngine::class.java)
    }
}
