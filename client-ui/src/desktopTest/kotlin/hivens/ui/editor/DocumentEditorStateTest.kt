package hivens.ui.editor

import hivens.test.stopThenDelete
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
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the editor can still bring back keeps its widgets' state, and a slot a screen takes as its own keeps the state
 * of what it held under the new names.
 */
class DocumentEditorStateTest {

    private val dir = Files.createTempDirectory("document-editor-state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    private val launcher = CompositionName("launcher")
    private val home = ConfigId("home")
    private val rail = WidgetKind("rail")
    private val top = SlotId("top")
    private val root = NodePath(launcher, NodeRoot.Layout(home, LayoutId("main")))
    private val railTop = root.child("rail", top)

    private val document = Document(
        start = launcher,
        compositions = mapOf(
            launcher to Composition(
                start = home,
                singles = mapOf(
                    rail to WidgetInstance(
                        rail, "rail",
                        children = mapOf(
                            top to SlotContent(
                                listOf(
                                    WidgetInstance(WidgetKind("notes"), "notes-1"),
                                    WidgetInstance(WidgetKind("box"), "box-1", children = mapOf(SlotId("inner") to SlotContent(listOf(WidgetInstance(WidgetKind("notes"), "notes-2"))))),
                                ),
                            ),
                        ),
                    ),
                ),
                configs = mapOf(
                    home to Config(layouts = listOf(ConfigLayout(LayoutId("main"), SlotContent(listOf(WidgetInstance(rail, "rail"), WidgetInstance(WidgetKind("clock"), "clock-1")))))),
                    ConfigId("made") to Config(title = "Made", layouts = listOf(ConfigLayout(LayoutId("main"), SlotContent(listOf(WidgetInstance(WidgetKind("notes"), "made-notes")))))),
                ),
            ),
        ),
    )

    private val copied: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())
    private var minted = 0

    private fun editor(own: Boolean = true): Pair<DocumentEditor, DocumentStore> {
        val store = DocumentStore(dir.resolve("interface.json"), { document })
        if (own) store.takeOwn()
        val editor = DocumentEditor(store, scope, mint = { "$it-copy${++minted}" }, copyState = { from, to -> copied += from to to })
        editor.reportEditing(true)
        return editor to store
    }

    private fun DocumentEditor.settle() = runBlocking { awaitWrites() }

    @Test
    fun `a removed widget is held while undo can bring it back, and let go with the session`() {
        val (editor, _) = editor()
        editor.remove(root, "clock-1")
        editor.settle()
        assertTrue("clock-1" in runBlocking { editor.heldIds() })
        editor.reportEditing(false)
        editor.settle()
        assertFalse("clock-1" in runBlocking { editor.heldIds() }, "nothing can bring it back once the session is over")
    }

    @Test
    fun `a deleted screen's widgets are held while the screen can be put back`() {
        val (editor, _) = editor()
        editor.deleteScreen(launcher, ConfigId("made"))
        editor.reportEditing(false)
        editor.settle()
        assertTrue("made-notes" in runBlocking { editor.heldIds() })
    }

    @Test
    fun `a slot taken as a screen's own keeps the state of what it held, nested ones included`() {
        val (editor, store) = editor()
        editor.holdOwn(railTop)
        editor.settle()
        val own = assertNotNull(store.document.value.contentAt(railTop)).widgets.map { it.instanceId }
        assertEquals(listOf("notes-1-copy1", "box-1-copy2"), own)
        assertEquals(setOf("notes-1" to "notes-1-copy1", "box-1" to "box-1-copy2", "notes-2" to "notes-2-copy3"), copied.toSet())
    }

    @Test
    fun `a preset takes the place of the whole interface in one step, and the modules stay`() {
        val store = DocumentStore(dir.resolve("interface.json"), { document.copy(modules = listOf("hivens.themes")) })
        store.takeOwn()
        val editor = DocumentEditor(store, scope).also { it.reportEditing(true) }
        val preset = document.copy(compositions = document.compositions.mapValues { (_, c) -> c.copy(configs = c.configs - ConfigId("made")) })
        editor.applyPreset(preset)
        editor.settle()
        assertEquals(preset.copy(modules = listOf("hivens.themes")), store.document.value)
        editor.undo()
        editor.settle()
        assertEquals(document.copy(modules = listOf("hivens.themes")), store.document.value, "one undo takes the whole preset back")
    }

    @Test
    fun `on the standard document the state is copied once the person says yes`() {
        val (editor, _) = editor(own = false)
        editor.holdOwn(railTop)
        editor.settle()
        assertTrue(copied.isEmpty(), "nothing is copied for an edit that has not landed")
        editor.answerConsent(yes = true)
        editor.settle()
        assertEquals(3, copied.size)
    }
}
