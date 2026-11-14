package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import hivens.ui.appearance.AppearanceStore
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import hivens.ui.theme.ThemeManager
import hivens.ui.theme.Themes
import hivens.ui.utils.PreferenceWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The look the main shell publishes for the console, drawn in the shape the shell
 * draws it: the scheme is read by the theme deep inside the window, and the look is
 * published from a scope above that reads none of it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ShellLookTest {

    private val dir: Path = Files.createTempDirectory("shell-look-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private class Settings : ISettingsService {
        @Volatile private var current = SettingsData(isDarkTheme = true)
        override fun getSettings(): SettingsData = current
        override fun saveSettings(settings: SettingsData) {
            current = settings
        }
    }

    /** A composable boundary of its own, as the window and its layers are. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) = content()

    @Test
    fun `a dark switch and a theme picked reach the console's look at once`() {
        val appearance = AppearanceStore(
            settings    = Settings(),
            preferences = PreferenceWriter("shell-look-test"),
            themes      = ThemeManager(dir) { file, content ->
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            },
            contributed = MutableStateFlow(emptyList()),
            scope       = scope,
        )
        val look = ShellLook(dark = true, locale = AppLocale.ENGLISH)
        val picked = Themes.all.last()
        onEventThread {
            val scene = ImageComposeScene(100, 100, Density(1f)) {
                val isDarkTheme by appearance.dark.collectAsState()
                val library by appearance.library.collectAsState()
                LocaleProvider(locale = AppLocale.ENGLISH) {
                    PublishShellLook(look, appearance, null, CustomizationSettings())
                    Frame { Frame { NxTheme(theme = library.active, dark = isDarkTheme) {} } }
                }
            }
            try {
                scene.settle(4)
                appearance.setDark(false)
                appearance.select(picked.id)
                scene.settle(8, sleepMs = 20)
            } finally {
                scene.close()
            }
        }
        assertFalse(look.dark, "the console was told the old scheme")
        assertEquals(picked.id, look.theme.id, "and the old theme")
    }
}
