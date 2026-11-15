package hivens.catalogue

import hivens.catalogue.api.CatalogueCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PackSnapshotsTest {

    private fun pack(id: String) = CatalogueCard(origin = "Searched", id = id, title = id, tagline = "")

    private fun snapshot(vararg ids: String, nextPage: Int = 0, endReached: Boolean = false) =
        PackSnapshots.Snapshot(ids.map(::pack), nextPage, endReached)

    @Test fun `a source is handed back what it was last showing`() {
        val session = PackSnapshots()
        session.put("Searched", "create", snapshot("a", "b", nextPage = 2))

        val back = session.get("Searched", "create")

        assertNotNull(back)
        assertEquals(listOf("a", "b"), back.packs.map { it.id })
        assertEquals(2, back.nextPage, "coming back to twenty results after scrolling to eighty is its own loss")
    }

    @Test fun `two sources do not share a list`() {
        val session = PackSnapshots()
        session.put("Searched", "", snapshot("create"))

        assertNull(session.get("Shelf", ""), "one source must not be handed another's list")
    }

    @Test fun `the difference between two spellings of a query is a keystroke`() {
        val session = PackSnapshots()
        session.put("Searched", "Create", snapshot("a"))

        assertNotNull(session.get("Searched", "  create "))
    }

    @Test fun `typing towards a query does not grow the map without end`() {
        val session = PackSnapshots()
        repeat(40) { session.put("Searched", "q$it", snapshot("a")) }

        assertNull(session.get("Searched", "q0"), "the oldest goes out")
        assertNotNull(session.get("Searched", "q39"), "the newest stays")
    }

    /**
     * The loop this closes: an empty snapshot was stored like any other, restored as
     * a loaded list, and painted as a blank pane with neither the empty message nor
     * its retry. Every later failure then read as one already answered, paging was
     * off because the snapshot claimed the end, and leaving wrote the same emptiness
     * back. One source briefly unreachable left Browse blank until a restart.
     */
    @Test fun `nothing is not remembered`() {
        val session = PackSnapshots()
        session.put("Searched", "create", snapshot(endReached = true))

        assertNull(session.get("Searched", "create"))
    }

    @Test fun `an empty answer forgets the list it replaces`() {
        // A source that has genuinely stopped listing something must not leave the
        // last good list to be restored over the empty state that says so.
        val session = PackSnapshots()
        session.put("Searched", "create", snapshot("a", "b"))
        session.put("Searched", "create", snapshot())

        assertNull(session.get("Searched", "create"))
    }

    @Test fun `an empty answer for one query leaves another alone`() {
        val session = PackSnapshots()
        session.put("Searched", "create", snapshot("a"))
        session.put("Searched", "tech", snapshot())

        assertNotNull(session.get("Searched", "create"))
    }

    @Test fun `looking at a list again keeps it from being the oldest`() {
        val session = PackSnapshots()
        session.put("Searched", "keep", snapshot("a"))
        repeat(20) { session.put("Searched", "q$it", snapshot("a")) }
        session.put("Searched", "keep", snapshot("a", "b"))
        repeat(20) { session.put("Searched", "r$it", snapshot("a")) }

        assertNotNull(session.get("Searched", "keep"))
    }
}
