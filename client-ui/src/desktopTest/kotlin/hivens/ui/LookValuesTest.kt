package hivens.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.i18n.I18nModule
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.look.manager.LookManagerModule
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookAxis
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookPack
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookProvision
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
import hivens.widget.api.CompiledModule
import hivens.widget.api.LocalScope
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.api.SettingsChange
import hivens.widget.api.TakenWords
import hivens.widget.api.current
import hivens.widget.model.ApiVersion
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
import kotlin.test.assertNotSame
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
        assertSame(LookProvision.NONE, ShellLookValues.Look.atBoot(boot))
        assertEquals(NeutralTheme, ShellLookValues.Theme.atBoot(boot))
        assertEquals(true, ShellLookValues.Dark.atBoot(boot))
        assertSame(Translator.KEYS, ShellLookValues.Words.atBoot(boot))
    }

    @Test
    fun `the shell reads the look through the manager's one key, and draws with none where no manager is brought in`() {
        ModuleAppLevel(dir, listOf(LookManagerModule), scope, named = emptySet()).use { shell ->
            var look: LookProvision? = null
            onEventThread {
                val scene = ImageComposeScene(8, 8, Density(1f)) { look = shell.app.read(ShellLookValues.Look) }
                try {
                    scene.settle(1)
                } finally {
                    scene.close()
                }
            }
            assertSame(LookProvision.NONE, look)
        }
        ModuleAppLevel(dir, listOf(LookManagerModule), scope, named = setOf(LookApi.ID)).use { shell ->
            assertNotSame(LookProvision.NONE, shell.value(LookKeys.Provide))
        }
    }

    /** A data class of answers, an axis of them, one provider and a pack naming it, as a compiled look module would carry them. */
    private data class Tint(val name: String)

    private object TintAxis : LookAxis<Tint>("test.tint", ApiVersion(1, 0), Tint::class, Tint("base")) {
        override val label = "tint"
    }

    private object WarmTint : LookProvider<Tint> {
        override val label = "warm"
        override val contract = ApiVersion(1, 0)
        override val axis = TintAxis.id
        override val family = "warm"
        override fun open(context: LookContext): LookAnswers<Tint> = LookAnswers.of(Tint("warm"))
    }

    private object TintModule : CompiledModule {
        override val id = "test.look.tint"
        override val name = id
        override val contributions: List<ModuleContribution> = listOf(TintAxis, WarmTint, object : LookPack {
            override val id = "warm"
            override val label = id
            override val providers = mapOf(TintAxis.id to "warm")
        })
    }

    @Test
    fun `a look module the document does not name reaches the manager, before the boot as after it`() {
        val compiled = listOf(LookManagerModule, TintModule)
        ModuleAppLevel(dir, compiled, scope, named = setOf(LookApi.ID)).use { shell ->
            shell.fire(LookKeys.UpdateSettings, SettingsChange { LookChoice.build { pack = "warm" } })
        }
        val boot = bootValues(dir, compiled = compiled, named = setOf(LookApi.ID))
        val bootState = boot[LookKeys.State]
        assertEquals("warm", bootState?.axis(TintAxis.id)?.family, "the boot window opens with the chosen provider")
        ModuleAppLevel(dir, compiled, scope, named = setOf(LookApi.ID)).use { shell ->
            // The live manager opens its providers off this thread, so its state is read once it settled.
            assertEquals(bootState, eventually(bootState) { shell.value(LookKeys.State) })
        }
    }

    /** What [read] gives once it gives [expected], or after five seconds of not, for a manager working off this thread. */
    private fun <T> eventually(expected: T, read: () -> T): T {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            read().let { if (it == expected) return it }
            Thread.sleep(10)
        }
        return read()
    }

    private val chosen = LookChoice.build {
        pack = "material"
        override("colour", "nx")
    }

    @Test
    fun `an axis brought in cannot replace what the host provides around the look`() {
        val compiled = listOf(LookManagerModule, TintModule)
        ModuleAppLevel(dir, compiled, scope, named = setOf(LookApi.ID)).use { shell ->
            shell.fire(LookKeys.UpdateSettings, SettingsChange { LookChoice.build { pack = "warm" } })
            assertEquals("warm", eventually("warm") { shell.value(LookKeys.State).axis(TintAxis.id)?.family })
            var scopeInside: Any? = "unread"
            var tintInside = ""
            onEventThread {
                val scene = ImageComposeScene(8, 8, Density(1f)) {
                    CompositionLocalProvider(LocalScope provides shell.app) {
                        shell.app.read(ShellLookValues.Look).Provide {
                            scopeInside = LocalScope.current
                            tintInside = TintAxis.local.current.name
                        }
                    }
                }
                try {
                    scene.settle(2)
                } finally {
                    scene.close()
                }
            }
            assertSame(shell.app, scopeInside, "the host's app level is still what the look's subtree reads")
            assertEquals("warm", tintInside, "and the axis is answered under its own local")
        }
    }

    @Test
    fun `the boot window opens in the look the shell then draws`() {
        ModuleAppLevel(dir, listOf(LookManagerModule), scope, named = setOf(LookApi.ID)).use { shell ->
            shell.fire(LookKeys.UpdateSettings, SettingsChange { chosen })
        }
        val boot = bootValues(dir, compiled = listOf(LookManagerModule), named = setOf(LookApi.ID))
        assertNotSame(LookProvision.NONE, ShellLookValues.Look.atBoot(boot))
        ModuleAppLevel(dir, listOf(LookManagerModule), scope, named = setOf(LookApi.ID)).use { shell ->
            assertEquals(chosen, boot[LookKeys.Settings])
            assertEquals(boot[LookKeys.State], eventually(boot[LookKeys.State]) { shell.value(LookKeys.State) })
        }
    }
}
