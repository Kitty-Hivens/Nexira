package hivens.ui.editor

import hivens.pages.api.MadeScreen
import hivens.pages.api.PagesHost
import hivens.pages.api.ScreenId
import hivens.pages.api.pagesHost
import hivens.pages.api.pagesHostProps
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screens somebody made live in the pages host's record: made with nothing on them, renamed, deleted with their links
 * and put back. A screen a module brought is renamed there too. A reset puts a shipped screen back and empties a made
 * one, and resetting everything keeps the made ones.
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
    private val mainConfig = ConfigId("main")
    private val main = LayoutId("main")
    private val railKind = WidgetKind("appshell.region.left")
    private val centreKind = WidgetKind("test.centre")
    private val top = SlotId("top")
    private val body = SlotId("body")
    private val home = ScreenId("home")

    private val standard = Document(
        start = launcher,
        compositions = mapOf(
            launcher to Composition(
                start = mainConfig,
                singles = mapOf(
                    railKind to WidgetInstance(railKind, "rail", children = mapOf(top to SlotContent(listOf(WidgetInstance(WidgetKind("nav.screens"), "made-links"))))),
                ),
                configs = mapOf(
                    mainConfig to Config(
                        layouts = listOf(
                            ConfigLayout(
                                main,
                                SlotContent(
                                    listOf(
                                        WidgetInstance(railKind, "rail"),
                                        WidgetInstance(
                                            centreKind,
                                            "centre",
                                            children = mapOf(
                                                body to SlotContent(
                                                    listOf(
                                                        WidgetInstance(
                                                            PagesHost.KIND,
                                                            "host",
                                                            children = mapOf(SlotId(home.value) to SlotContent(listOf(WidgetInstance(WidgetKind("screen.home"), "screen.home")))),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
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
        val editor = DocumentEditor(store, scope, mint = { "$it-${++minted}" })
        editor.reportEditing(true)
        return editor to store
    }

    private fun DocumentEditor.settle() = runBlocking { awaitWrites() }

    private fun Document.host() = assertNotNull(compositions.getValue(launcher).pagesHost())
    private fun Document.made() = host().pagesHostProps().made
    private fun screen(id: ScreenId) = NodePath(launcher, NodeRoot.Layout(mainConfig, main)).child("centre", body).child("host", SlotId(id.value))
    private fun Document.page(id: ScreenId) = contentAt(screen(id))
    private fun Document.links() = compositions.getValue(launcher).widgets().mapNotNull { ScreenLinks.target(it) }.toList()

    @Test
    fun `a made screen is kept in the host's record, with nothing on it`() {
        val (editor, store) = editor()
        val id = editor.createScreen(launcher, "Notes", "star")
        editor.settle()
        assertEquals(listOf(MadeScreen(id.value, "Notes", "star")), store.document.value.made())
        assertEquals(SlotContent(), store.document.value.host().children[SlotId(id.value)])
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId }, "home keeps its page")
    }

    @Test
    fun `typing a name is one step, and a deleted screen takes what it held and its links, and comes back with them`() {
        val (editor, store) = editor()
        val id = editor.createScreen(launcher, "", "star")
        editor.settle()
        editor.add(screen(id), WidgetKind("note"), emptyList(), 0)
        editor.edit { d ->
            d.withRailLinks { listOf(WidgetInstance(ScreenLinks.KIND, "link", props = buildJsonObject { put("screen", JsonPrimitive(id.value)) })) }
        }
        "Notes".indices.forEach { editor.updateScreen(launcher, id, title = "Notes".take(it + 1)) }
        editor.settle()
        assertEquals("Notes", store.document.value.made().single().title)
        editor.undo()
        editor.settle()
        assertEquals("", store.document.value.made().single().title, "the whole name in one step")
        editor.redo()
        var gone: DeletedScreen? = null
        editor.deleteScreen(launcher, id) { gone = it }
        editor.settle()
        assertEquals(emptyList(), store.document.value.made())
        assertNull(store.document.value.host().children[SlotId(id.value)], "what it held goes with it")
        assertEquals(emptyList(), store.document.value.links())
        val kept = runBlocking { editor.heldIds() }
        assertTrue(kept.any { it.startsWith("note") }, "a deleted screen's widgets keep their state while it can come back: $kept")
        editor.restoreScreen(launcher, assertNotNull(gone))
        editor.settle()
        assertEquals("Notes", store.document.value.made().single().title)
        assertEquals(listOf(WidgetKind("note")), store.document.value.page(id)?.widgets?.map { it.kind })
    }

    @Test
    fun `a screen a module brought is renamed in the host's record, and only one a person made is deleted`() {
        val (editor, store) = editor()
        editor.updateScreen(launcher, home, title = "Start", icon = "home")
        editor.settle()
        assertEquals(listOf(MadeScreen(home.value, "Start", "home")), store.document.value.host().pagesHostProps().named)
        editor.updateScreen(launcher, home, title = "", icon = "")
        editor.settle()
        assertEquals(emptyList(), store.document.value.host().pagesHostProps().named, "blank both ways is no name of the person's")
        editor.deleteScreen(launcher, home) { error("a shipped screen is not deleted") }
        editor.settle()
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId })
    }

    @Test
    fun `a reset puts a shipped screen back, empties a made one, and resetting all keeps the made ones`() {
        val (editor, store) = editor()
        val id = editor.createScreen(launcher, "Notes", "star")
        editor.settle()
        editor.remove(screen(home), "screen.home")
        editor.updateScreen(launcher, home, title = "Start")
        editor.add(screen(id), WidgetKind("note"), emptyList(), 0)
        editor.settle()

        editor.resetScreen(launcher, home, standard)
        editor.resetScreen(launcher, id, standard)
        editor.settle()
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId })
        assertEquals(emptyList(), store.document.value.host().pagesHostProps().named, "and its name")
        assertEquals(emptyList(), store.document.value.page(id)?.widgets)
        assertEquals("Notes", store.document.value.made().single().title, "a made one keeps its name")

        editor.remove(screen(home), "screen.home")
        editor.add(screen(id), WidgetKind("note"), emptyList(), 0)
        editor.settle()
        editor.resetAll(standard)
        editor.settle()
        assertEquals(listOf("screen.home"), store.document.value.page(home)?.widgets?.map { it.instanceId })
        assertEquals(listOf(id.value), store.document.value.made().map { it.id }, "the made screen stays")
        assertEquals(listOf(WidgetKind("note")), store.document.value.page(id)?.widgets?.map { it.kind }, "with what is on it")
        assertTrue(store.own.value)
    }

    @Test
    fun `back to the standard interface draws it and leaves nothing to undo`() {
        val (editor, store) = editor()
        editor.createScreen(launcher, "Notes", "star")
        editor.settle()
        editor.backToStandard()
        editor.settle()
        assertFalse(store.own.value)
        assertEquals(standard, store.document.value)
        assertFalse(editor.canUndo)
    }

    /** The document with a link added at the end of the rail's top slot. */
    private fun Document.withRailLinks(links: () -> List<WidgetInstance>): Document {
        val composition = compositions.getValue(launcher)
        val rail = composition.singles.getValue(railKind)
        val slot = rail.children.getValue(top)
        val next = rail.copy(children = rail.children + (top to slot.copy(widgets = slot.widgets + links())))
        return copy(compositions = compositions + (launcher to composition.copy(singles = composition.singles + (railKind to next))))
    }
}
