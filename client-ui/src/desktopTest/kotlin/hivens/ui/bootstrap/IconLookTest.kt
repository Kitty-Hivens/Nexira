package hivens.ui.bootstrap

import hivens.look.icon.api.Icon
import hivens.look.icon.api.Icons
import hivens.look.manager.api.LookKeys
import hivens.test.stopThenDelete
import hivens.ui.ModuleAppLevel
import hivens.ui.prepareLook
import hivens.ui.widgets.modules.CompiledModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The window the launcher boots in and the shell take their icons from the Google icon provider, through
 * the standard document, which names the look manager and not the provider: the manager brings the
 * provider's module in before the boot as after it.
 */
class IconLookTest {

    private val dir: Path = Files.createTempDirectory("icon-look")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Test
    fun `the boot window and the shell draw Google icons`() {
        val atBoot = requireNotNull(bootValues(dir)[LookKeys.Answers]).of(Icon)
        assertNotNull(atBoot.drawing(Icons.Star), "the library answers at boot")
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            shell.app.prepareLook()
            assertEquals("google-rounded", shell.value(LookKeys.State).axis(Icon.id)?.family)
            assertSame(atBoot, shell.value(LookKeys.Answers).of(Icon))
        }
    }
}
