package hivens.ui.editor

import hivens.ui.layout.DocumentStore
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.Placement
import hivens.widget.model.SlotContent
import hivens.widget.model.SurfaceInsets
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.WidgetMotion
import hivens.widget.model.contentAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's one way to change the document: the standard one waits for the person's word, an edit is read and
 * written in one step, a gesture is one step of the history, and undo goes only where the store goes.
 */
class DocumentEditorTest {

    private val dir = Files.createTempDirectory("document-editor")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private val launcher = CompositionName("launcher")
    private val home = ConfigId("home")
    private val root = NodePath(launcher, NodeRoot.Layout(home, LayoutId("main")))

    private fun standard(vararg widgets: WidgetInstance) = Document(
        start = launcher,
        compositions = mapOf(
            launcher to Composition(start = home, configs = mapOf(home to Config(layouts = listOf(ConfigLayout(LayoutId("main"), SlotContent(widgets.toList())))))),
        ),
    )

    private val clock = WidgetInstance(WidgetKind("clock"), "clock", placement = Placement(x = 4f, padding = SurfaceInsets(all = 8f)))

    private var minted = 0
    private fun editor(document: Document = standard(clock), own: Boolean = true): Pair<DocumentEditor, DocumentStore> {
        val store = DocumentStore(dir.resolve("interface.json"), { document })
        if (own) store.takeOwn()
        val editor = DocumentEditor(store, scope, mint = { "${it}-${++minted}" })
        editor.reportEditing(true)
        return editor to store
    }

    private fun DocumentEditor.settle() = runBlocking { awaitWrites() }

    private fun DocumentStore.widgets() = assertNotNull(document.value.contentAt(root)).widgets

    // Consent

    @Test
    fun `the first edit of the standard document waits for the person's word, and no keeps it standard`() {
        val (editor, store) = editor(own = false)
        editor.remove(root, "clock")
        editor.remove(root, "nothing")
        editor.settle()
        assertNotNull(editor.consent.value)
        assertEquals(listOf("clock"), store.widgets().map { it.instanceId }, "nothing changed before the word")
        assertFalse(Files.exists(dir.resolve("interface.json")))
        editor.answerConsent(yes = false)
        editor.settle()
        assertNull(editor.consent.value)
        assertFalse(store.own.value)
        assertEquals(listOf("clock"), store.widgets().map { it.instanceId })
    }

    @Test
    fun `yes makes the document the person's own and lands the edit that waited`() {
        val (editor, store) = editor(own = false)
        editor.remove(root, "clock")
        editor.settle()
        editor.answerConsent(yes = true)
        editor.settle()
        assertTrue(store.own.value)
        assertEquals(emptyList(), store.widgets())
        assertTrue(editor.canUndo)
    }

    @Test
    fun `an edit that changes nothing asks nothing`() {
        val (editor, _) = editor(own = false)
        editor.remove(root, "nobody")
        editor.settle()
        assertNull(editor.consent.value)
    }

    // Steps

    @Test
    fun `a gesture is one step until it closes, and the next one is another`() {
        val (editor, store) = editor()
        repeat(20) { editor.offset(root, "clock", it.toFloat(), 0f) }
        editor.closeStep()
        repeat(5) { editor.offset(root, "clock", 100f + it, 0f) }
        editor.settle()
        assertEquals(104f, store.widgets().single().placement?.x)
        editor.undo()
        editor.settle()
        assertEquals(19f, store.widgets().single().placement?.x, "the second drag went back whole")
        editor.undo()
        editor.settle()
        assertEquals(4f, store.widgets().single().placement?.x, "and the first one")
    }

    @Test
    fun `a gesture cut short leaves nothing to undo`() {
        val (editor, store) = editor()
        editor.remove(root, "gone-anyway")
        repeat(5) { editor.offset(root, "clock", 50f + it, 0f) }
        editor.restorePlacement(root, "clock", clock.placement)
        editor.settle()
        assertEquals(clock.placement, store.widgets().single().placement)
        assertFalse(editor.canUndo)
    }

    @Test
    fun `undo goes only where the store goes`() {
        val (editor, store) = editor()
        editor.remove(root, "clock")
        editor.settle()
        store.dropOwn()
        editor.undo()
        editor.settle()
        assertTrue(editor.canUndo, "a step the store did not take stays to be taken")
        assertFalse(editor.canRedo)
    }

