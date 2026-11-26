package hivens.ui.layout

import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyReason
import hivens.core.data.ReadOnlyStore
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.Composition
import hivens.widget.model.Document
import hivens.widget.model.DocumentCodec
import hivens.widget.model.DocumentRead
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The document drawn: the person's own where they keep one, the standard one otherwise, and a file left as it is where this build cannot take it. */
class DocumentStoreTest {

    private val dir = Files.createTempDirectory("document-store")
    private val file = dir.resolve("document.json")

    private val launcher = CompositionName("launcher")
    private val standard = Document(
        modules = listOf("nexira.packs"),
        start = launcher,
        compositions = mapOf(launcher to Composition(start = ConfigId("home"))),
    )

    @BeforeTest
    fun clean() = NewerBuildData.reset()

    @AfterTest
    fun tearDown() {
        NewerBuildData.reset()
        dir.toFile().deleteRecursively()
    }

    private fun store() = DocumentStore(file, { standard })

    @Test
    fun `with no document of one's own the standard one is drawn and nothing is written`() {
        val store = store()
        assertEquals(standard, store.document.value)
        assertFalse(store.own.value)
        assertFalse(store.update { it.copy(modules = emptyList()) }, "the standard document is not edited in place")
        assertFalse(Files.exists(file))
    }

    @Test
    fun `taking it as one's own writes the standard one, modules and all, and edits go there`() {
        val store = store()
        assertTrue(store.takeOwn())
        assertTrue(store.own.value)
        assertEquals(DocumentRead.Whole(standard), DocumentCodec.read(file.readText()))

        val moved = standard.copy(start = CompositionName("elsewhere"))
        assertTrue(store.update { moved })
        assertEquals(moved, store.document.value)
        assertEquals(DocumentRead.Whole(moved), DocumentCodec.read(file.readText()))
        assertEquals(moved, store().document.value, "the next start reads it back")
        assertTrue(store().own.value)
    }

    @Test
    fun `a document of one's own is not taken again over itself`() {
        val mine = standard.copy(modules = emptyList())
        file.writeText(DocumentCodec.write(mine))
        val writes = ArrayList<String>()
        val store = DocumentStore(file, { standard }) { what, _ -> writes += what }
        assertTrue(store.takeOwn())
        assertEquals(mine, store.document.value, "the person's modules stay theirs")
        assertEquals(emptyList(), writes, "and nothing is written over the file")
    }

    @Test
    fun `a newer build's document is left as it is, and the standard one is drawn meanwhile`() {
        val newer = """{"schema": 99, "start": "launcher"}"""
        file.writeText(newer)
        val store = store()
        assertEquals(standard, store.document.value)
        assertFalse(store.writable)
        assertFalse(store.takeOwn())
        assertFalse(store.update { it.copy(modules = emptyList()) })
        assertEquals(newer, file.readText())
        assertEquals(mapOf(ReadOnlyStore.Layout to ReadOnlyReason.NewerBuild), NewerBuildData.affectedWithReason())
    }

    @Test
    fun `a file that is no document is left as it is too, and said to be damaged`() {
        file.writeText("{not a document")
        val store = store()
        assertEquals(standard, store.document.value)
        assertFalse(store.takeOwn())
        assertEquals("{not a document", file.readText())
        assertEquals(mapOf(ReadOnlyStore.Layout to ReadOnlyReason.Damaged), NewerBuildData.affectedWithReason())
    }

    @Test
    fun `a write goes where the launcher runs its writes`() {
        val asked = ArrayList<String>()
        val held = ArrayList<() -> Unit>()
        val store = DocumentStore(file, { standard }) { what, block -> asked += what; held += block }
        store.takeOwn()
        assertFalse(Files.exists(file), "nothing written on the caller's thread")
        held.forEach { it() }
        assertIs<DocumentRead.Whole>(DocumentCodec.read(file.readText()))
        assertEquals(listOf("the interface"), asked)
    }

    @Test
    fun `edits made while a write waits ride on it, and it writes the last of them`() {
        val held = ArrayList<() -> Unit>()
        val store = DocumentStore(file, { standard }) { _, block -> held += block }
        store.takeOwn()
        repeat(30) { i -> store.update { it.copy(modules = listOf("frame-$i")) } }
        assertEquals(1, held.size, "one write for the whole drag")
        held.forEach { it() }
        assertEquals(DocumentRead.Whole(standard.copy(modules = listOf("frame-29"))), DocumentCodec.read(file.readText()))
        store.update { it.copy(modules = listOf("after")) }
        assertEquals(2, held.size, "an edit after the write began is written again")
    }

    @Test
    fun `going back to the standard document draws it, removes the file and takes no edits`() {
        val store = store()
        store.takeOwn()
        store.update { it.copy(modules = emptyList()) }
        assertFalse(store.matchesStandard)
        assertTrue(store.dropOwn())
        assertEquals(standard, store.document.value)
        assertFalse(store.own.value)
        assertTrue(store.matchesStandard)
        assertFalse(Files.exists(file))
        assertFalse(store.update { it.copy(modules = emptyList()) })
        assertEquals(standard, store().document.value, "the next start draws the standard one")
    }

    @Test
    fun `whatever order the writes run in, the file ends as the person last meant`() {
        val held = ArrayList<() -> Unit>()
        val store = DocumentStore(file, { standard }) { _, block -> held += block }
        store.takeOwn()
        store.update { it.copy(modules = emptyList()) }
        store.dropOwn()
        store.takeOwn()
        held.toList().forEach { it() }
        assertEquals(DocumentRead.Whole(standard), DocumentCodec.read(file.readText()), "own again, as the standard one was")
        store.dropOwn()
        held.toList().forEach { it() }
        assertFalse(Files.exists(file))
    }

    @Test
    fun `a file this build does not write is not given up either`() {
        file.writeText("{not a document")
        assertFalse(store().dropOwn())
        assertEquals("{not a document", file.readText())
    }
}
