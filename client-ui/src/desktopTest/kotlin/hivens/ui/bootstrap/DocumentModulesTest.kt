package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.look.colour.api.Colour
import hivens.look.colour.nx.controls.ColourNxControls
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookKeys
import hivens.look.motion.nx.controls.MotionNxControls
import hivens.look.surface.nx.controls.SurfaceNxControls
import hivens.test.stopThenDelete
import hivens.themes.Themes
import hivens.themes.api.ThemesApi
import hivens.ui.ModuleAppLevel
import hivens.ui.ShellLookValues
import hivens.ui.i18n.NexiraStrings
import hivens.ui.prepareLook
import hivens.ui.widgets.modules.CompiledModules
import hivens.widget.api.current
import hivens.widget.model.DefaultLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The modules the boot window asks are the modules the document names, decided before the boot the way
 * the shell decides it once it is up, so a document that leaves a module out has the boot window and
 * the shell agree on the fallbacks.
 */
class DocumentModulesTest {

    private val dir: Path = Files.createTempDirectory("document-modules")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Test
    fun `with no document of the person's the standard list is named, and nothing is written`() {
        assertEquals(DefaultLayout.modules().toSet(), documentModules(dir))
        assertFalse(dir.resolve(Storage.LAYOUT_GRAPH_FILE).exists(), "the first start's own document is the shell's to write")
    }

    @Test
    fun `the standard document names the look manager and no axis, provider or pack of the look`() {
        // The providers' controls are named beside it: a controller is none of the three, the manager
        // never brings one in, and its keys are in only where a document names it.
        val look = DefaultLayout.modules().filter { it.startsWith("nexira.look") }
        assertEquals(listOf(LookApi.ID, ColourNxControls.ID, MotionNxControls.ID, SurfaceNxControls.ID), look)
    }

    @Test
    fun `a document that names modules names those, and one that cannot be read the standard list`() {
        ModuleAppLevel.writeDocument(dir, listOf(ThemesApi.ID, "someone.else"))
        assertEquals(setOf(ThemesApi.ID, "someone.else"), documentModules(dir))

        dir.resolve(Storage.LAYOUT_GRAPH_FILE).writeText("{ damaged")
        assertEquals(DefaultLayout.modules().toSet(), documentModules(dir))
    }

    @Test
    fun `a document without the themes and the language modules has the boot window and the shell agree on the look and the fallbacks`() {
        dir.resolve(Storage.SETTINGS_FILE).writeText("""{"locale":"ru","isDarkTheme":false}""")
        dir.resolve("themes.json").writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")
        ModuleAppLevel.writeDocument(dir, CompiledModules.map { it.id } - ThemesApi.ID - I18nApi.ID)

        val boot = bootValues(dir)
        // The look manager is named, and brings the themes module in for its colour provider before the
        // boot as after it, so both draw the theme the files name, in the mode they name.
        val atBoot = requireNotNull(boot[LookKeys.Answers]).of(Colour)
        assertEquals(Themes.Lotus.light!!.inks[0], atBoot.inkMain)
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            val shellWords = shell.app.current(I18nKeys.Words) ?: ShellLookValues.Words.fallback
            shell.app.prepareLook()
            val live = shell.value(LookKeys.Answers).of(Colour)

            assertEquals(listOf(atBoot.inkMain, atBoot.groundPage, atBoot.groundDark), listOf(live.inkMain, live.groundPage, live.groundDark))
            assertEquals(Translator.KEYS, ShellLookValues.Words.atBoot(boot))
            assertEquals(shellWords.strings(NexiraStrings.NAMESPACE).text("navBack"), ShellLookValues.Words.atBoot(boot).strings(NexiraStrings.NAMESPACE).text("navBack"))
        }
    }
}
