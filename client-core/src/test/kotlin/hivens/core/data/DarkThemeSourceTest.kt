package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Three sources write one boolean: the manual toggle, the OS scheme, and the
 * wallpaper's brightness. Each used to decide for itself, in its own effect,
 * with the persist repeated beside it -- so what actually governed the theme
 * could only be reconstructed by reading all three.
 *
 * Null means "change nothing", and it carries weight: every caller persists
 * what it applies, so a source that answers on every tick would rewrite the
 * settings file throughout a wallpaper crossfade.
 */
class DarkThemeSourceTest {

    @Test
    fun `a manual choice is not overridden by either automatic source`() {
        assertNull(darkThemeFor(ThemeMode.Manual, current = true, wallpaperLuminance = 0.9f))
        assertNull(darkThemeFor(ThemeMode.Manual, current = false, systemDark = true))
    }

    @Test
    fun `a dark wallpaper asks for the dark theme`() {
        assertEquals(true, darkThemeFor(ThemeMode.Wallpaper, current = false, wallpaperLuminance = 0.2f))
    }

    @Test
    fun `a bright wallpaper asks for the light theme`() {
        assertEquals(false, darkThemeFor(ThemeMode.Wallpaper, current = true, wallpaperLuminance = 0.8f))
    }

    @Test
    fun `the threshold is strict, so exact mid-grey is light`() {
        assertEquals(true, darkThemeFor(ThemeMode.Wallpaper, current = false, wallpaperLuminance = WALLPAPER_DARK_THRESHOLD - 0.001f))
        assertEquals(false, darkThemeFor(ThemeMode.Wallpaper, current = true, wallpaperLuminance = WALLPAPER_DARK_THRESHOLD))
    }

    @Test
    fun `a wallpaper that agrees with the current theme asks for no write`() {
        assertNull(
            darkThemeFor(ThemeMode.Wallpaper, current = true, wallpaperLuminance = 0.1f),
            "persisting an unchanged value would rewrite the settings file on every crossfade tick",
        )
    }

    @Test
    fun `no wallpaper decoded yet says nothing`() {
        assertNull(darkThemeFor(ThemeMode.Wallpaper, current = true, wallpaperLuminance = null))
    }

    @Test
    fun `the OS scheme drives system mode`() {
        assertEquals(false, darkThemeFor(ThemeMode.System, current = true, systemDark = false))
        assertNull(darkThemeFor(ThemeMode.System, current = true, systemDark = true), "already dark")
    }

    @Test
    fun `an unreadable OS scheme says nothing`() {
        // No portal backend on Linux reads as null rather than as light.
        assertNull(darkThemeFor(ThemeMode.System, current = true, systemDark = null))
    }

    @Test
    fun `each mode ignores the other mode's source`() {
        assertNull(
            darkThemeFor(ThemeMode.System, current = true, wallpaperLuminance = 0.9f),
            "a bright wallpaper must not lighten a launcher following the OS",
        )
        assertNull(
            darkThemeFor(ThemeMode.Wallpaper, current = true, systemDark = false),
            "the OS scheme must not touch a launcher following its wallpaper",
        )
    }
}
