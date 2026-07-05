package hivens.ui.bootstrap

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPeekTest {

    @Test
    fun `reads locale and chrome from a real-shaped settings file`() {
        val dir = Files.createTempDirectory("peek")
        dir.resolve("settings.json").writeText(
            """{"locale":"ru","useCustomChrome":false,"isDarkTheme":true,"unrelated":{"nested":1}}"""
        )
        val peek = SettingsPeek.read(dir)
        assertEquals("ru", peek.locale)
        assertEquals(false, peek.useCustomChrome)
    }

    @Test
    fun `missing file falls back to SettingsData defaults`() {
        val dir = Files.createTempDirectory("peek")
        val peek = SettingsPeek.read(dir)
        assertEquals("en", peek.locale)
        assertEquals(true, peek.useCustomChrome)
    }

    @Test
    fun `corrupt json falls back instead of throwing`() {
        val dir = Files.createTempDirectory("peek")
        dir.resolve("settings.json").writeText("{not json at all")
        val peek = SettingsPeek.read(dir)
        assertEquals("en", peek.locale)
        assertEquals(true, peek.useCustomChrome)
    }

    @Test
    fun `fields absent from an older settings file fall back individually`() {
        val dir = Files.createTempDirectory("peek")
        dir.resolve("settings.json").writeText("""{"locale":"de"}""")
        val peek = SettingsPeek.read(dir)
        assertEquals("de", peek.locale)
        assertEquals(true, peek.useCustomChrome)
    }
}
