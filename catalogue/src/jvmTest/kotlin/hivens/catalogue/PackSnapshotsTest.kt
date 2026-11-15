package hivens.catalogue

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackOrigins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PackSnapshotsTest {

    private fun pack(id: String) = CatalogueCard(origin = PackOrigins.MODRINTH, id = id, title = id, tagline = "")

    private fun snapshot(vararg ids: String, nextPage: Int = 0, endReached: Boolean = false) =
        PackSnapshots.Snapshot(ids.map(::pack), nextPage, endReached)

    @Test fun `a source is handed back what it was last showing`() {
        val session = PackSnapshots()
        session.put(PackOrigins.MODRINTH, "create", snapshot("a", "b", nextPage = 2))

        val back = session.get(PackOrigins.MODRINTH, "create")

        assertNotNull(back)
        assertEquals(listOf("a", "b"), back.packs.map { it.id })
        assertEquals(2, back.nextPage, "coming back to twenty results after scrolling to eighty is its own loss")
    }

    @Test fun `two sources do not share a list`() {
        val session = PackSnapshots()
        session.put(PackOrigins.MODRINTH, "", snapshot("create"))

        assertNull(session.get(PackOrigins.MIRROR, ""), "the mirror must not be handed Modrinth's catalogue")
    }

    @Test fun `the difference between two spellings of a query is a keystroke`() {
        val session = PackSnapshots()
        session.put(PackOrigins.MODRINTH, "Create", snapshot("a"))

        assertNotNull(session.get(PackOrigins.MODRINTH, "  create "))
    }

    @Test fun `typing towards a query does not grow the map without end`() {
        val session = PackSnapshots()
        repeat(40) { session.put(PackOrigins.MODRINTH, "q$it", snapshot("a")) }

        assertNull(session.get(PackOrigins.MODRINTH, "q0"), "the oldest goes out")
        assertNotNull(session.get(PackOrigins.MODRINTH, "q39"), "the newest stays")
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
        session.put(PackOrigins.MODRINTH, "create", snapshot(endReached = true))

        assertNull(session.get(PackOrigins.MODRINTH, "create"))
    }

    @Test fun `an empty answer forgets the list it replaces`() {
        // A source that has genuinely stopped listing something must not leave the
        // last good list to be restored over the empty state that says so.
        val session = PackSnapshots()
        session.put(PackOrigins.MODRINTH, "create", snapshot("a", "b"))
        session.put(PackOrigins.MODRINTH, "create", snapshot())

        assertNull(session.get(PackOrigins.MODRINTH, "create"))
    }

    @Test fun `an empty answer for one query leaves another alone`() {
        val session = PackSnapshots()
        session.put(PackOrigins.MODRINTH, "create", snapshot("a"))
        session.put(PackOrigins.MODRINTH, "tech", snapshot())

        assertNotNull(session.get(PackOrigins.MODRINTH, "create"))
    }

    @Test fun `looking at a list again keeps it from being the oldest`() {
        val session = PackSnapshots()
        session.put(PackOrigins.MODRINTH, "keep", snapshot("a"))
        repeat(20) { session.put(PackOrigins.MODRINTH, "q$it", snapshot("a")) }
        session.put(PackOrigins.MODRINTH, "keep", snapshot("a", "b"))
        repeat(20) { session.put(PackOrigins.MODRINTH, "r$it", snapshot("a")) }

        assertNotNull(session.get(PackOrigins.MODRINTH, "keep"))
    }
}
