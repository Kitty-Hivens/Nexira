package hivens.ui.editor

import hivens.ui.layout.LayoutGraphRepository
import hivens.widget.model.FlowSpec
import hivens.widget.model.GRID_MAX
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScreenSpec
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.screen
import hivens.widget.model.traverse
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `nudgeWrap adjusts the live line length and clamps to 0 and MAX`() = runBlocking {
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
                        widgets = listOf(WidgetInstance(WidgetKind("a"), "i1", JsonObject(emptyMap()))),
                        flow    = FlowSpec.grid(2),
                    ),
                )),
            ))
        }

        ctl.nudgeWrap(path, 1)
        awaitWrap(repo, path, 3)

        ctl.nudgeWrap(path, -1)
        awaitWrap(repo, path, 2)

        // Serialized reads inside each write compose without a lost update: five
        // decrements from 2 settle on the model's lower clamp, not a stale 2 - 5.
        repeat(5) { ctl.nudgeWrap(path, -1) }
        awaitWrap(repo, path, 0)

        repeat(GRID_MAX + 5) { ctl.nudgeWrap(path, 1) }
        awaitWrap(repo, path, GRID_MAX)
    }

    // The prop panel and a region's own toggle write the same record. Each change
    // is read against the props as they stand when it lands, so neither undoes the
    // other however close together the two arrive.
    @Test
    fun `two prop writers keep each other's change`() = runBlocking {
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
                        widgets = listOf(WidgetInstance(WidgetKind("a"), "i1", JsonObject(emptyMap()))),
                    ),
                )),
            ))
        }

        ctl.updatePropsFrom(path, "i1", historyKey = "opacityPct") { JsonObject(it + ("opacityPct" to JsonPrimitive(40))) }
        ctl.updatePropsFrom(path, "i1", historyKey = "collapsed") { JsonObject(it + ("collapsed" to JsonPrimitive(true))) }

        withTimeout(3000) {
            while (repo.value().traverse(path)?.widgets?.single()?.props?.size != 2) delay(5)
        }
        val props = repo.value().traverse(path)?.widgets?.single()?.props
        assertEquals(JsonPrimitive(40), props?.get("opacityPct"))
        assertEquals(JsonPrimitive(true), props?.get("collapsed"))
    }

    // A saved arrangement knows nothing of a screen made after it, and loading it
    // used to delete that screen along with what was on it. It stays, its link on
    // the rail comes back, and the load is one step in the history.
    @Test
    fun `loading a saved arrangement keeps the screens somebody made and can be undone`() = runBlocking {
        val repo = LayoutGraphRepository(
            tmpDir.resolve("layout-graph.json"),
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            scope,
        ) { LayoutGraph.EMPTY }
        val ctl = EditModeController(repo, scope)
        val rail = SurfaceId("appshell.leftrail")
        val home = SurfaceId("home.new")
        val note = WidgetInstance(WidgetKind("note"), "note-1", JsonObject(emptyMap()))
        val screen = ScreenSpec(id = "s1", title = "Mine", surface = SurfaceId("screen.s1"))
        repo.update {
            LayoutGraph(
                surfaces = mapOf(
                    rail to SurfaceLayout(slots = mapOf(SlotId("top") to SlotContent())),
                    home to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent())),
                    screen.surface to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(widgets = listOf(note)))),
                ),
                screens = listOf(screen),
            )
        }
        val before = repo.value()
        val saved = LayoutGraph(
            surfaces = mapOf(
                rail to SurfaceLayout(slots = mapOf(SlotId("top") to SlotContent())),
                home to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(
                    widgets = listOf(WidgetInstance(WidgetKind("clock"), "clock-1", JsonObject(emptyMap()))),
                ))),
            ),
        )

        ctl.loadArrangement(saved)
        withTimeout(3000) { while (repo.value().traverse(SlotPath(home, SlotId("main")))?.widgets.isNullOrEmpty()) delay(5) }

        val after = repo.value()
        assertEquals(screen, after.screen("s1"), "the made screen is still there")
        assertEquals(listOf(note), after.traverse(SlotPath(screen.surface, SlotId("main")))?.widgets, "and what was on it")
        assertTrue(after.walkInstances().any { it.props["screen"] == JsonPrimitive("s1") }, "the rail links it again")

        ctl.undo()
        withTimeout(3000) { while (repo.value() != before) delay(5) }
    }

    private suspend fun awaitWrap(repo: LayoutGraphRepository, path: SlotPath, expected: Int) {
        withTimeout(3000) {
            while (repo.value().traverse(path)?.flow?.wrap != expected) delay(5)
        }
        assertEquals(expected, repo.value().traverse(path)?.flow?.wrap)
    }
}
