package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandardDocumentTest {

    private val document = StandardDocument.load()

    @Test
    fun `it carries no key the model does not read`() {
        val text = StandardDocument::class.java.getResource("/widget/standard-document.json")!!.readText()
        assertEquals(document, Json.decodeFromString(Document.serializer(), text))
    }

    @Test
    fun `it starts the launcher's composition, in a frame widgets draw`() {
        val frame = assertNotNull(document.compositions[document.start]).frame
        assertEquals(CompositionName("launcher"), document.start)
        assertFalse(frame.systemChrome)
        assertTrue(frame.opensMaximized)
        assertTrue(frame.resizable)
        assertTrue(frame.minWidth in 1..frame.width && frame.minHeight in 1..frame.height)
    }

    @Test
    fun `the console is in a frame the system draws`() {
        val frame = assertNotNull(document.compositions[CompositionName("console")]).frame
        assertTrue(frame.systemChrome)
        assertFalse(frame.opensMaximized)
        assertTrue(frame.width > 0 && frame.height > 0)
    }
}
