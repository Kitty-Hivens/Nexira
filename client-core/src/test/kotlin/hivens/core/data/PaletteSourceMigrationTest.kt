package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The legacy boolean only speaks for a settings file written before the source
 * enum existed. Once a source is stored it is an explicit choice and wins, which
 * is what stops the migration from re-firing on every start.
 */
class PaletteSourceMigrationTest {

    @Test
    fun `a fresh install starts on the wallpaper`() {
        assertEquals(PaletteSource.Wallpaper, resolveInitialPaletteSource(SettingsData()))
    }

    @Test
    fun `the legacy opt-out promotes the default to the brand seed`() {
        val legacy = SettingsData(paletteFromWallpaper = false)
        assertEquals(PaletteSource.Brand, resolveInitialPaletteSource(legacy))
    }

    @Test
    fun `a stored source outranks the legacy flag`() {
        val explicit = SettingsData(paletteFromWallpaper = false, paletteSource = PaletteSource.Custom)
        assertEquals(PaletteSource.Custom, resolveInitialPaletteSource(explicit))
    }

    @Test
    fun `a stored wallpaper source survives a legacy flag that agrees with it`() {
        val agreeing = SettingsData(paletteFromWallpaper = true, paletteSource = PaletteSource.Wallpaper)
        assertEquals(PaletteSource.Wallpaper, resolveInitialPaletteSource(agreeing))
    }

    // The palette is generated whatever the source is, so no combination may resolve
    // to "do not generate" -- there is no such state to resolve to.
    @Test
    fun `every combination resolves to a real source`() {
        for (source in PaletteSource.entries) {
            for (legacy in listOf(true, false)) {
                val resolved = resolveInitialPaletteSource(
                    SettingsData(paletteFromWallpaper = legacy, paletteSource = source),
                )
                assertEquals(true, resolved in PaletteSource.entries)
            }
        }
    }
}
