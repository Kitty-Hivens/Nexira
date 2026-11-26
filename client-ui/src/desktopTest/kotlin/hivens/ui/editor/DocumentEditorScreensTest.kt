package hivens.ui.editor

import hivens.ui.layout.DocumentStore
import hivens.ui.screens.custom.ScreenLinks
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import hivens.widget.model.widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screens somebody made are configs of the document: made like the start one with empty pages and a link on the rail,
 * renamed, deleted with their links and put back. A reset puts the shipped configs back and keeps the made ones.
 */
class DocumentEditorScreensTest {

    private val dir = Files.createTempDirectory("document-editor-screens")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private val launcher = CompositionName("launcher")
    private val home = ConfigId("home")
    private val main = LayoutId("main")
    private val railKind = WidgetKind("appshell.region.left")
    private val centreKind = WidgetKind("test.centre")
    private val top = SlotId("top")
    private val body = SlotId("body")
    private val pagesOf = { kind: WidgetKind -> if (kind == centreKind) listOf(body) else emptyList() }

    private val standard = Document(
        start = launcher,
        compositions = mapOf(
            launcher to Composition(
                start = home,
                singles = mapOf(
                    railKind to WidgetInstance(railKind, "rail", children = mapOf(top to SlotContent(listOf(WidgetInstance(WidgetKind("nav.entry"), "home-link"))))),
                    centreKind to WidgetInstance(centreKind, "centre", children = mapOf(body to SlotContent())),
                ),
                configs = mapOf(
                    home to Config(
                        title = "Home",
                        layouts = listOf(
                            ConfigLayout(
                                main,
                                SlotContent(
                                    listOf(
                                        WidgetInstance(railKind, "rail"),
                                        WidgetInstance(centreKind, "centre", children = mapOf(body to SlotContent(listOf(WidgetInstance(WidgetKind("screen.home"), "screen.home"))))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private var minted = 0
    private fun editor(): Pair<DocumentEditor, DocumentStore> {
        val store = DocumentStore(dir.resolve("interface.json"), { standard })
        store.takeOwn()
        val editor = DocumentEditor(store, scope) { "$it-${++minted}" }
        editor.reportEditing(true)
        return editor to store
    }

    private fun DocumentEditor.settle() = runBlocking { awaitWrites() }

    private fun Document.launcher() = compositions.getValue(launcher)
    private fun Document.page(config: ConfigId) = contentAt(NodePath(launcher, NodeRoot.Layout(config, main)).child("centre", body))
    private fun Document.links() = launcher().widgets().mapNotNull { ScreenLinks.target(it) }.toList()

    @Test
    fun `a made screen is laid out like the start one, with nothing on its page, and linked from the rail`() {
        val (editor, store) = editor()
        val id = editor.createScreen(launcher, "Notes", "star", pagesOf)
        editor.settle()
        val made = assertNotNull(store.document.value.launcher().configs[id])
        assertEquals("Notes", made.title)
        assertEquals(emptyList(), assertNotNull(store.document.value.page(id)).widgets)
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId }, "home keeps its page")
        assertEquals(listOf(id.value), store.document.value.links())
    }

    @Test
    fun `typing a name is one step, and a deleted screen takes its links and comes back with one`() {
        val (editor, store) = editor()
        val id = editor.createScreen(launcher, "", "star", pagesOf)
        editor.settle()
        "Notes".indices.forEach { editor.updateScreen(launcher, id, title = "Notes".take(it + 1)) }
        editor.settle()
        assertEquals("Notes", store.document.value.launcher().configs.getValue(id).title)
        editor.undo()
        editor.settle()
        assertEquals("", store.document.value.launcher().configs.getValue(id).title, "the whole name in one step")
        editor.redo()
        var gone: Config? = null
        editor.deleteScreen(launcher, id) { gone = it }
        editor.settle()
        assertNull(store.document.value.launcher().configs[id])
        assertEquals(emptyList(), store.document.value.links())
        editor.restoreScreen(launcher, id, assertNotNull(gone))
        editor.settle()
        assertEquals("Notes", store.document.value.launcher().configs[id]?.title)
        assertEquals(listOf(id.value), store.document.value.links())
    }

    @Test
    fun `a reset puts a shipped screen back, empties a made one, and resetting all keeps the made ones`() {
        val (editor, store) = editor()
        val id = editor.createScreen(launcher, "Notes", "star", pagesOf)
        editor.settle()
        val homePage = NodePath(launcher, NodeRoot.Layout(home, main)).child("centre", body)
        val madePage = NodePath(launcher, NodeRoot.Layout(id, main)).child("centre", body)
        editor.remove(homePage, "screen.home")
        editor.add(madePage, WidgetKind("note"), emptyList(), 0)
        editor.settle()

        editor.resetScreen(launcher, home, standard, pagesOf)
        editor.resetScreen(launcher, id, standard, pagesOf)
        editor.settle()
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId })
        assertEquals(emptyList(), store.document.value.page(id)?.widgets)

        editor.remove(homePage, "screen.home")
        editor.settle()
        editor.resetAll(standard)
        editor.settle()
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId })
        assertNotNull(store.document.value.launcher().configs[id], "the made screen stays")
        assertEquals(listOf(id.value), store.document.value.links(), "and so does the way to it")
        assertTrue(store.own.value)
    }

    @Test
    fun `back to the standard interface draws it and leaves nothing to undo`() {
        val (editor, store) = editor()
        editor.createScreen(launcher, "Notes", "star", pagesOf)
        editor.settle()
        editor.backToStandard()
        editor.settle()
        assertFalse(store.own.value)
        assertEquals(standard, store.document.value)
        assertFalse(editor.canUndo)
    }
}
