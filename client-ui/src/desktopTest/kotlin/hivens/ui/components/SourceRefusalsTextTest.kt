package hivens.ui.components

import hivens.core.data.PackOrigin
import hivens.launcher.PackOperationPhase
import hivens.launcher.sources.SourceCannotException
import hivens.launcher.sources.SourceMissingException
import hivens.launcher.sources.SourceOperation
import hivens.ui.i18n.NexiraStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A refusal of the pack's source reads in the person's language, in every language that
 * ships, naming the source and what it could not do. The engine's own message, English and
 * meant for the log, is never what the pack screens show.
 */
class SourceRefusalsTextTest {
    private val refusals = listOf(PackOperationPhase.SourceRefused("Shelf")) +
        SourceOperation.entries.map { PackOperationPhase.SourceRefused("Shelf", it) }

    @Test
    fun `every refusal reads in every shipped language and names the source`() {
        for (language in listOf("en", "ru", "de", "ja")) {
            val s = NexiraStrings.engine(null, language).strings(NexiraStrings.NAMESPACE)
            val texts = refusals.map { it.text(s) }
            texts.forEach { text ->
                assertTrue("Shelf" in text, "$language: $text")
                assertFalse(text.startsWith("packSourceRefused"), "$language has the words, not the key: $text")
                assertFalse(text == SourceMissingException(PackOrigin("Shelf")).message, "$language: the engine's message")
                SourceOperation.entries.forEach { op ->
                    assertFalse(text == SourceCannotException(PackOrigin("Shelf"), op).message, "$language: the engine's message")
                }
            }
            assertEquals(texts.size, texts.toSet().size, "$language says each refusal its own way")
        }
    }

    @Test
    fun `in Russian a missing source reads as not loaded`() {
        val s = NexiraStrings.engine(null, "ru").strings(NexiraStrings.NAMESPACE)
        assertEquals("Ничего не сделано: источник Shelf не загружен.", PackOperationPhase.SourceRefused("Shelf").text(s))
    }
}
