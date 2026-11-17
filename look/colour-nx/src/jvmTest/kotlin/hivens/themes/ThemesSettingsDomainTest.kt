package hivens.themes

import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The dark flag and the mode a person chose before the module existed are taken from the
 * launcher's old settings file, and every choice is written back there in the fields a build
 * from before the move reads.
 */
class ThemesSettingsDomainTest {

    private fun legacy(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the old dark flag and mode are the choice, and the theme is left to the theme file`() {
        assertEquals(
            ThemeSettings(theme = null, mode = ThemeMode.Manual, dark = false),
            ThemesSettingsDomain.imported(legacy("""{"isDarkTheme":false,"themeMode":"Manual","locale":"de"}"""), current = null),
        )
        assertEquals(
            ThemeSettings(theme = null, mode = ThemeMode.System, dark = true),
            ThemesSettingsDomain.imported(legacy("""{"isDarkTheme":true,"themeMode":"System"}"""), current = null),
        )
    }

    @Test
    fun `the oldest wallpaper flag, from before the mode, is the wallpaper mode`() {
        assertEquals(ThemeMode.Wallpaper, ThemesSettingsDomain.imported(legacy("""{"themeFromWallpaper":true}"""), current = null)?.mode)
        assertEquals(ThemeMode.Manual, ThemesSettingsDomain.imported(legacy("""{"themeFromWallpaper":true,"themeMode":"Manual"}"""), current = null)?.mode)
    }

    @Test
    fun `a dark flag alone keeps the field's default mode, and a mode alone keeps it dark`() {
        assertEquals(ThemeSettings(mode = ThemeMode.System, dark = false), ThemesSettingsDomain.imported(legacy("""{"isDarkTheme":false}"""), current = null))
        assertEquals(ThemeSettings(mode = ThemeMode.Manual, dark = true), ThemesSettingsDomain.imported(legacy("""{"themeMode":"Manual"}"""), current = null))
    }

    @Test
    fun `a mode a newer build wrote reads as the field's default and costs nothing else`() {
        assertEquals(
            ThemeSettings(mode = ThemeMode.System, dark = false),
            ThemesSettingsDomain.imported(legacy("""{"themeMode":"Seasonal","isDarkTheme":false}"""), current = null),
        )
    }

    @Test
    fun `taken back over the domain's own value, what the old file never held is kept`() {
        val current = ThemeSettings(theme = "signal", mode = ThemeMode.Manual, dark = true, copied = "signal")
        assertEquals(
            current.copy(dark = false),
            ThemesSettingsDomain.imported(legacy("""{"isDarkTheme":false,"themeMode":"Manual","themeFromWallpaper":false}"""), current),
        )
        assertEquals(
            current.copy(mode = ThemeMode.System),
            ThemesSettingsDomain.imported(legacy("""{"themeMode":"System"}"""), current),
            "a field the old file does not hold is the domain's own too",
        )
    }

    @Test
    fun `an old file with none of the fields imports nothing`() {
        assertNull(ThemesSettingsDomain.imported(legacy("""{"locale":"de","javaPath":"/opt/jdk"}"""), current = null))
    }

    @Test
    fun `every choice is mirrored in the fields an older build reads`() {
        assertEquals(
            mapOf("isDarkTheme" to JsonPrimitive(false), "themeMode" to JsonPrimitive("Wallpaper"), "themeFromWallpaper" to JsonPrimitive(true)),
            ThemesSettingsDomain.mirrored(ThemeSettings(theme = "lotus", mode = ThemeMode.Wallpaper, dark = false)),
        )
        assertEquals(
            mapOf("isDarkTheme" to JsonPrimitive(true), "themeMode" to JsonPrimitive("Manual"), "themeFromWallpaper" to JsonPrimitive(false)),
            ThemesSettingsDomain.mirrored(ThemeSettings(theme = null, mode = ThemeMode.Manual, dark = true)),
        )
    }

    @Test
    fun `what is mirrored is read back as the same mode and flag`() {
        ThemeMode.entries.forEach { mode ->
            listOf(true, false).forEach { dark ->
                val written = JsonObject(ThemesSettingsDomain.mirrored(ThemeSettings(theme = "signal", mode = mode, dark = dark)))
                assertEquals(ThemeSettings(theme = null, mode = mode, dark = dark), ThemesSettingsDomain.imported(written, current = null))
            }
        }
    }

    @Test
    fun `the domain is named for its file and its defaults are the old file's`() {
        assertEquals("themes", ThemesSettingsDomain.id)
        assertEquals(ThemeSettings(theme = null, mode = ThemeMode.System, dark = true), ThemesSettingsDomain.defaults())
    }
}
