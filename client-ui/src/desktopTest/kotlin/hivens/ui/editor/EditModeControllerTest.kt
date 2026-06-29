package hivens.ui.editor

import hivens.launcher.LayoutGraphRepository
import hivens.widget.model.GRID_COLUMNS_MAX
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.traverse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun `nudgeGridColumns adjusts the live count and clamps to 1 and MAX`() = runBlocking {
        val repo = LayoutGraphRepository(
            tmpDir.resolve("layout-graph.json"),
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            scope,
        ) { LayoutGraph.EMPTY }
        val ctl  = EditModeController(repo, scope)
        val path = SlotPath(SurfaceId("home.new"), SlotId("main"))
        repo.update {
            LayoutGraph(surfaces = mapOf(
                SurfaceId("home.new") to SurfaceLayout(slots = mapOf(
                    SlotId("main") to SlotContent(
                        widgets     = listOf(WidgetInstance(WidgetKind("a"), "i1", JsonObject(emptyMap()))),
                        orientation = SlotOrientation.Grid,
                        gridColumns = 2,
                    ),
                )),
            ))
        }

        ctl.nudgeGridColumns(path, 1)
        awaitColumns(repo, path, 3)

        ctl.nudgeGridColumns(path, -1)
        awaitColumns(repo, path, 2)

        // Serialized reads inside each write compose without a lost update: five
        // decrements from 2 settle on the model's lower clamp, not a stale 2 - 5.
        repeat(5) { ctl.nudgeGridColumns(path, -1) }
        awaitColumns(repo, path, 1)

        repeat(GRID_COLUMNS_MAX + 5) { ctl.nudgeGridColumns(path, 1) }
        awaitColumns(repo, path, GRID_COLUMNS_MAX)
    }

    private suspend fun awaitColumns(repo: LayoutGraphRepository, path: SlotPath, expected: Int) {
        withTimeout(3000) {
            while (repo.value().traverse(path)?.gridColumns != expected) delay(5)
        }
        assertEquals(expected, repo.value().traverse(path)?.gridColumns)
    }
}
