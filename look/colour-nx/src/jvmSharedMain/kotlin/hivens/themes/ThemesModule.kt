// The themes module is compiled into the application and trusted with the host's own services:
// the data directory its theme file is in, and the themes the modules brought in contribute,
// which it reads through the host view of the app level.
@file:OptIn(HostApi::class)

package hivens.themes

import hivens.i18n.api.FixedTranslations
import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeInputs
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeSettings
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
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
import hivens.widget.api.TakenContributions
import hivens.widget.api.command
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostService
import hivens.widget.api.service
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * The themes module as the application carries it: compiled in, and brought in by a document
 * that names [ThemesApi.ID].
 *
 * It makes its manager itself, as an app service over its settings domain and the theme file in
 * the host's data directory, and takes the themes other modules bring as the kind of contribution
 * its API declares. The window the launcher boots in is drawn before that, and asks [boot] for the
 * theme and the scheme, worked out from the same files the way the manager opens on them.
 *
 * It is also the nx provider of the look's colour axis ([NxColourProvider]): the look manager opens it
 * from the module's contributions, and brings the module in where the document does not name it.
 */
object ThemesModule : CompiledModule {
    override val id: String = ThemesApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(ThemesModule::class.java, ThemesApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(ThemesApi.ID to ThemesApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(ThemesApi.ID to setOf(ThemesApi::class.java.name.substringBeforeLast('.')))
    override val contributionKinds: List<Class<out ModuleContribution>> = listOf(ThemeContribution::class.java)
    override val contributions: List<ModuleContribution> = listOf(ThemesKeysContribution, NxColourProvider)
    override val boot: BootAnswers = BootAnswers(::bootAnswer)

    private const val NAME = "themes.name"

    /** What the person is told the theme file as, when this session cannot write it. */
    internal const val FILE = "themes.file"
}

/**
 * What the person chose: the theme, the mode and whether the launcher is drawn dark, in
 * `themes.json` under the settings folder.
 *
 * Before the module existed the launcher kept the dark flag and the mode in its one settings
 * file, as `isDarkTheme`, `themeMode` and, older still, `themeFromWallpaper`. A build from before
 * then still reads them there, so every choice is written there too, and a choice such a build
 * made there is taken back (see [SettingsDomain.imported]), laid over what the domain holds. The
 * theme itself was never in that file, so taking the scheme back keeps the theme, and where nothing
 * names one here the one the theme file was left on is in use.
 */
object ThemesSettingsDomain : SettingsDomain<ThemeSettings>("themes", ThemeSettings.serializer()) {
    private const val DARK = "isDarkTheme"
    private const val MODE = "themeMode"
    private const val FROM_WALLPAPER = "themeFromWallpaper"

    override fun defaults(): ThemeSettings = ThemeSettings()

    override fun imported(legacy: JsonObject, current: ThemeSettings?): ThemeSettings? {
        val dark = (legacy[DARK] as? JsonPrimitive)?.booleanOrNull
        val mode = (legacy[MODE] as? JsonPrimitive)?.contentOrNull
        val fromWallpaper = (legacy[FROM_WALLPAPER] as? JsonPrimitive)?.booleanOrNull
        if (dark == null && mode == null && fromWallpaper == null) return null
        val base = current ?: defaults()
        return base.copy(
            mode = if (mode == null && fromWallpaper == null) base.mode else legacyThemeMode(mode, fromWallpaper == true),
            dark = dark ?: base.dark,
        )
    }

    override fun mirrored(value: ThemeSettings): Map<String, JsonElement> = mapOf(
        DARK to JsonPrimitive(value.dark),
        MODE to JsonPrimitive(value.mode.name),
        FROM_WALLPAPER to JsonPrimitive(value.mode == ThemeMode.Wallpaper),
    )
}

/**
 * The keys in [ThemesKeys], answered over the module's own manager, and [ThemeInputs], kept for the
 * host and the modules compiled into it: what the wallpaper and the system's scheme say is the host's
 * to tell, and no jar's.
 */
object ThemesKeysContribution : KeyContribution {
    override val id: String = "themes.keys"
    override val label: String = "Themes"

    override fun declare(keys: ModuleKeys) {
        keys.settings(ThemesSettingsDomain, ThemesKeys.Settings, ThemesKeys.UpdateSettings)
        keys.service(ScopeLevel.App, ::moduleManager)
        keys.source(ThemesKeys.Active, sample = { Themes.default }) { app -> flowSource(app.services.get<ThemeManager>().active) }
        keys.source(ThemesKeys.Offered, sample = { Themes.all }) { app -> flowSource(app.services.get<ThemeManager>().offered) }
        keys.source(ThemesKeys.Dark, sample = { true }) { app -> flowSource(app.services.get<ThemeManager>().dark) }
        keys.source(ThemesKeys.Mode, sample = { ThemeMode.Manual }) { app -> flowSource(app.services.get<ThemeManager>().mode) }
        keys.source(ThemesKeys.SystemSchemeAvailable, sample = { false }) { app ->
            flowSource(app.services.get<ThemeManager>().systemSchemeAvailable)
        }
        keys.command(ThemesKeys.Choose) { app -> command(app.services.get<ThemeManager>()::choose) }
        keys.command(ThemesKeys.SetDark) { app -> command(app.services.get<ThemeManager>()::setDark) }
        keys.command(ThemesKeys.SetMode) { app -> command(app.services.get<ThemeManager>()::setMode) }
        keys.hostService<ThemeInputs>(ScopeLevel.App) { context -> context.services.get<ThemeManager>() }
    }
}

/**
 * The module's manager for the app, over its settings domain and the theme file in the host's data
 * directory, written where the host writes. It names the themes modules bring in the words of the
 * language module where the document brings one in and it could start, and by their keys where not. A
 * theme file a newer build wrote is told to the host as the manager opens, under this module's own
 * word for it, so the person hears that what this session changes in it is not kept.
 */
private fun moduleManager(context: ServiceContext): ThemeManager {
    val files = context.hostServices.get<HostFiles>()
    val translations = wordsOrKeys(context)
    return ThemeManager(
        store        = context.services.get<SettingsStores>().of(ThemesSettingsDomain),
        file         = ThemeLibraryFile(files.dataDir, files::publish),
        translations = translations,
        scope        = context.coroutineScope,
        write        = files::write,
    ).also { manager ->
        if (manager.readOnly) files.newerBuild(files.dataDir.resolve(ThemeLibraryFile.FILE_NAME), ThemesApi.ID, ThemesModule.FILE)
        context.hostServices.find<TakenContributions>()?.let { taken -> manager.follow(taken.all, context.coroutineScope) }
    }
}

/** The language module's words, or every key as itself where it is not brought in or could not make them. */
private fun wordsOrKeys(context: ServiceContext): Translations = try {
    context.services.find<Translations>()
} catch (e: Exception) {
    log.warn("The language module's words could not be had, so the themes modules bring are named by their keys", e)
    null
} ?: FixedTranslations(Translator.KEYS)

private val log = LoggerFactory.getLogger("hivens.themes.Module")

/**
 * The theme and the scheme a window drawn before the host is up opens on: what the manager opens on,
 * from the module's own settings and theme file, read and not kept. The boot answer and the colour
 * provider's boot answers are both made of it.
 */
internal fun bootOpening(context: BootContext): ThemeOpening = ThemeManager.opening(
    settings = context.settings(ThemesSettingsDomain),
    stored   = ThemeLibraryFile(context.dataDir) { _, _ -> }.load(),
)

/** The theme and the scheme the boot window is drawn in: what the manager opens on, from the same files. */
private fun bootAnswer(context: BootContext): BootValues {
    val opening = bootOpening(context)
    return BootValues.of {
        put(ThemesKeys.Active, opening.theme)
        put(ThemesKeys.Dark, opening.dark)
    }
}
