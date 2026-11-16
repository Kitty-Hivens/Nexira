// The language module is compiled into the application and trusted with the host's own
// services: the data directory a person's own translations are in, and the words every owner
// ships, which it reads through the host view of the app level.
@file:OptIn(HostApi::class)

package hivens.i18n

import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.I18nSettings
import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.widget.api.BootAnswers
import hivens.widget.api.BootContext
import hivens.widget.api.BootValues
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.HostFiles
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ServiceContext
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenWords
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.service
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path

/**
 * The language module as the application carries it: compiled in, and brought in by a
 * document that names [I18nApi.ID].
 *
 * It makes its engine itself, as an app service over the person's own translation files in the
 * host's data directory, the language they chose in its settings domain and the words every
 * owner ships ([TakenWords]). The window the launcher boots in speaks before that, and asks
 * [boot] for its words, which come from an engine made the same way over the same files.
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
    override val boot: BootAnswers = BootAnswers(::bootAnswer)

    /** The folder under the launcher's data directory a person's own translations are in. */
    const val TRANSLATIONS_DIR: String = "translations"

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

    override fun imported(legacy: JsonObject, current: I18nSettings?): I18nSettings? =
        (legacy[LEGACY_FIELD] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { language ->
            current?.copy(language = language) ?: I18nSettings(language)
        }

    override fun mirrored(value: I18nSettings): Map<String, JsonElement> = mapOf(LEGACY_FIELD to JsonPrimitive(value.language))
}

/**
 * The keys in [I18nKeys], answered over the module's own engine, and [Translations] as a
 * service every module may read for words it formats outside a composition.
 */
object I18nKeysContribution : KeyContribution {
    override val id: String = "i18n.keys"
    override val label: String = "Language"

    override fun declare(keys: ModuleKeys) {
        keys.settings(I18nSettingsDomain, I18nKeys.Settings, I18nKeys.UpdateSettings)
        keys.service(ScopeLevel.App, ::moduleEngine)
        keys.service(ScopeLevel.App) { context -> context.services.get<I18nEngine>() as Translations }
        keys.source(I18nKeys.Language, sample = { ShippedLanguages.all.first { it.tag == ENGLISH } }) { app ->
            val engine = app.services.get<I18nEngine>()
            flowSource(engine.current.map { it.language }.stateIn(app.coroutineScope, SharingStarted.Eagerly, engine.current.value.language))
        }
        keys.source(I18nKeys.Languages, sample = { ShippedLanguages.all }) { app ->
            flowSource(app.services.get<I18nEngine>().languages)
        }
        keys.source(I18nKeys.Words, sample = { Translator.KEYS }) { app ->
            flowSource(app.services.get<I18nEngine>().current)
        }
    }
}

/**
 * An engine over the person's own files under [dataDir], speaking [language], with what [words]
 * holds taken in, in its order. The window the launcher boots in and the module once its keys are
 * up both make theirs here, so the two speak the same words from the same files.
 */
internal fun engineOver(dataDir: Path?, language: String, words: Map<String, ModuleStrings>): I18nEngine =
    I18nEngine(dataDir?.resolve(I18nModule.TRANSLATIONS_DIR), language).apply {
        words.forEach { (owner, strings) -> take(owner, strings) }
    }

/**
 * The module's engine for the app: it follows the language the person chooses in the module's
 * settings domain, and the words every owner ships as owners come and go. A host that names no data
 * directory leaves out the person's own files, and one that hands over no words leaves only the keys.
 */
private fun moduleEngine(context: ServiceContext): I18nEngine {
    val dataDir = context.hostServices.find<HostFiles>()?.dataDir
    val language = context.services.get<SettingsStores>().of(I18nSettingsDomain)
    val words = context.hostServices.find<TakenWords>()?.all ?: MutableStateFlow(emptyMap())
    val first = words.value
    return engineOver(dataDir, language.value.value.language, first).also { engine ->
        engine.follow(language, context.coroutineScope)
        engine.followWords(words, first, context.coroutineScope)
    }
}

/** The language, the languages and the words the boot window is drawn in, from an engine made the way [moduleEngine] makes it. */
private fun bootAnswer(context: BootContext): BootValues {
    val words = context.services.find<TakenWords>()?.all?.value.orEmpty()
    val engine = engineOver(context.dataDir, context.settings(I18nSettingsDomain).language, words)
    val now = engine.current.value
    return BootValues.of {
        put(I18nKeys.Language, now.language)
        put(I18nKeys.Languages, engine.languages.value)
        put(I18nKeys.Words, now)
    }
}
