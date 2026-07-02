package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Contract of [resolveInitialThemeMode]: the legacy wallpaper flag promotes the
 *  DEFAULT mode (a legacy file decodes to it), and any non-default stored mode is
 *  an explicit choice that wins over the stale flag. */
class ThemeModeMigrationTest {

    @Test
    fun defaults_follow_the_system() {
        assertEquals(ThemeMode.System, resolveInitialThemeMode(SettingsData()))
    }

    @Test
    fun legacy_wallpaper_flag_promotes_to_wallpaper_mode() {
        val legacy = SettingsData(themeFromWallpaper = true)
        assertEquals(ThemeMode.Wallpaper, resolveInitialThemeMode(legacy))
    }

    @Test
    fun explicit_manual_wins_over_stale_flag() {
        val explicit = SettingsData(themeFromWallpaper = true, themeMode = ThemeMode.Manual)
        assertEquals(ThemeMode.Manual, resolveInitialThemeMode(explicit))
    }

    @Test
    fun explicit_wallpaper_stays_wallpaper() {
        val wallpaper = SettingsData(themeFromWallpaper = true, themeMode = ThemeMode.Wallpaper)
        assertEquals(ThemeMode.Wallpaper, resolveInitialThemeMode(wallpaper))
    }
}
