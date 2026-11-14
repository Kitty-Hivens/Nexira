package hivens.ui.appearance

import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyStore
import hivens.core.data.ThemeMode
import hivens.core.data.darkThemeFor
import hivens.core.data.resolveInitialThemeMode
import hivens.ui.i18n.AppLocale
import hivens.ui.theme.ThemeLibrary
import hivens.ui.theme.ThemeManager
import hivens.ui.utils.PreferenceWriter
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.ui.widgets.modules.toThemes
import hivens.widget.api.ThemeContribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How the launcher looks and speaks: the themes there are and the one in use,
 * whether it is drawn dark, what decides that, and the language.
 *
 * One for the app, read by every window and every composition through the sources
 * in [hivens.ui.widgets.Sources] and changed through the commands in
 * [hivens.ui.widgets.Commands], so a shell draws with what this holds instead of
 * keeping its own copy. Each change is saved through the settings service or the
 * theme file the moment it is made, on the preference writer's thread, the way the
 * shell saved them when it held them.
 *
 * The automatic sources of the dark flag are inputs the host feeds in: the
 * wallpaper's brightness through [wallpaperLuminanceChanged] and the system scheme
 * through [systemSchemeChanged]. Only the source [mode] names is listened to, and a
 * source that agrees with the current flag writes nothing (see [darkThemeFor]).
 *
 * [contributed] is what the loaded widget modules bring, read in the current
 * language, and never stored.
 */
class AppearanceStore(
    private val settings: ISettingsService,
    private val preferences: PreferenceWriter,
    private val themes: ThemeManager,
    private val contributed: StateFlow<List<Contributed<ThemeContribution>>>,
    scope: CoroutineScope,
) {
    private val lock = Any()

    private val initial = settings.getSettings()

    private val _dark = MutableStateFlow(initial.isDarkTheme)

    /** Whether the launcher is drawn in its dark scheme. */
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    private val _mode = MutableStateFlow(resolveInitialThemeMode(initial))

    /** Which source decides [dark]: the person, the system scheme or the wallpaper. */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _locale = MutableStateFlow(AppLocale.fromTag(initial.locale))

    /** The language the launcher speaks. */
    val locale: StateFlow<AppLocale> = _locale.asStateFlow()

    private val _systemSchemeAvailable = MutableStateFlow(false)

    /** Whether the host can read the system's colour scheme at all, so following it can be offered. */
    val systemSchemeAvailable: StateFlow<Boolean> = _systemSchemeAvailable.asStateFlow()

    // What themes.json holds. The module themes are laid over it in [library].
    private var stored: ThemeLibrary = themes.load().also {
        // The manager decides read only on its own, in a module that cannot see the
        // notice registry, so the fact is carried across here. Made before the shell's
        // first frame, ahead of the notice that reads it.
        if (themes.readOnly) NewerBuildData.record(ReadOnlyStore.Theme)
    }

    private val _library = MutableStateFlow(compose(stored, contributed.value, _locale.value))

    /** Every theme there is and the one in use, the modules' among them. */
    val library: StateFlow<ThemeLibrary> = _library.asStateFlow()

    // The last brightness the wallpaper reported, which a change of mode reads.
    private var luminance: Float? = null

    init {
        scope.launch { contributed.collect { refresh() } }
    }

    /** Draws dark or light as the person chose. An explicit choice always wins, so an automatic mode drops back to the person's own. */
    fun setDark(dark: Boolean) {
        synchronized(lock) {
            _mode.value = ThemeMode.Manual
            _dark.value = dark
        }
        preferences.write("the colour scheme") {
            settings.updateSettings { it.copy(isDarkTheme = dark, themeMode = ThemeMode.Manual, themeFromWallpaper = false) }
        }
    }

    /** Hands the dark flag to [mode], and applies at once what that source last said. */
    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        // The legacy wallpaper flag mirrors the mode, so a build from before the mode
        // existed keeps the wallpaper choice coherent.
        preferences.write("the theme mode") {
            settings.updateSettings { it.copy(themeMode = mode, themeFromWallpaper = mode == ThemeMode.Wallpaper) }
        }
        follow { current -> darkThemeFor(mode, current, wallpaperLuminance = luminance) }
    }

    /** What the wallpaper now looks like, null while there is none. Decides [dark] in the wallpaper mode. */
    fun wallpaperLuminanceChanged(value: Float?) {
        synchronized(lock) {
            if (value == luminance) return
            luminance = value
        }
        follow { current -> darkThemeFor(_mode.value, current, wallpaperLuminance = value) }
    }

    /** What the system's scheme now says, null where it cannot be read. Decides [dark] in the system mode. */
    fun systemSchemeChanged(systemDark: Boolean?) {
        follow { current -> darkThemeFor(_mode.value, current, systemDark = systemDark) }
    }

    /** Whether the host can read the system's scheme. Said once by the host when it has found out. */
    fun setSystemSchemeAvailable(available: Boolean) {
        _systemSchemeAvailable.value = available
    }

    /** Puts the theme of [id] in use. */
    fun select(id: String) {
        val library = synchronized(lock) {
            stored = stored.copy(selected = id)
            refreshLocked()
            stored
        }
        preferences.write("the theme") { themes.save(library) }
    }

    /**
     * Keeps the wallpaper's [colours], best first, with the selection, so the theme
     * made from the wallpaper opens in itself next time. Written only when the
     * picture changed, which is not every frame of a crossfade.
     */
    fun setWallpaperColours(colours: List<Int>) {
        val library = synchronized(lock) {
            if (colours == stored.wallpaper) return
            stored = stored.copy(wallpaper = colours)
            refreshLocked()
            stored
        }
        preferences.write("the wallpaper colours") { themes.save(library) }
    }

    /** Speaks [locale] from now on. The module themes are named in it too. */
    fun setLocale(locale: AppLocale) {
        synchronized(lock) {
            _locale.value = locale
            refreshLocked()
        }
        preferences.write("the language") { settings.updateSettings { it.copy(locale = locale.tag) } }
    }

    // Sets the flag to what [wanted] says from the current one and saves it, so the
    // next start opens on what was last seen instead of flashing the old scheme.
    private fun follow(wanted: (current: Boolean) -> Boolean?) {
        val next = synchronized(lock) {
            wanted(_dark.value)?.also { _dark.value = it }
        } ?: return
        preferences.write("the colour scheme") { settings.updateSettings { it.copy(isDarkTheme = next) } }
    }

    private fun refresh() = synchronized(lock) { refreshLocked() }

    private fun refreshLocked() {
        _library.value = compose(stored, contributed.value, _locale.value)
    }

    private fun compose(stored: ThemeLibrary, modules: List<Contributed<ThemeContribution>>, locale: AppLocale): ThemeLibrary =
        stored.copy(contributed = modules.toThemes(locale.tag))
}
