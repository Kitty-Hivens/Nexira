package hivens.ui.editor

import hivens.ui.layout.LayoutGraphRepository
import hivens.widget.api.KeyDeclaration
import hivens.widget.api.KeyKind
import hivens.widget.api.KeyOrigin
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the editor lets a widget stand: every key it needs has to answer on the
 * surface it is put on, and a drop or a move that would break that leaves the
 * arrangement as it was.
 */
class WidgetAdmissionTest {

    private val declarations = listOf(
        KeyDeclaration("nav.state", ScopeLevel.Composition, KeyKind.Source, KeyOrigin.Owner),
        KeyDeclaration("background.settings", ScopeLevel.App, KeyKind.Source, KeyOrigin.Registered),
        KeyDeclaration("profile.category", ScopeLevel.Page, KeyKind.Source, KeyOrigin.Factory),
        KeyDeclaration("project.page", ScopeLevel.Page, KeyKind.Source, KeyOrigin.Owner),
    ).associateBy { it.id }

    private val needs = mapOf(
        WidgetKind("shell.needy") to listOf("nav.state", "background.settings"),
        WidgetKind("page.made") to listOf("profile.category"),
        WidgetKind("project.only") to listOf("project.page"),
        WidgetKind("lost") to listOf("nobody.declares"),
    )

    private val admission = WidgetAdmission(declarations::get) { needs[it].orEmpty() }

    private val bundled = LayoutGraph.EMPTY

    private fun admits(kind: String, surface: String) = admission.admits(WidgetKind(kind), SurfaceId(surface), bundled)

    @Test
    fun `app and composition keys answer on every surface the editor arranges`() {
        listOf("home.new", "project", "appshell.topbar", "appshell.leftrail").forEach {
            assertTrue(admits("shell.needy", it), it)
        }
    }

    @Test
    fun `a key every page makes answers on a screen's surface and not on the shell's`() {
        assertTrue(admits("page.made", "home.new"))
        assertTrue(admits("page.made", "profile"))
        assertFalse(admits("page.made", "appshell.rightrail"), "the rails are drawn outside any page")
    }

    @Test
    fun `a key one page hands its widgets answers on that page alone`() {
        assertTrue(admits("project.only", "project"))
        assertFalse(admits("project.only", "home.new"))
        assertFalse(admits("project.only", "catalogue.pack"))
        assertFalse(admits("project.only", "appshell.overlay"))
    }

    @Test
    fun `a key nothing declares answers nowhere, and the refusal names it`() {
        assertFalse(admits("lost", "home.new"))
        assertEquals(listOf("nobody.declares"), admission.missing(WidgetKind("lost"), SurfaceId("home.new"), bundled))
        assertTrue(admits("needs.nothing", "appshell.topbar"), "a widget that needs nothing goes anywhere")
    }

    // ── The controller's writes ──────────────────────────────────────

    private lateinit var tmpDir: Path
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("widget-admission-test")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tmpDir.toFile().deleteRecursively()
    }

    private val home = SlotPath(SurfaceId("home.new"), SlotId("main"))
    private val project = SlotPath(SurfaceId("project"), SlotId("body"))

    private fun repo(): LayoutGraphRepository = LayoutGraphRepository(
        tmpDir.resolve("layout-graph.json"),
        Json { ignoreUnknownKeys = true; encodeDefaults = true },
        scope,
    ) { LayoutGraph.EMPTY }

    private fun graph(projectWidgets: List<WidgetInstance> = emptyList()) = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("home.new") to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(flow = FlowSpec.Column))),
            SurfaceId("project") to SurfaceLayout(slots = mapOf(SlotId("body") to SlotContent(widgets = projectWidgets, flow = FlowSpec.Column))),
        ),
    )

    @Test
    fun `a drop the surface cannot take leaves the slot as it was`() = runBlocking {
        val repo = repo()
        val controller = EditModeController(repo, scope, admission)
        repo.update { graph() }

        controller.addWidget(home, WidgetKind("project.only"), emptyList(), index = 0)
        controller.addWidget(home, WidgetKind("page.made"), emptyList(), index = 0)
        controller.awaitWrites()

        assertEquals(
            listOf(WidgetKind("page.made")),
            repo.value().traverse(home)?.widgets?.map { it.kind },
            "the widget whose page is not there was refused, the other placed",
        )
        assertFalse(controller.admits(WidgetKind("project.only"), SurfaceId("home.new")))
        assertTrue(controller.admits(WidgetKind("project.only"), SurfaceId("project")))
    }

    @Test
    fun `a move onto a surface that cannot take the widget leaves it where it was`() = runBlocking {
        val repo = repo()
        val controller = EditModeController(repo, scope, admission)
        val widget = WidgetInstance(kind = WidgetKind("project.only"), instanceId = "p1")
        repo.update { graph(listOf(widget)) }

        controller.moveWidget(project, home, "p1", toIndex = 0)
        controller.awaitWrites()

        assertEquals(listOf("p1"), repo.value().traverse(project)?.widgets?.map { it.instanceId })
        assertEquals(emptyList(), repo.value().traverse(home)?.widgets?.map { it.instanceId })
    }

    /** A container that needs nothing, holding a widget that needs the project page, twice over. */
    private val container = WidgetInstance(
        kind = WidgetKind("box"),
        instanceId = "box1",
        children = mapOf(
            SlotId("inner") to SlotContent(
                widgets = listOf(
                    WidgetInstance(kind = WidgetKind("project.only"), instanceId = "p2"),
                    WidgetInstance(kind = WidgetKind("project.only"), instanceId = "p3"),
                ),
            ),
        ),
    )

    @Test
    fun `a container answers for everything inside it`() {
        assertEquals(listOf("project.page"), admission.missing(container, SurfaceId("home.new"), bundled), "named once")
        assertEquals(emptyList(), admission.missing(container, SurfaceId("project"), bundled))
    }

    @Test
    fun `a container cannot carry a page's widget off that page`() = runBlocking {
        val repo = repo()
        val controller = EditModeController(repo, scope, admission)
        repo.update { graph(listOf(container)) }

        controller.moveWidget(project, home, "box1", toIndex = 0)
        controller.awaitWrites()

        assertEquals(listOf("box1"), repo.value().traverse(project)?.widgets?.map { it.instanceId })
        assertEquals(emptyList(), repo.value().traverse(home)?.widgets?.map { it.instanceId })
    }
}
