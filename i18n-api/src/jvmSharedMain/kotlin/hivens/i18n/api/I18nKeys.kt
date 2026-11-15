package hivens.i18n.api

import hivens.widget.api.SettingsChange
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.serialization.Serializable

/**
 * The language module's settings: the language a person chose, by tag. A tag no language
 * installed answers to is kept as chosen, and the words are read in English meanwhile, so a
 * custom language whose folder is away for a start comes back with it.
 */
@Serializable
data class I18nSettings(val language: String = "en")

/**
 * The keys the language module answers, all at the app level. The ids are constants so a
 * widget can name them in its needs.
 */
object I18nKeys {
    const val SETTINGS_ID = "i18n.settings"
    const val UPDATE_SETTINGS_ID = "i18n.settings.update"
    const val LANGUAGE_ID = "i18n.language"
    const val LANGUAGES_ID = "i18n.languages"

    /** What the person chose, as stored. */
    val Settings = SourceKey<I18nSettings>(SETTINGS_ID, ScopeLevel.App)

    /** Changes what the person chose: `update { it.copy(language = "de") }`. */
    val UpdateSettings = CommandKey<SettingsChange<I18nSettings>>(UPDATE_SETTINGS_ID, ScopeLevel.App)

    /** The language the words are in now: the chosen one where it is installed, English where it is not. */
    val Language = SourceKey<LanguageInfo>(LANGUAGE_ID, ScopeLevel.App)

    /** Every language installed, the shipped ones first, for a picker. */
    val Languages = SourceKey<List<LanguageInfo>>(LANGUAGES_ID, ScopeLevel.App)
}
