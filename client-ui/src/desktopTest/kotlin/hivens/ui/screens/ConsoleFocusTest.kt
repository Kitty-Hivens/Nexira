package hivens.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import hivens.launcher.platform.PlatformPaths
import hivens.ui.theme.NxTheme
import hivens.ui.utils.ConsoleSettings
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import hivens.ui.utils.releaseFocusOnPress
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The console embedded the way a pack's Logs tab embeds it: inside the shell, which
 * lets go of the focus on a press outside whatever holds it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ConsoleFocusTest {

    private class Probe {
        var hasFocus = false
    }

    private var clock = 0L

    private fun ImageComposeScene.frames(count: Int = 10) = repeat(count) {
        clock += 16_000_000L
        render(clock)
    }

    /** Its keys are read at its root, so the console has to hold the focus somewhere for any of them to work. */
    @Test
    fun `a press on the console's toolbar text keeps the focus inside it`() {
        val dir = Files.createTempDirectory("console-focus-")
        val paths = PlatformPaths(
            osName = "linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        val console = GameConsoleService(paths)
        startKoin { modules(module { single { console } }) }
        try {
            SwingUtilities.invokeAndWait {
                val probe = Probe()
                val entries = (1..5).map { LogEntry("line $it", LogType.INFO, timestamp = "00:00:0$it") }
                val scene = ImageComposeScene(800, 400, density = Density(1f)) {
                    NxTheme(dark = true) {
                        Box(Modifier.fillMaxSize().releaseFocusOnPress()) {
                            Box(Modifier.fillMaxSize().onFocusChanged { probe.hasFocus = it.hasFocus }) {
                                ConsoleContent(ConsoleSettings(), onSettingsChange = {}, source = ConsoleSource.FileBacked(entries))
                            }
                        }
                    }
                }
                try {
                    scene.frames()
                    assertTrue(probe.hasFocus, "the log takes the focus as the console opens")

                    // The line count at the toolbar's left: text, nothing that takes the focus.
                    val at = Offset(40f, 16f)
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.frames(2)
                    scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                    scene.frames(2)

                    assertTrue(probe.hasFocus, "the console still holds the focus, so its shortcuts still work")
                } finally {
                    scene.close()
                }
            }
        } finally {
            stopKoin()
            runBlocking { console.close() }
            dir.toFile().deleteRecursively()
        }
    }
}
