package hivens.ui.editor

import hivens.launcher.LayoutGraphRepository
import hivens.widget.model.LayoutGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Pins the keybind bridge contract: requestEditToggle() is the only
// mutator of the observable signal the active EditorSurfaceHost watches,
// and each call advances it by one. The host's seen-init / toggle logic
// needs a Compose snapshot harness and stays covered by live smoke.
class EditModeControllerTest {

    private lateinit var tmpDir: Path
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("edit-mode-controller-test")
        scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmpDir.toFile().deleteRecursively()
    }

    private fun controller(): EditModeController {
        val repo = LayoutGraphRepository(
            tmpDir.resolve("layout-graph.json"),
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            scope,
        ) { LayoutGraph.EMPTY }
        return EditModeController(repo, scope)
    }

    @Test
    fun `requestEditToggle increments the signal on each call`() {
        val controller = controller()
        assertEquals(0, controller.editToggleSignal.value)
        controller.requestEditToggle()
        assertEquals(1, controller.editToggleSignal.value)
        controller.requestEditToggle()
        assertEquals(2, controller.editToggleSignal.value)
    }
}
