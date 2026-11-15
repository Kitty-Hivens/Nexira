package hivens.i18n.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * A language the launcher can speak: the ones it ships and any a person added.
 *
 * [plural] names the rule family the language's plural forms follow, see the language
 * module's documentation for the families. [fallbacks] are the languages a key this one
 * has no word for is looked up in next, in order, before English.
 */
data class LanguageInfo(
    /** The language tag, `ru` or `pt-BR`. Also the folder its custom words live in. */
    val tag: String,
    /** What the language calls itself, as the language picker lists it. */
    val nativeName: String,
    /** The plural rule family. */
    val plural: String,
    /** The languages looked up after this one and before English. */
    val fallbacks: List<String> = emptyList(),
)

/** The words of one namespace in one language. Answered at once: nothing here waits or reads a file. */
interface Strings {
    /** Whose words these are: a module's id, or `nexira` for the launcher's own. */
    val namespace: String

    /** The tag of the language they are in. */
    val language: String

    /** The language as the JDK knows it, for dates and numbers written beside the words. */
    val locale: Locale

    /**
     * The message for [key] with [args] in it: in this language, then in each language it falls
     * back to, then in English, then [key] itself.
     */
    fun text(key: String, vararg args: Any?): String

    /** Whether any language in the chain has a message for [key]. */
    fun has(key: String): Boolean
}

/**
 * Every namespace's words in one language, as they were when this was made. A new one is
 * made whenever the language or the words change, so holding one is holding a moment.
 */
interface Translator {
    /** The language these are in. */
    val language: LanguageInfo

    /** The words of [namespace]. */
    fun strings(namespace: String): Strings

    /** The same words in the language [tag], or in this one where no such language is installed. */
    fun inLanguage(tag: String): Translator

    companion object {
        /** Knows no words: every key is shown as itself. What a composition without the language module reads. */
        val KEYS: Translator = KeysOnly(LanguageInfo("en", "English", "one-other"))
    }
}

/**
 * The launcher's words as they are now and every time they change, for a service that
 * formats words outside a composition: a notice, a log line a person reads.
 */
interface Translations {
    /** The words in the current language. */
    val current: StateFlow<Translator>

    /** Every language installed, the shipped ones first. */
    val languages: StateFlow<List<LanguageInfo>>

    /** The words of [namespace] in the current language. */
    fun strings(namespace: String): Strings = current.value.strings(namespace)
}

private class KeysOnly(override val language: LanguageInfo) : Translator {
    override fun strings(namespace: String): Strings = object : Strings {
        override val namespace = namespace
        override val language = this@KeysOnly.language.tag
        override val locale: Locale = Locale.forLanguageTag(this@KeysOnly.language.tag)
        override fun text(key: String, vararg args: Any?): String = key
        override fun has(key: String): Boolean = false
    }

    override fun inLanguage(tag: String): Translator = this
}

/** [Translations] holding one [translator] that never changes, for a test or a host with nothing to switch. */
class FixedTranslations(translator: Translator) : Translations {
    private val flow = MutableStateFlow(translator)
    override val current: StateFlow<Translator> = flow.asStateFlow()
    override val languages: StateFlow<List<LanguageInfo>> = MutableStateFlow(listOf(translator.language)).asStateFlow()
}
