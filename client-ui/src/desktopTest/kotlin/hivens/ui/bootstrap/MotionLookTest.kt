package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.look.manager.api.LookKeys
import hivens.look.motion.api.Motion
import hivens.look.motion.nx.MotionNxKeys
import hivens.test.stopThenDelete
import hivens.ui.ModuleAppLevel
import hivens.ui.prepareLook
import hivens.look.motion.nx.nxMotionAnswers
import hivens.ui.widgets.modules.CompiledModules
import hivens.widget.api.current
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window the launcher boots in and the shell move as the nx motion provider answers, through the
 * standard document, which names the look manager and not the provider: the manager brings the
 * provider's module in before the boot as after it. A reduce motion the launcher kept before the
 * provider had settings of its own holds in both, and is kept in the provider's settings from then on.
 */
class MotionLookTest {

    private val dir: Path = Files.createTempDirectory("motion-look")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    @Test
    fun `the boot window and the shell move as nx moves`() {
        val atBoot = requireNotNull(bootValues(dir)[LookKeys.Answers]).of(Motion)
        assertSame(nxMotionAnswers(reduceMotion = false), atBoot)
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            shell.app.prepareLook()
            assertEquals("nx", shell.value(LookKeys.State).axis(Motion.id)?.family)
            assertSame(atBoot, shell.value(LookKeys.Answers).of(Motion))
        }
    }

    @Test
    fun `a reduce motion kept in customization dot json holds before the boot and once up, and is kept in the provider's own settings`() {
        dir.resolve("customization.json").writeText("""{"surfaceBlur":true,"reduceMotion":true}""")
        val atBoot = requireNotNull(bootValues(dir)[LookKeys.Answers]).of(Motion)
        assertSame(nxMotionAnswers(reduceMotion = true), atBoot)
        ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).use { shell ->
            shell.app.prepareLook()
            assertSame(atBoot, shell.value(LookKeys.Answers).of(Motion))
            assertEquals(true, shell.app.current(MotionNxKeys.ReduceMotion))

            val kept = Files.list(dir.resolve(Storage.SETTINGS_DIR)).use { files -> files.toList() }.single { it.fileName.toString().startsWith("motion.nx") }
            assertTrue(""""reduceMotion": true""" in kept.readText().replace("\":true", "\": true"), "carried over into ${kept.fileName}")

            shell.fire(MotionNxKeys.SetReduceMotion, false)
            val end = System.currentTimeMillis() + 5_000
            while (shell.value(LookKeys.Answers).of(Motion) !== nxMotionAnswers(false) && System.currentTimeMillis() < end) Thread.sleep(10)
            assertSame(nxMotionAnswers(reduceMotion = false), shell.value(LookKeys.Answers).of(Motion), "a change moves the look while it is open")
        }
    }
}
