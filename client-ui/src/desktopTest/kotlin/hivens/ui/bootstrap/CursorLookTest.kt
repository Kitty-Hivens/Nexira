package hivens.ui.bootstrap

import androidx.compose.ui.input.pointer.PointerIcon
import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorRole
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window the launcher boots in and the shell take their pointers from the nx cursor provider, over the
 * system's, through the standard document, which names the look manager and neither provider: the manager
 * brings nx's module in, and what that requires with it.
 */
class CursorLookTest {

    private val dir: Path = Files.createTempDirectory("cursor-look")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Test
    fun `the boot window and the shell point with nx over the system`() {
        val atBoot = requireNotNull(bootValues(dir)[LookKeys.Answers]).of(Cursor)
        assertSame(PointerIcon.Default, atBoot.pointer(CursorRole.Pressable))
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            shell.app.prepareLook()
            assertEquals("nx", shell.value(LookKeys.State).axis(Cursor.id)?.family)
            assertSame(atBoot, shell.value(LookKeys.Answers).of(Cursor), "the same answers, the system's handed through")
            assertTrue(shell.modulesIn.ids.value.containsAll(listOf("nexira.look.cursor.nx", "nexira.look.cursor.system")), "nx's module comes with what it requires")
        }
    }
}
