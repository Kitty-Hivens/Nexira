package hivens.ui.theme

import hivens.core.data.PaletteSource
import hivens.core.data.SettingsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import hivens.core.data.PaletteVariant as StoredVariant

/**
 * The seed never comes out missing: every source falls through to the brand colour
 * when it has nothing to give, because generation is unconditional and there is no
 * second code path for "no palette".
 */
class PaletteResolutionTest {

    private val wallpaper = 0xFF3B82F6.toInt()

    private fun spec(settings: SettingsData, seed: Int? = null) =
        paletteSpecFor(settings, dark = true, wallpaperSeedArgb = seed)

    @Test
    fun `the wallpaper source uses the extracted seed`() {
        assertEquals(wallpaper, spec(SettingsData(), wallpaper).seedArgb)
    }

    @Test
    fun `the wallpaper source falls back to the brand seed before one is decoded`() {
        assertEquals(BRAND_SEED_ARGB, spec(SettingsData(), null).seedArgb)
    }

    @Test
    fun `a custom seed is read from settings`() {
        val settings = SettingsData(paletteSource = PaletteSource.Custom, paletteCustomSeed = "#E0533A")
        assertEquals(0xFFE0533A.toInt(), spec(settings, wallpaper).seedArgb)
    }

    @Test
    fun `an unparseable custom seed falls back rather than failing`() {
        val settings = SettingsData(paletteSource = PaletteSource.Custom, paletteCustomSeed = "not a colour")
        assertEquals(BRAND_SEED_ARGB, spec(settings, wallpaper).seedArgb)
    }

    @Test
    fun `the brand source ignores a decoded wallpaper`() {
        val settings = SettingsData(paletteSource = PaletteSource.Brand)
        assertEquals(BRAND_SEED_ARGB, spec(settings, wallpaper).seedArgb)
    }

    @Test
    fun `the legacy opt-out still reaches the brand seed`() {
        val legacy = SettingsData(paletteFromWallpaper = false)
        assertEquals(BRAND_SEED_ARGB, spec(legacy, wallpaper).seedArgb)
    }

    // --- the rest of the spec ---

    @Test
    fun `every stored variant maps to a scheme variant`() {
        for (stored in StoredVariant.entries) {
            val mapped = spec(SettingsData(paletteVariant = stored)).variant
            assertEquals(stored.name, mapped.name, "the two enums drifted apart")
        }
    }

    @Test
    fun `contrast is clamped to the range the science accepts`() {
        assertEquals(1.0, spec(SettingsData(paletteContrast = 4f)).contrastLevel)
        assertEquals(-1.0, spec(SettingsData(paletteContrast = -4f)).contrastLevel)
        assertEquals(0.5, spec(SettingsData(paletteContrast = 0.5f)).contrastLevel)
    }

    @Test
    fun `a secondary seed is carried when set and absent when not`() {
        assertNull(spec(SettingsData()).secondarySeedArgb)
        val twoTone = SettingsData(paletteSecondarySeed = "#12B8A0")
        assertEquals(0xFF12B8A0.toInt(), spec(twoTone).secondarySeedArgb)
    }
}
