// The language module is compiled into the application and trusted with the host's own
// services: the engine it answers over is the host's, made before any module loads.
@file:OptIn(HostApi::class)

package hivens.i18n

import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.I18nSettings
import hivens.i18n.api.Translations
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.SettingsDomain
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostServices
import hivens.widget.api.service
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The language module as the application carries it: compiled in, and brought in by a
 * document that names [I18nApi.ID].
 *
 * The engine itself is made by the host before any module loads, because the window the
 * launcher boots in is already drawn in the person's language, and handed to the module as
 * a host service. The module owns what the person chose, as its settings domain, and the
 * keys that read the languages and change the choice.
 */
object I18nModule : CompiledModule {
    override val id: String = I18nApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings(
        mapOf(
            "en" to mapOf(NAME to "Language"),
            "ru" to mapOf(NAME to "Язык"),
            "de" to mapOf(NAME to "Sprache"),
            "ja" to mapOf(NAME to "言語"),
        ),
    )
    override val provides: Map<String, ApiVersion> = mapOf(I18nApi.ID to I18nApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(I18nApi.ID to setOf(I18nApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(I18nKeysContribution)

    private const val NAME = "i18n.name"
}

/**
 * What the person chose: the language, by tag, in `i18n.json`.
 *
 * Before the module existed the launcher kept the language as `locale` in its one settings
 * file. A build from before then still reads it there, so every choice is written there too,
 * and a choice such a build made there is taken back (see [SettingsDomain.imported]).
 */
object I18nSettingsDomain : SettingsDomain<I18nSettings>("i18n", I18nSettings.serializer()) {
    private const val LEGACY_FIELD = "locale"

    override fun defaults(): I18nSettings = I18nSettings()

    override fun imported(legacy: JsonObject): I18nSettings? =
        (legacy[LEGACY_FIELD] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(::I18nSettings)

    override fun mirrored(value: I18nSettings): Map<String, JsonElement> = mapOf(LEGACY_FIELD to JsonPrimitive(value.language))
}

/**
 * The keys in [I18nKeys], answered over the host's engine, and [Translations] as a service
 * every module may read for words it formats outside a composition.
 */
object I18nKeysContribution : KeyContribution {
    override val id: String = "i18n.keys"
    override val label: String = "Language"

    override fun declare(keys: ModuleKeys) {
        keys.settings(I18nSettingsDomain, I18nKeys.Settings, I18nKeys.UpdateSettings)
        keys.service(ScopeLevel.App) { context -> context.hostServices.get<I18nEngine>() as Translations }
        keys.source(I18nKeys.Language, sample = { ShippedLanguages.all.first { it.tag == ENGLISH } }) { app ->
            val engine = app.hostServices.get<I18nEngine>()
            flowSource(engine.current.map { it.language }.stateIn(app.coroutineScope, SharingStarted.Eagerly, engine.current.value.language))
        }
        keys.source(I18nKeys.Languages, sample = { ShippedLanguages.all }) { app ->
            flowSource(app.hostServices.get<I18nEngine>().languages)
        }
    }
}