    @Test
    fun `undo back to the standard document gives the own one up without a word, and redo takes it back`() {
        val (editor, store) = editor(own = false)
        editor.remove(root, "clock")
        editor.settle()
        editor.answerConsent(yes = true)
        editor.settle()
        assertTrue(Files.exists(dir.resolve("interface.json")))

        editor.undo()
        editor.settle()
        assertFalse(store.own.value, "nothing of the person's is left in it, so updates reach it again")
        assertNull(editor.consent.value, "and nobody is asked anything")
        assertFalse(Files.exists(dir.resolve("interface.json")))

        editor.redo()
        editor.settle()
        assertTrue(store.own.value)
        assertNull(editor.consent.value, "the person said yes to this edit already")
        assertEquals(emptyList(), store.widgets())
    }

    @Test
    fun `undo that leaves the own document unlike the standard one keeps it`() {
        val (editor, store) = editor()
        editor.remove(root, "clock")
        editor.closeStep()
        editor.offset(root, "missing", 1f, 1f)
        editor.add(root, WidgetKind("note"), emptyList(), index = 0)
        editor.settle()
        editor.undo()
        editor.settle()
        assertTrue(store.own.value, "the clock is still gone, so the document is still the person's")
    }

    @Test
    fun `a new edit after undo gave the document up asks again`() {
        val (editor, store) = editor()
        editor.remove(root, "clock")
        editor.settle()
        editor.undo()
        editor.settle()
        assertFalse(store.own.value)
        editor.offset(root, "clock", 9f, 0f)
        editor.settle()
        assertNotNull(editor.consent.value)
    }

    // Writes read what is there when they land

    @Test
    fun `two quick drops land side by side, each placed where the slot had room`() {
        val (editor, store) = editor(standard().let { d -> d.copy(compositions = d.compositions.mapValues { (_, c) -> c.copy(configs = c.configs.mapValues { (_, cfg) -> cfg.copy(layouts = cfg.layouts.map { l -> l.copy(root = SlotContent(flow = null)) }) }) }) })
        editor.add(root, WidgetKind("note"), emptyList(), index = 0)
        editor.add(root, WidgetKind("note"), emptyList(), index = 0)
        editor.settle()
        val placed = store.widgets().map { it.placement }
        assertEquals(2, placed.size)
        assertTrue(placed[0] != placed[1], "the second was seeded with the first already there: $placed")
    }

    @Test
    fun `a plane equal to the declared one is none of the widget's own`() {
        val (editor, store) = editor()
        val declared = SurfaceSpec(fill = "panel")
        editor.surface(root, "clock", "fill", declared) { it.copy(fill = "chrome") }
        editor.settle()
        assertEquals(declared.copy(fill = "chrome"), store.widgets().single().surface)
        editor.surface(root, "clock", "fill", declared) { it.copy(fill = "panel") }
        editor.settle()
        assertNull(store.widgets().single().surface)
    }

    @Test
    fun `two fields of the plane written in one frame both land`() {
        val (editor, store) = editor()
        editor.surface(root, "clock", "fill", null) { it.copy(fill = "chrome") }
        editor.surface(root, "clock", "opacity", null) { it.copy(opacity = 0.5f) }
        editor.settle()
        assertEquals(SurfaceSpec(fill = "chrome", opacity = 0.5f), store.widgets().single().surface)
    }

    @Test
    fun `a reset puts props, plane, arrival and room back in one step`() {
        val (editor, store) = editor()
        editor.props(root, "clock", "title") { JsonObject(mapOf("title" to JsonPrimitive("x"))) }
        editor.surface(root, "clock", "fill", null) { it.copy(fill = "chrome") }
        editor.motion(root, "clock", "delay") { it.copy(delayMs = 100) }
        editor.settle()
        editor.reset(root, "clock")
        editor.settle()
        val w = store.widgets().single()
        assertEquals(JsonObject(emptyMap()), w.props)
        assertNull(w.surface)
        assertNull(w.motion)
        assertEquals(SurfaceInsets(), w.placement?.padding)
        assertEquals(4f, w.placement?.x, "where it stands is not what it says")
        editor.undo()
        editor.settle()
        assertEquals(SurfaceInsets(all = 8f), store.widgets().single().placement?.padding, "one undo brings all of it back")
        assertEquals(WidgetMotion(delayMs = 100), store.widgets().single().motion)
    }
}
