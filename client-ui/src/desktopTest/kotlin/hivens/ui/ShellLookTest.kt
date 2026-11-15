package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.config.Storage
import hivens.i18n.api.FixedTranslations
import hivens.themes.Themes
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.NexiraStrings
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** A composable boundary of its own, as the window and its layers are. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) = content()

    @Test
    fun `a dark switch and a theme picked reach the console's look at once`() {
        val themes = hostThemeManager(
            dataDir      = dir,
            settings     = SettingsDomainFiles(dir.resolve(Storage.SETTINGS_DIR), dir.resolve(Storage.SETTINGS_FILE)),
            translations = FixedTranslations(NexiraStrings.translator("en")),
            scope        = scope,
        )
        val look = ShellLook(theme = Themes.default, dark = true, words = NexiraStrings.translator("en"))
        val picked = Themes.all.last()
        onEventThread {
            val scene = ImageComposeScene(100, 100, Density(1f)) {
                val isDarkTheme by themes.dark.collectAsState()
                val active by themes.active.collectAsState()
                ShippedLanguageProvider("en") {
                    PublishShellLook(look, themes, FixedTranslations(NexiraStrings.translator("en")), null, CustomizationSettings())
                    Frame { Frame { NxTheme(theme = active, dark = isDarkTheme) {} } }
                }
            }
            try {
                scene.settle(4)
                themes.setDark(false)
                themes.choose(picked.id)
                scene.settle(8, sleepMs = 20)
            } finally {
                scene.close()
            }
        }
        assertFalse(look.dark, "the console was told the old scheme")
        assertEquals(picked.id, look.theme.id, "and the old theme")
    }
}
