package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Contract of [resolveInitialThemeMode]: the legacy wallpaper flag promotes the
 *  default mode, and an explicitly stored mode always wins over the stale flag. */
class ThemeModeMigrationTest {

    @Test
    fun defaults_start_manual() {
        assertEquals(ThemeMode.Manual, resolveInitialThemeMode(SettingsData()))
    }

    @Test
    fun legacy_wallpaper_flag_promotes_to_wallpaper_mode() {
        val legacy = SettingsData(themeFromWallpaper = true)
        assertEquals(ThemeMode.Wallpaper, resolveInitialThemeMode(legacy))
    }

    @Test
    fun explicit_mode_wins_over_stale_flag() {
        val explicit = SettingsData(themeFromWallpaper = true, themeMode = ThemeMode.System)
        assertEquals(ThemeMode.System, resolveInitialThemeMode(explicit))
    }

    @Test
    fun explicit_manual_with_flag_off_stays_manual() {
        val manual = SettingsData(themeFromWallpaper = false, themeMode = ThemeMode.Manual)
        assertEquals(ThemeMode.Manual, resolveInitialThemeMode(manual))
    }
}
