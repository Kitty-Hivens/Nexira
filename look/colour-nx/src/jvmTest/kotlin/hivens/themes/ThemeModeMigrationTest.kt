package hivens.themes

import hivens.themes.api.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Contract of [legacyThemeMode]: the legacy wallpaper flag promotes the
 *  DEFAULT mode (a legacy file decodes to it), and any non-default stored mode is
 *  an explicit choice that wins over the stale flag. */
class ThemeModeMigrationTest {

    @Test
    fun defaults_follow_the_system() {
        assertEquals(ThemeMode.System, legacyThemeMode(stored = null, fromWallpaper = false))
    }

    @Test
    fun legacy_wallpaper_flag_promotes_to_wallpaper_mode() {
        assertEquals(ThemeMode.Wallpaper, legacyThemeMode(stored = null, fromWallpaper = true))
    }

    @Test
    fun explicit_manual_wins_over_stale_flag() {
        assertEquals(ThemeMode.Manual, legacyThemeMode(stored = "Manual", fromWallpaper = true))
    }

    @Test
    fun explicit_wallpaper_stays_wallpaper() {
        assertEquals(ThemeMode.Wallpaper, legacyThemeMode(stored = "Wallpaper", fromWallpaper = true))
    }

    @Test
    fun a_mode_this_build_does_not_know_reads_as_the_default() {
        assertEquals(ThemeMode.System, legacyThemeMode(stored = "Seasonal", fromWallpaper = false))
        assertEquals(ThemeMode.Wallpaper, legacyThemeMode(stored = "Seasonal", fromWallpaper = true))
    }

    @Test
    fun the_modes_that_remain_still_read_by_name() {
        ThemeMode.entries.forEach { mode -> assertEquals(mode, legacyThemeMode(stored = mode.name, fromWallpaper = false)) }
    }
}
