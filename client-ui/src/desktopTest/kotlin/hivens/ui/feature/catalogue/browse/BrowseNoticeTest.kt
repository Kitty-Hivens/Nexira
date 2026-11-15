package hivens.ui.feature.catalogue.browse

import hivens.catalogue.api.SearchStatus
import hivens.ui.i18n.NexiraStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a pack list with nothing to show says: in the name of the source that answered,
 * in every language the launcher ships, and a case of its own where no source is loaded.
 */
class BrowseNoticeTest {

    private val languages = listOf("en", "ru", "de", "ja")

    /** The words the mirror's name took in each language before the texts named the source. */
    private val mirrorWords = listOf("mirror", "Mirror", "зеркал", "Зеркал", "ミラー")

    private fun notice(status: SearchStatus, language: String) =
        browseNotice(status, "Shelf", NexiraStrings.shipped(language))

    @Test
    fun `an empty and an unreachable source are named, in every language`() {
        languages.forEach { language ->
            listOf(SearchStatus.Empty, SearchStatus.Failed()).forEach { status ->
                val said = checkNotNull(notice(status, language))
                val text = said.title + " " + said.message
                assertTrue("Shelf" in text, "$language $status names the source: $text")
                assertFalse(mirrorWords.any { it in text }, "$language $status names no other source: $text")
            }
        }
    }

    @Test
    fun `a source's own words for its failure are kept`() {
        assertEquals("gone fishing", notice(SearchStatus.Failed("gone fishing"), "en")?.message)
    }

    @Test
    fun `no source loaded is its own case, and says nothing of a source`() {
        languages.forEach { language ->
            val said = checkNotNull(notice(SearchStatus.NoSource, language))
            assertFalse("Shelf" in said.title + said.message, "nobody was asked, so nobody is named")
            assertNotEquals(notice(SearchStatus.Empty, language)?.title, said.title, "$language tells it from an empty list")
            assertNotEquals(said.title, "browseNoSourceTitle", "$language has the words")
        }
    }

    @Test
    fun `a list that shows something says nothing`() {
        assertNull(notice(SearchStatus.Loaded, "en"))
        assertNull(notice(SearchStatus.Loading, "en"))
    }
}
