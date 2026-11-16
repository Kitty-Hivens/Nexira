package hivens.ui

import hivens.i18n.api.I18nKeys
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.BootValues
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The recovery surface speaks whatever every module did before the boot, the launcher's own words being its floor. */
class RecoveryWordsTest {

    @Test
    fun `where nothing was answered before the boot, recovery speaks the launcher's own words`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("de-DE"))
        try {
            val words = recoveryWords(BootValues.NONE).strings(NexiraStrings.NAMESPACE)
            assertNotEquals("recoveryTitle", words.text("recoveryTitle"), "not every key as itself")
            assertEquals("de", words.language, "in the system's language")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `where the language module answered before the boot, recovery speaks that`() {
        val answered = NexiraStrings.translator("ru")
        assertEquals(answered, recoveryWords(BootValues.of { put(I18nKeys.Words, answered) }))
    }
}
