package hivens.ui.bootstrap

import hivens.look.manager.api.LookKeys
import hivens.look.feedback.api.Feedback
import hivens.test.stopThenDelete
import hivens.ui.ModuleAppLevel
import hivens.ui.prepareLook
import hivens.look.feedback.nx.nxFeedbackAnswers
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
 * The window the launcher boots in and the shell take nx's feedback from the nx feedback provider, through
 * the standard document, which names the look manager and not the provider: the manager brings the
 * provider's module in before the boot as after it.
 */
class FeedbackLookTest {

    private val dir: Path = Files.createTempDirectory("feedback-look")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Test
    fun `the boot window and the shell answer as nx answers`() {
        val atBoot = requireNotNull(bootValues(dir)[LookKeys.Answers]).of(Feedback)
        assertSame(nxFeedbackAnswers(), atBoot)
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            shell.app.prepareLook()
            assertEquals("nx", shell.value(LookKeys.State).axis(Feedback.id)?.family)
            assertSame(atBoot, shell.value(LookKeys.Answers).of(Feedback))
        }
    }
}
