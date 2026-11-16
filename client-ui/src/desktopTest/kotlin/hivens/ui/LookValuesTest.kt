package hivens.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.i18n.I18nModule
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.themes.Themes
import hivens.themes.ThemesModule
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
import hivens.ui.bootstrap.bootValues
import hivens.ui.i18n.NexiraStrings
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Scheme
import hivens.widget.api.BootValues
import hivens.widget.api.ModuleStrings
import hivens.widget.api.TakenWords
import hivens.widget.api.current
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The shell draws its windows with what the modules' keys answer at the app level, and with its own
 * fallbacks where the document brings no module that answers them, before the boot as after it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LookValuesTest {

    private val dir: Path = Files.createTempDirectory("look-values")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** The scheme a window of the shell is drawn in over [shell]'s app level, read the way the shell reads it. */
    private fun drawnScheme(shell: ModuleAppLevel): Scheme? {
        var drawn: Scheme? = null
        onEventThread {
            val scene = ImageComposeScene(64, 64, Density(1f)) {
                NxTheme(theme = shell.app.read(ShellLookValues.Theme), dark = shell.app.read(ShellLookValues.Dark)) {
                    drawn = NxTheme.colours.scheme
                }
            }
            try {
                scene.settle(2)
            } finally {
                scene.close()
            }
        }
        return drawn
    }

    @Test
    fun `a document without the themes module has the shell drawn in the neutral theme`() {
        ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, named = setOf(I18nApi.ID)).use { shell ->
            assertEquals(NeutralTheme.dark, drawnScheme(shell), "the shell draws its fallback, dark as a first start opens")
        }
    }

    @Test
    fun `a document with the themes module has the shell drawn in what its keys answer`() {
        ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, named = setOf(I18nApi.ID, ThemesApi.ID)).use { shell ->
            assertEquals(Themes.default.dark, drawnScheme(shell))
        }
    }

    @Test
    fun `a document without the language module has the shell show every key as itself`() {
        ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, named = setOf(ThemesApi.ID)).use { shell ->
            var words: Translator? = null
            onEventThread {
                val scene = ImageComposeScene(8, 8, Density(1f)) { words = shell.app.read(ShellLookValues.Words) }
                try {
                    scene.settle(1)
                } finally {
                    scene.close()
                }
            }
            assertSame(Translator.KEYS, words)
        }
    }

    @Test
    fun `what a module answers before the boot is what the boot window draws`() {
        val boot = BootValues.of {
            put(ThemesKeys.Active, Themes.Lotus)
            put(ThemesKeys.Dark, false)
            put(I18nKeys.Words, NexiraStrings.translator("de"))
        }
        assertEquals(Themes.Lotus, ShellLookValues.Theme.atBoot(boot))
        assertEquals(false, ShellLookValues.Dark.atBoot(boot))
        assertEquals("de", ShellLookValues.Words.atBoot(boot).language.tag)
    }

    @Test
    fun `the look is made ahead of the shell's first read, its words among them`() {
        val reads = AtomicInteger()
        val words = object : TakenWords {
            private val flow = MutableStateFlow(mapOf(NexiraStrings.NAMESPACE to NexiraStrings.words()))
            override val all: StateFlow<Map<String, ModuleStrings>> get() = flow.also { reads.incrementAndGet() }
        }
        ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, words = words).use { shell ->
            assertEquals(0, reads.get(), "nothing is made before it is asked for")
            shell.app.prepareLook()
            assertEquals(1, reads.get(), "the language module made its engine")
            shell.app.current(I18nKeys.Words)
            assertEquals(1, reads.get(), "which the shell's read finds made")
        }
    }

    @Test
    fun `what no module answers before the boot is drawn with the same fallbacks`() {
        val boot = bootValues(dir, compiled = emptyList())
        assertEquals(NeutralTheme, ShellLookValues.Theme.atBoot(boot))
        assertEquals(true, ShellLookValues.Dark.atBoot(boot))
        assertSame(Translator.KEYS, ShellLookValues.Words.atBoot(boot))
    }
}
