package hivens.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.LocalWidgetDecorator
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetDecorator
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.FlowSpec
import hivens.widget.model.LayoutGraph
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.toNodePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A slot addressed into a document is drawn from the document, and one addressed under a surface from the layout
 * graph, whichever of the two is provided around it. Each widget is handed the whole path it stands at, so two
 * containers' slots of one name are two places.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DocumentSlotTest {

    @Test
    fun `a config's layout is drawn from the document`() {
        val drawn = draw(document(root = slot(leaf("a"), leaf("b"))), HOME)
        assertEquals(listOf(Seen(HOME, "a"), Seen(HOME, "b")), drawn)
    }

    @Test
    fun `a container's slot is read from the document, at its own path`() {
        val content = slot(container("left", slot(leaf("a"))), container("right", slot(leaf("b"))))
        val drawn = draw(document(root = content), HOME)
        assertEquals(
            setOf(
                Seen(HOME, "left"),
                Seen(HOME.child("left", BODY), "a"),
                Seen(HOME, "right"),
                Seen(HOME.child("right", BODY), "b"),
            ),
            drawn.toSet(),
        )
        assertEquals(4, drawn.size, "each once: $drawn")
    }

    @Test
    fun `a container inside a container descends from where it stands`() {
        val content = slot(container("outer", slot(container("inner", slot(leaf("a"))))))
        val inner = HOME.child("outer", BODY)
        assertEquals(
            setOf(Seen(HOME, "outer"), Seen(inner, "inner"), Seen(inner.child("inner", BODY), "a")),
            draw(document(root = content), HOME).toSet(),
        )
    }

    @Test
    fun `a single is drawn as its record wherever it stands`() {
        val record = container("rail", slot(leaf("kept")))
        val document = document(
            root = slot(WidgetInstance(kind = CONTAINER, instanceId = "rail")),
            singles = mapOf(CONTAINER to record),
        )
        assertEquals(setOf(Seen(HOME, "rail"), Seen(HOME.child("rail", BODY), "kept")), draw(document, HOME).toSet())
    }

    @Test
    fun `the residents are a root of their own`() {
        val residents = NodePath(LAUNCHER, NodeRoot.Residents)
        assertEquals(listOf(Seen(residents, "r")), draw(document(root = slot(leaf("a")), residents = slot(leaf("r"))), residents))
    }

    @Test
    fun `a document replaced is drawn as it now is`() {
        val state = mutableStateOf<Document?>(document(root = slot(leaf("a"))))
        val seen = ArrayList<Seen>()
        val scene = scene(state, LayoutGraph.EMPTY, HOME, seen)
        try {
            scene.render().close()
            seen.clear()
            state.value = document(root = slot(leaf("b"), leaf("c")))
            scene.render().close()
            assertEquals(listOf(Seen(HOME, "b"), Seen(HOME, "c")), seen)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a slot under a surface reads the graph and a document's slot does not`() {
        val surface = SlotPath(SurfaceId("s"), SlotId("main")).toNodePath()
        val graph = LayoutGraph(surfaces = mapOf(SurfaceId("s") to SurfaceLayout(slots = mapOf(SlotId("main") to slot(leaf("graph"))))))
        val document = document(root = slot(leaf("document")))
        assertEquals(listOf(Seen(surface, "graph")), draw(document, surface, graph))
        assertEquals(listOf(Seen(HOME, "document")), draw(document, HOME, graph))
    }

    @Test
    fun `nothing is drawn where the path leads nowhere`() {
        val gone = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId("gone"), MAIN))
        assertEquals(emptyList(), draw(document(root = slot(leaf("a"))), gone))
        assertEquals(emptyList(), draw(null, HOME))
    }

    // ── Harness ───────────────────────────────────────────────────────

    private data class Seen(val path: NodePath, val instanceId: String)

    private fun leaf(id: String) = WidgetInstance(kind = LEAF, instanceId = id)

    private fun container(id: String, body: SlotContent) =
        WidgetInstance(kind = CONTAINER, instanceId = id, props = JsonObject(mapOf("n" to JsonPrimitive(id))), children = mapOf(BODY to body))

    private fun slot(vararg widgets: WidgetInstance) = SlotContent(widgets = widgets.toList(), flow = FlowSpec.Column)

    private fun document(
        root: SlotContent,
        singles: Map<WidgetKind, WidgetInstance> = emptyMap(),
        residents: SlotContent = SlotContent(flow = null),
    ) = Document(
        start = LAUNCHER,
        compositions = mapOf(
            LAUNCHER to Composition(
                start = ConfigId("home"),
                configs = mapOf(ConfigId("home") to Config(layouts = listOf(ConfigLayout(MAIN, root)))),
                singles = singles,
                residents = residents,
            ),
        ),
    )

    private fun draw(document: Document?, path: NodePath, graph: LayoutGraph = LayoutGraph.EMPTY): List<Seen> {
        val seen = ArrayList<Seen>()
        val scene = scene(mutableStateOf(document), graph, path, seen)
        try {
            scene.render().close()
        } finally {
            scene.close()
        }
        return seen
    }

    private fun scene(document: MutableState<Document?>, graph: LayoutGraph, path: NodePath, seen: MutableList<Seen>): ImageComposeScene {
        val decorator: WidgetDecorator = { at, _, _, instance, content ->
            seen += Seen(at, instance.instanceId)
            content()
        }
        return ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            CompositionLocalProvider(
                LocalDocument provides document.value,
                LocalLayoutGraph provides graph,
                LocalWidgetRegistry provides registry,
                LocalWidgetDecorator provides decorator,
            ) {
                SlotRenderer(path)
            }
        }
    }

    private object Leaf : WidgetDescriptor {
        override val kind = LEAF
        override val displayName = "leaf"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = Box(Modifier.size(4.dp))
    }

    private object Container : WidgetDescriptor {
        override val kind = CONTAINER
        override val displayName = "container"
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = SlotRenderer(instance, BODY)
    }

    private val registry = object : WidgetRegistry {
        private val kinds = listOf(Leaf, Container).associateBy { it.kind }

        override fun all(): Map<WidgetKind, WidgetDescriptor> = kinds

        override fun get(kind: WidgetKind): WidgetDescriptor? = kinds[kind]
    }

    private companion object {
        val LAUNCHER = CompositionName("launcher")
        val MAIN = LayoutId("main")
        val HOME = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId("home"), MAIN))
        val BODY = SlotId("body")
        val LEAF = WidgetKind("leaf")
        val CONTAINER = WidgetKind("container")
    }
}
