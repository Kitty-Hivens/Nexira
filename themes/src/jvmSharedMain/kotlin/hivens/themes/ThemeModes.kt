package hivens.themes

import hivens.themes.api.ThemeMode

/**
 * Wallpapers below this luminance drive the dark theme.
 *
 * The comparison is strict, so exactly mid-grey resolves to the light theme.
 * Nothing rides on which side takes the tie: it is stated only because a
 * threshold nobody wrote down is a threshold somebody later flips by accident.
 */
const val WALLPAPER_DARK_THRESHOLD = 0.5f

/**
 * The dark flag an automatic theme source wants, or null when nothing should
 * change.
 *
 * Three sources write one boolean: the manual toggle, the OS scheme, and the
 * wallpaper's brightness. Stated once here: the mode picks which source is
 * listened to at all, a source with nothing to say (no wallpaper decoded yet, no
 * readable OS scheme) says nothing, and a source that agrees with the current
 * value asks for no write.
 *
 * Returning null rather than the unchanged value is the point: every caller
 * both sets state and persists, and a settings file rewritten on every
 * wallpaper tick is a write per frame during a crossfade.
 */
fun darkThemeFor(
    mode: ThemeMode,
    current: Boolean,
    wallpaperLuminance: Float? = null,
    systemDark: Boolean? = null,
): Boolean? {
    val wanted = when (mode) {
        // The user said so, and nothing automatic overrides that until they
        // choose another mode.
        ThemeMode.Manual -> null
        ThemeMode.System -> systemDark
        ThemeMode.Wallpaper -> wallpaperLuminance?.let { it < WALLPAPER_DARK_THRESHOLD }
    }
    return wanted?.takeIf { it != current }
}

/**
 * The mode the former settings file's `themeMode` and `themeFromWallpaper` fields say.
 *
 * A file that opted into the wallpaper before the mode existed holds no mode, which reads as
 * the field's default, [ThemeMode.System], so the old flag promotes exactly that default to
 * [ThemeMode.Wallpaper]. A stored mode other than the default is an explicit choice and wins
 * over the flag. A mode this build does not know, a newer build's say, reads as the default.
 */
fun legacyThemeMode(stored: String?, fromWallpaper: Boolean): ThemeMode {
    val mode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    return if (mode == ThemeMode.System && fromWallpaper) ThemeMode.Wallpaper else mode
}
