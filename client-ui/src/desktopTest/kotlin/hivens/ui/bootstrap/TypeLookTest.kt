package hivens.ui.bootstrap

import hivens.look.manager.api.LookKeys
import hivens.look.type.api.Type
import hivens.test.stopThenDelete
import hivens.ui.ModuleAppLevel
import hivens.ui.prepareLook
import hivens.ui.theme.nxTypeAnswers
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

/**
 * The window the launcher boots in and the shell take nx's faces from the nx type provider, through
 * the standard document, which names the look manager and not the provider: the manager brings the
 * provider's module in before the boot as after it.
 */
class TypeLookTest {

    private val dir: Path = Files.createTempDirectory("type-look")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Test
    fun `the boot window and the shell write in nx faces`() {
        val atBoot = requireNotNull(bootValues(dir)[LookKeys.Answers]).of(Type)
        assertSame(nxTypeAnswers(), atBoot)
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            shell.app.prepareLook()
            assertEquals("nx", shell.value(LookKeys.State).axis(Type.id)?.family)
            assertSame(atBoot, shell.value(LookKeys.Answers).of(Type))
        }
    }
}
