package hivens.ui.host

import hivens.i18n.I18nModule
import hivens.i18n.api.I18nKeys
import hivens.themes.Themes
import hivens.themes.ThemesModule
import hivens.themes.api.ThemesKeys
import hivens.ui.ModuleAppLevel
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.ModuleStrings
import hivens.widget.api.TakenWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * What the host says outside a composition, and what the themes module names its themes in, where
 * the language module could not make its words: every key as itself, and nothing else taken down.
 */
class HostWordsTest {

    private val dir: Path = Files.createTempDirectory("host-words")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Words that cannot be read, so the language module cannot make its engine. */
    private val unreadable = object : TakenWords {
        override val all: StateFlow<Map<String, ModuleStrings>> get() = error("the words could not be read")
    }

    @Test
    fun `with the language module's engine failing, the host says every key as itself and loses nothing to it`() {
        ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, words = unreadable).use { shell ->
            val words = HostWords { shell.app }

            assertNull(shell.app.find(I18nKeys.Words), "the engine really could not be made")
            assertEquals("navBack", words.strings(NexiraStrings.NAMESPACE).text("navBack"))
            assertNull(words.readingOrder)
        }
    }

    @Test
    fun `with the language module's engine failing, the themes module still answers`() {
        ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope, words = unreadable).use { shell ->
            assertEquals(Themes.default, shell.value(ThemesKeys.Active))
        }
    }

    @Test
    fun `with the language module's words, the host says them`() {
        ModuleAppLevel(dir, listOf(I18nModule), scope).use { shell ->
            val words = HostWords { shell.app }
            assertNotEquals("navBack", words.strings(NexiraStrings.NAMESPACE).text("navBack"))
            assertEquals(listOf("en"), words.readingOrder)
        }
    }
}
