package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.i18n.I18nModule
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.look.colour.api.ColourAnswers
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookProvision
import hivens.test.stopThenDelete
import hivens.themes.Themes
import hivens.themes.api.ThemesKeys
import hivens.ui.bootstrap.bootValues
import hivens.ui.bootstrap.documentModules
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.modules.CompiledModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The console beside the main window, drawn in the shape the shell draws it: the look read off the app
 * level and the bridge deep inside the window, and the words published from a scope above that reads
 * none of the look.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ShellLookTest {

    private val dir: Path = Files.createTempDirectory("shell-look-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    /** A composable boundary of its own, as the window and its layers are. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) = content()

    /** What the console draws with now, read the way the console window reads it, once [settled] says it is there. */
    private fun drawn(shell: ModuleAppLevel, settled: (ink: Any?, dark: Boolean?) -> Boolean): Pair<Any?, Boolean?> {
        var ink: Any? = null
        var dark: Boolean? = null
        onEventThread {
            val scene = ImageComposeScene(100, 100, Density(1f)) {
                shell.app.read(ShellLookValues.Look).Provide {
                    Frame { Frame { NxTheme { ink = NxInk.main; dark = NxTheme.isDark } } }
                }
            }
            try {
                val end = System.currentTimeMillis() + 5000
                do {
                    scene.settle(2, sleepMs = 10)
                } while (!settled(ink, dark) && System.currentTimeMillis() < end)
            } finally {
                scene.close()
            }
        }
        return ink to dark
    }

    @Test
    fun `a dark switch and a theme picked reach the console at once, through the look it reads itself`() {
        val shell = ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir))
        val picked = Themes.Lotus
        try {
            val before = drawn(shell) { ink, _ -> ink == Themes.default.dark.inks[0] }
            assertEquals(Themes.default.dark.inks[0] to true, before)
            shell.fire(ThemesKeys.SetDark, false)
            shell.fire(ThemesKeys.Choose, picked.id)
            val after = drawn(shell) { ink, dark -> ink == picked.light!!.inks[0] && dark == false }
            assertEquals(picked.light!!.inks[0] to false, after, "the console draws the new theme and mode")
        } finally {
            shell.close()
        }
    }

    @Test
    fun `the words the shell draws in reach the console's look at once`() {
        ModuleAppLevel.writeDocument(dir, listOf(I18nApi.ID))
        val shell = ModuleAppLevel(dir, listOf(I18nModule), scope, named = documentModules(dir))
        val look = ShellLook(words = Translator.KEYS)
        val answered = shell.value(I18nKeys.Words)
        try {
            onEventThread {
                val scene = ImageComposeScene(100, 100, Density(1f)) {
                    ShippedLanguageProvider("en") {
                        PublishShellLook(look, shell.app, "", CustomizationSettings())
                    }
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
        assertSame(answered, look.words, "the console is told the words the language module answers")
    }

    @Test
    fun `a document without the look manager or the themes module draws the console and the boot window in the base answers`() {
        ModuleAppLevel.writeDocument(dir, listOf(I18nApi.ID))
        val shell = ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir))
        try {
            assertNull(shell.app.find(LookKeys.Provide), "no manager answers the look")
            assertEquals(ColourAnswers.BASE.inkMain to ColourAnswers.BASE.groundDark, drawn(shell) { ink, _ -> ink != null })
        } finally {
            shell.close()
        }
        assertSame(LookProvision.NONE, ShellLookValues.Look.atBoot(bootValues(dir)), "and the boot window has no look either")
    }
}
