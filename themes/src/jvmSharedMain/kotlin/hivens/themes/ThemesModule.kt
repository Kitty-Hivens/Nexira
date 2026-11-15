// The themes module is compiled into the application and trusted with the host's own
// services: the manager it answers over is the host's, made before any module loads.
@file:OptIn(HostApi::class)

package hivens.themes

import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeInputs
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeSettings
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.SettingsDomain
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostService
import hivens.widget.api.hostServices
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The themes module as the application carries it: compiled in, and brought in by a document
 * that names [ThemesApi.ID].
 *
 * The manager itself is made by the host before any module loads, because the window the
 * launcher boots in is already drawn in the person's theme, and handed to the module as a host
 * service. The module owns what the person chose, as its settings domain, the keys that read
 * the themes and change the choice, and the kind of contribution a module brings a theme as.
 */
object ThemesModule : CompiledModule {
    override val id: String = ThemesApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings(
        mapOf(
            "en" to mapOf(NAME to "Themes"),
            "ru" to mapOf(NAME to "Темы"),
            "de" to mapOf(NAME to "Designs"),
            "ja" to mapOf(NAME to "テーマ"),
        ),
    )
    override val provides: Map<String, ApiVersion> = mapOf(ThemesApi.ID to ThemesApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(ThemesApi.ID to setOf(ThemesApi::class.java.name.substringBeforeLast('.')))
    override val contributionKinds: List<Class<out ModuleContribution>> = listOf(ThemeContribution::class.java)
    override val contributions: List<ModuleContribution> = listOf(ThemesKeysContribution)

    private const val NAME = "themes.name"
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
 * The keys in [ThemesKeys], answered over the host's manager, and [ThemeInputs], kept for the host
 * and the modules compiled into it: what the wallpaper and the system's scheme say is the host's
 * to tell, and no jar's.
 */
object ThemesKeysContribution : KeyContribution {
    override val id: String = "themes.keys"
    override val label: String = "Themes"

    override fun declare(keys: ModuleKeys) {
        keys.settings(ThemesSettingsDomain, ThemesKeys.Settings, ThemesKeys.UpdateSettings)
        keys.source(ThemesKeys.Active, sample = { Themes.default }) { app -> flowSource(app.hostServices.get<ThemeManager>().active) }
        keys.source(ThemesKeys.Offered, sample = { Themes.all }) { app -> flowSource(app.hostServices.get<ThemeManager>().offered) }
        keys.source(ThemesKeys.Dark, sample = { true }) { app -> flowSource(app.hostServices.get<ThemeManager>().dark) }
        keys.source(ThemesKeys.Mode, sample = { ThemeMode.Manual }) { app -> flowSource(app.hostServices.get<ThemeManager>().mode) }
        keys.source(ThemesKeys.SystemSchemeAvailable, sample = { false }) { app ->
            flowSource(app.hostServices.get<ThemeManager>().systemSchemeAvailable)
        }
        keys.command(ThemesKeys.Choose) { app -> command(app.hostServices.get<ThemeManager>()::choose) }
        keys.command(ThemesKeys.SetDark) { app -> command(app.hostServices.get<ThemeManager>()::setDark) }
        keys.command(ThemesKeys.SetMode) { app -> command(app.hostServices.get<ThemeManager>()::setMode) }
        keys.hostService<ThemeInputs>(ScopeLevel.App) { context -> context.hostServices.get<ThemeManager>() }
    }
}
