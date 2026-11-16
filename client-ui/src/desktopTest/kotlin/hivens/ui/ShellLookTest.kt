package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.i18n.I18nModule
import hivens.i18n.api.I18nApi
import hivens.themes.Themes
import hivens.themes.ThemesModule
import hivens.themes.api.ThemesKeys
import hivens.ui.bootstrap.bootValues
import hivens.ui.bootstrap.documentModules
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.NexiraStrings
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.theme.NeutralTheme
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
        val shell = ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope)
        val look = ShellLook(theme = Themes.default, dark = true, words = NexiraStrings.translator("en"))
        val picked = Themes.all.last()
        try {
            onEventThread {
                val scene = ImageComposeScene(100, 100, Density(1f)) {
                    val isDarkTheme = shell.app.read(ShellLookValues.Dark)
                    val active = shell.app.read(ShellLookValues.Theme)
                    ShippedLanguageProvider("en") {
                        PublishShellLook(look, shell.app, null, CustomizationSettings())
                        Frame { Frame { NxTheme(theme = active, dark = isDarkTheme) {} } }
                    }
                }
                try {
                    scene.settle(4)
                    shell.fire(ThemesKeys.SetDark, false)
                    shell.fire(ThemesKeys.Choose, picked.id)
                    scene.settle(8, sleepMs = 20)
                } finally {
                    scene.close()
                }
            }
        } finally {
            shell.close()
        }
        assertFalse(look.dark, "the console was told the old scheme")
        assertEquals(picked.id, look.theme.id, "and the old theme")
    }

    @Test
    fun `a document without the themes module has the console told the shell's fallback, and so is the boot window`() {
        ModuleAppLevel.writeDocument(dir, listOf(I18nApi.ID))
        val shell = ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, named = documentModules(dir))
        val look = ShellLook(theme = Themes.default, dark = false, words = NexiraStrings.translator("en"))
        try {
            onEventThread {
                val scene = ImageComposeScene(100, 100, Density(1f)) {
                    PublishShellLook(look, shell.app, null, CustomizationSettings())
                }
                try {
                    scene.settle(4)
                } finally {
                    scene.close()
                }
            }
        } finally {
            shell.close()
        }
        assertEquals(NeutralTheme, look.theme)
        assertEquals(true, look.dark)
        assertEquals(NeutralTheme, ShellLookValues.Theme.atBoot(bootValues(dir, listOf(I18nModule, ThemesModule))))
    }
}
