package hivens.themes.api

import hivens.ui.theme.Theme
import hivens.ui.theme.WallpaperTone
import hivens.widget.api.SettingsChange
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.serialization.Serializable

/**
 * What decides whether the launcher is drawn dark or light. Exactly one is in charge:
 *
 * - [Manual]: the person's own day and night switch.
 * - [System]: the system's colour scheme, where the host can read one.
 * - [Wallpaper]: the wallpaper's average brightness.
 *
 * Choosing day or night by hand while [System] or [Wallpaper] is in charge hands the
 * choice back to [Manual]: a choice made by hand always wins over an automatic one.
 */
@Serializable
enum class ThemeMode { Manual, System, Wallpaper }

/**
 * The themes module's settings: what the person chose.
 *
 * [dark] is whether the launcher is drawn dark now, whoever decided it, so the next start
 * opens on what was last seen rather than flashing the other scheme until the system or
 * the wallpaper has been read.
 */
@Serializable
data class ThemeSettings(
    /**
     * The theme chosen, by id. Null where none was chosen in this setting yet: then the one
     * the library of themes was last left on is in use, which is also what a launcher from
     * before this setting chose. A theme that is not on offer, one whose module is away say,
     * stays chosen while the default theme is drawn in its place.
     */
    val theme: String? = null,
    val mode: ThemeMode = ThemeMode.System,
    val dark: Boolean = true,
    /**
     * The theme last copied into the theme file for a build from before this setting, kept by the
     * themes module and not a choice of its own. A theme found there that is not this one is one an
     * older build chose since, and is taken back. Null where nothing was copied yet.
     */
    val copied: String? = null,
)

/**
 * What the host reads of the system's colour scheme. [readable] says whether it can read
 * one at all, which decides whether following the system is offered. [dark] is what the
 * scheme says now, null where it cannot tell at the moment.
 */
data class SystemScheme(val readable: Boolean, val dark: Boolean? = null)

/**
 * What only the host feeds the themes module: what the wallpaper shows and what the system's
 * scheme says. A service the module keeps for the host and the modules compiled into it,
 * reached through the host's view of a level, so no jar can fake the system's scheme or write
 * a wallpaper's colours into the person's files.
 */
interface ThemeInputs {
    /**
     * What the wallpaper now shows: its colours, which the theme made from it is made of and
     * which are kept so it opens in itself next time, and its brightness, which decides
     * [ThemesKeys.Dark] in [ThemeMode.Wallpaper]. Told each time the picture is read, and
     * [WallpaperTone.NONE] once there is none.
     */
    fun wallpaper(tone: WallpaperTone)

    /** What the system's colour scheme says. Decides [ThemesKeys.Dark] in [ThemeMode.System]. */
    fun systemScheme(scheme: SystemScheme)
}

/**
 * The keys the themes module answers, all at the app level. The ids are constants so a
 * widget can name them in its needs.
 *
 * The values come from one place, so a theme chosen through [Choose] and one chosen
 * through [UpdateSettings] are the same choice, read back through every source here.
 */
object ThemesKeys {
    const val SETTINGS_ID = "themes.settings"
    const val UPDATE_SETTINGS_ID = "themes.settings.update"
    const val ACTIVE_ID = "themes.active"
    const val OFFERED_ID = "themes.offered"
    const val DARK_ID = "themes.dark"
    const val MODE_ID = "themes.mode"
    const val SYSTEM_SCHEME_AVAILABLE_ID = "themes.system.available"
    const val CHOOSE_ID = "themes.choose"
    const val SET_DARK_ID = "themes.dark.set"
    const val SET_MODE_ID = "themes.mode.set"

    /** What the person chose, as stored. */
    val Settings = SourceKey<ThemeSettings>(SETTINGS_ID, ScopeLevel.App)

    /** Changes what the person chose: `update { it.copy(mode = ThemeMode.Wallpaper) }`. */
    val UpdateSettings = CommandKey<SettingsChange<ThemeSettings>>(UPDATE_SETTINGS_ID, ScopeLevel.App)

    /** The theme the launcher is drawn in: the one chosen where it is on offer, the default where it is not. */
    val Active = SourceKey<Theme>(ACTIVE_ID, ScopeLevel.App)

    /** Every theme on offer: the ones that ship, the one made from the wallpaper, the ones modules brought and the person's own. */
    val Offered = SourceKey<List<Theme>>(OFFERED_ID, ScopeLevel.App)

    /** Whether the launcher is drawn in the dark scheme of [Active]. */
    val Dark = SourceKey<Boolean>(DARK_ID, ScopeLevel.App)

    /** What decides [Dark]. */
    val Mode = SourceKey<ThemeMode>(MODE_ID, ScopeLevel.App)

    /** Whether the host can read the system's colour scheme, so following it can be offered. */
    val SystemSchemeAvailable = SourceKey<Boolean>(SYSTEM_SCHEME_AVAILABLE_ID, ScopeLevel.App)

    /** Puts the theme of this id in use. */
    val Choose = CommandKey<String>(CHOOSE_ID, ScopeLevel.App)

    /** Draws dark or light by the person's own choice, which hands [Mode] back to [ThemeMode.Manual]. */
    val SetDark = CommandKey<Boolean>(SET_DARK_ID, ScopeLevel.App)

    /** Says what decides [Dark] from now on, and applies at once what that source last said. */
    val SetMode = CommandKey<ThemeMode>(SET_MODE_ID, ScopeLevel.App)
}
