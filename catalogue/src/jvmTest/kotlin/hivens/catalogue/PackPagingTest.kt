package hivens.catalogue

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackOrigins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The rule a pack listing pages by: a page is accepted for what is new in it, since
 * a source that ignores the page number answers with its whole listing every time.
 *
 * Written down as a test because an inline version of it compared by a constant:
 * every entry answered "already seen", so the second page was always empty and the
 * list stopped at the first twenty results with nothing saying so.
 */
class PackPagingTest {

    private fun pack(id: String, origin: String = PackOrigins.MODRINTH) =
        CatalogueCard(origin = origin, id = id, title = id, tagline = "")

    @Test fun `two packs are told apart`() {
        assertNotEquals(pack("a").key, pack("b").key)
    }

    @Test fun `the same id on two sources is two packs`() {
        assertNotEquals(pack("1", PackOrigins.MIRROR).key, pack("1", PackOrigins.MODRINTH).key)
    }

    @Test fun `a page of new entries arrives whole`() {
        val shown = listOf(pack("a"), pack("b"))
        val next = listOf(pack("c"), pack("d"))

        assertEquals(listOf("c", "d"), newIn(next, shown).map { it.id })
    }

    @Test fun `a page that repeats what is shown is dropped to what it adds`() {
        val shown = listOf(pack("a"), pack("b"))
        val next = listOf(pack("b"), pack("c"))

        assertEquals(listOf("c"), newIn(next, shown).map { it.id }, "the overlap is what a source that ignores paging returns")
    }

    @Test fun `a source that answers with the same listing every time reaches the end`() {
        val whole = listOf(pack("a"), pack("b"), pack("c"))

        assertEquals(emptyList(), newIn(whole, whole), "nothing new is how the end is recognised")
    }

    @Test fun `the page keeps its own order`() {
        val shown = listOf(pack("a"))
        val next = listOf(pack("z"), pack("a"), pack("m"))

        assertEquals(listOf("z", "m"), newIn(next, shown).map { it.id })
    }

    @Test fun `nothing shown yet takes the page as it is`() {
        val next = listOf(pack("a"), pack("b"))

        assertEquals(listOf("a", "b"), newIn(next, emptyList()).map { it.id })
    }
}
