package hivens.i18n.api

import hivens.widget.model.ApiVersion

/**
 * The language module's API: what another module compiles against to look its words up
 * ([Strings], [Translator], [Translations], [LocalTranslator]) and to read and change the
 * language through the keys in [I18nKeys].
 *
 * A module built against it names [ID] and the version it was built against in its
 * manifest, and the loader refuses it where this launcher carries a version that does
 * not serve that one (see [ApiVersion.serves]).
 *
 * The words themselves are messages, looked up by namespace and key. The syntax is a
 * small part of ICU MessageFormat:
 *
 * - `{0}`, `{1}` and so on are the arguments in order, written with `toString()`.
 * - `{0, plural, one {# file} few {# files} many {# files} other {# files}}` picks a
 *   form by the number's plural category in the language (see [LanguageInfo.plural]).
 *   An exact value comes first when one is given, `=0 {no files}`. `#` in a form is the
 *   number. `other` is required.
 * - `{0, select, smartycraft {SmartyCraft} other {{0}}}` picks a form by the
 *   argument's text, `other` where none matches. `other` is required.
 * - Forms nest, so a form may hold arguments, plurals and selects of its own.
 * - `'{'`, `'}'` and `'#'` are a literal brace and hash. An apostrophe anywhere else is
 *   an apostrophe, unlike ICU, so `'{0}'` is the argument in quotes.
 *
 * A key is looked up in the current language, then the languages it falls back to,
 * then English, and shown as itself where none of them has it. A missing translation
 * is never an error: the build only warns about a key a shipped language lacks and
 * about a key the code looks up that English does not have. A message that does not
 * parse is shown as written at runtime, and in a table the launcher ships it fails
 * the launcher's tests, since that is a bug and not a missing translation.
 */
object I18nApi {
    /** What a manifest names this API by, and what a document names the module by to bring it in. */
    const val ID: String = "nexira.i18n"

    /**
     * The version of this API. A minor step only adds. A major step removes or changes
     * a key, a value type or the message syntax.
     *
     * The module builds stamp their manifests from the `nexira.i18nApi` Gradle property,
     * which a test holds equal to this.
     */
    val VERSION: ApiVersion = ApiVersion(1, 0)
}
