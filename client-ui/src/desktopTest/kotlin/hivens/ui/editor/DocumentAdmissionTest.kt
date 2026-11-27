package hivens.ui.editor

import androidx.compose.runtime.Composable
import hivens.test.stopThenDelete
import hivens.ui.layout.DocumentStore
import hivens.widget.api.KeyDeclaration
import hivens.widget.api.KeyKind
import hivens.widget.api.KeyOrigin
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the editor lets a widget stand in the document: every key it needs has to answer in the slot it is put in, and
 * a drop or a move that would break that leaves the document as it was.
 */
class DocumentAdmissionTest {

    private val dir = Files.createTempDirectory("document-admission")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    private class Kind(
        id: String,
        override val slots: List<SlotId> = emptyList(),
        override val hands: List<String> = emptyList(),
        override val needs: List<String> = emptyList(),
    ) : WidgetDescriptor {
        override val kind = WidgetKind(id)
        override val displayName = id
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) = Unit
    }

    private val body = SlotId("body")
    private val bottom = SlotId("bottom")
    private val main = SlotId("main")

    private val kinds = listOf(
        Kind("frame.centre", slots = listOf(body, bottom)),
        Kind("pages.host"),
        Kind("screen.home", slots = listOf(main)),
        Kind("screen.project", slots = listOf(body), hands = listOf("project.page")),
        Kind("box", slots = listOf(SlotId("inner"))),
        Kind("shell.needy", needs = listOf("nav.state", "background.settings")),
        Kind("page.made", needs = listOf("profile.category")),
        Kind("page.entry", needs = listOf("page.entry")),
        Kind("project.only", needs = listOf("project.page")),
        Kind("lost", needs = listOf("nobody.declares")),
        Kind("needs.nothing"),
    ).associateBy { it.kind }

    private val declarations = listOf(
        KeyDeclaration("nav.state", ScopeLevel.Composition, KeyKind.Source, KeyOrigin.Owner),
        KeyDeclaration("background.settings", ScopeLevel.App, KeyKind.Source, KeyOrigin.Registered),
        KeyDeclaration("profile.category", ScopeLevel.Page, KeyKind.Source, KeyOrigin.Factory),
        KeyDeclaration("project.page", ScopeLevel.Page, KeyKind.Source, KeyOrigin.Owner),
        KeyDeclaration("page.entry", ScopeLevel.Page, KeyKind.Source, KeyOrigin.Owner),
    ).associateBy { it.id }

    private val admission = DocumentAdmission(declarations::get, kinds::get, everyPage = setOf("page.entry"))

    private val launcher = CompositionName("launcher")
    private val root = NodePath(launcher, NodeRoot.Layout(ConfigId("home"), LayoutId("main")))
    private val frame = root.child("centre", bottom)
    private val aroundHost = root.child("centre", body)
    private val pageSlot = aroundHost.child("host", SlotId("home"))
    private val homePage = pageSlot.child("screen.home", main)
    private val projectPage = aroundHost.child("host", SlotId("project")).child("screen.project", body)

    private fun widget(kind: String, id: String, children: Map<SlotId, SlotContent> = emptyMap()) =
        WidgetInstance(WidgetKind(kind), id, children = children)

    /** A centre holding the pages host, which holds both screens, so one document has a page of each. */
    private fun document(projectHolds: List<WidgetInstance> = emptyList()): Document {
        val host = widget(
            "pages.host", "host",
            mapOf(
                SlotId("home") to SlotContent(listOf(widget("screen.home", "screen.home", mapOf(main to SlotContent())))),
                SlotId("project") to SlotContent(listOf(widget("screen.project", "screen.project", mapOf(body to SlotContent(projectHolds))))),
            ),
        )
        val centre = widget("frame.centre", "centre", mapOf(body to SlotContent(listOf(host)), bottom to SlotContent()))
        val home = Config(layouts = listOf(ConfigLayout(LayoutId("main"), SlotContent(listOf(centre)))))
        return Document(start = launcher, compositions = mapOf(launcher to Composition(start = ConfigId("home"), configs = mapOf(ConfigId("home") to home))))
    }

    private fun admits(kind: String, path: NodePath) = admission.admits(WidgetKind(kind), path, document())

    @Test
    fun `app and composition keys answer in every slot`() {
        listOf(frame, aroundHost, pageSlot, homePage, projectPage).forEach { assertTrue(admits("shell.needy", it), it.toString()) }
    }

    @Test
    fun `a key every page makes answers on a page and not in the frame`() {
        assertTrue(admits("page.made", homePage))
        assertTrue(admits("page.made", pageSlot), "the host's slot for a screen is on its page")
        assertFalse(admits("page.made", frame), "the frame is drawn outside any page")
        assertFalse(admits("page.made", aroundHost), "the slot the host stands in is the frame's")
        assertTrue(admits("page.entry", homePage), "what the history hands every page")
        assertFalse(admits("page.entry", frame))
    }

    @Test
    fun `a key one screen hands answers under that screen alone`() {
        assertTrue(admits("project.only", projectPage))
        assertFalse(admits("project.only", homePage))
        assertFalse(admits("project.only", pageSlot), "beside the screen is not under it")
        assertFalse(admits("project.only", frame))
    }

    @Test
    fun `a key nothing declares answers nowhere, and the refusal names it`() {
        assertFalse(admits("lost", homePage))
        assertEquals(listOf("nobody.declares"), admission.missing(WidgetKind("lost"), homePage, document()))
        assertTrue(admits("needs.nothing", frame), "a widget that needs nothing goes anywhere")
    }

    @Test
    fun `a slot whose container is gone is on no page`() {
        assertNull(admission.pageAt(root.child("nobody", body).child("x", main), document()))
    }

    private val container = widget(
        "box", "box1",
        mapOf(SlotId("inner") to SlotContent(listOf(widget("project.only", "p2"), widget("project.only", "p3")))),
    )

    @Test
    fun `a container answers for everything inside it`() {
        assertEquals(listOf("project.page"), admission.missing(container, homePage, document()), "named once")
        assertEquals(emptyList(), admission.missing(container, projectPage, document()))
    }

    // ── The editor's writes ──────────────────────────────────────────

    private fun editor(document: Document): Pair<DocumentEditor, DocumentStore> {
        val store = DocumentStore(dir.resolve("interface.json"), { document })
        store.takeOwn()
        return DocumentEditor(store, scope, admission).also { it.reportEditing(true) } to store
    }

    @Test
    fun `a drop the slot cannot take leaves the document as it was`() = runBlocking {
        val (editor, store) = editor(document())
        editor.add(homePage, WidgetKind("project.only"), emptyList(), 0)
        editor.add(homePage, WidgetKind("page.made"), emptyList(), 0)
        editor.awaitWrites()
        assertEquals(listOf(WidgetKind("page.made")), store.document.value.contentAt(homePage)?.widgets?.map { it.kind })
        assertEquals(listOf("project.page"), editor.missingAt(homePage, WidgetKind("project.only")))
        assertEquals(emptyList(), editor.missingAt(projectPage, WidgetKind("project.only")))
    }

    @Test
    fun `a move a slot cannot take leaves the widget where it was, container and all`() = runBlocking {
        val (editor, store) = editor(document(projectHolds = listOf(widget("project.only", "p1"), container)))
        editor.move(projectPage, homePage, "p1", 0)
        editor.move(projectPage, homePage, "box1", 0)
        editor.move(projectPage, frame, "p1", 0)
        editor.awaitWrites()
        assertEquals(listOf("p1", "box1"), store.document.value.contentAt(projectPage)?.widgets?.map { it.instanceId })
        assertEquals(emptyList(), store.document.value.contentAt(homePage)?.widgets)
        assertFalse(editor.canUndo, "a refused move is no step")
    }

    @Test
    fun `a refused drop on the standard document asks nothing`() = runBlocking {
        val store = DocumentStore(dir.resolve("standard.json"), { document() })
        val editor = DocumentEditor(store, scope, admission).also { it.reportEditing(true) }
        editor.add(frame, WidgetKind("project.only"), emptyList(), 0)
        editor.awaitWrites()
        assertNull(editor.consent.value, "nothing would change, so there is nothing to make the person's own")
    }
}
