package hivens.ui.bootstrap

import hivens.core.data.SettingsData
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsPeekTest {

    @Test
    fun `reads the chrome from a real-shaped settings file`() {
        val dir = Files.createTempDirectory("peek")
        dir.resolve("settings.json").writeText(
            """{"locale":"ru","useCustomChrome":false,"isDarkTheme":false,"unrelated":{"nested":1}}"""
        )
        assertEquals(false, SettingsPeek.read(dir).useCustomChrome)
    }

    @Test
    fun `missing file falls back to SettingsData defaults`() {
        val dir = Files.createTempDirectory("peek")
        assertEquals(SettingsData().useCustomChrome, SettingsPeek.read(dir).useCustomChrome)
    }

    @Test
    fun `corrupt json falls back instead of throwing`() {
        val dir = Files.createTempDirectory("peek")
        dir.resolve("settings.json").writeText("{not json at all")
        assertEquals(true, SettingsPeek.read(dir).useCustomChrome)
    }

    // The settings screen persists SettingsData; the window host reads the file back by
    // literal key before Koin exists. Renaming the field on either side would silently
    // strand the switch on the value it had at install time.
    @Test
    fun `a persisted SettingsData is what the peek reads back`() {
        val json = Json { encodeDefaults = true }
        for (chrome in listOf(true, false)) {
            val dir = Files.createTempDirectory("peek")
            dir.resolve("settings.json")
                .writeText(json.encodeToString(SettingsData(useCustomChrome = chrome, locale = "de")))
            assertEquals(chrome, SettingsPeek.read(dir).useCustomChrome)
        }
    }

    @Test
    fun `fields absent from an older settings file fall back individually`() {
        val dir = Files.createTempDirectory("peek")
        dir.resolve("settings.json").writeText("""{"locale":"de"}""")
        assertEquals(true, SettingsPeek.read(dir).useCustomChrome)
    }
}
