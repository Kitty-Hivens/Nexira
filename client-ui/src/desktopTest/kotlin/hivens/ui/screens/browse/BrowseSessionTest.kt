package hivens.ui.screens.browse

import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BrowseSessionTest {

    private fun pack(id: String) = CataloguePack(origin = PackOrigin.Modrinth, id = id, title = id, tagline = "")

    private fun snapshot(vararg ids: String, nextPage: Int = 0, endReached: Boolean = false) =
        BrowseSession.Snapshot(ids.map(::pack), nextPage, endReached)

    @Test fun `a source is handed back what it was last showing`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "create", snapshot("a", "b", nextPage = 2))

        val back = session.get(PackOrigin.Modrinth, "create")

        assertNotNull(back)
        assertEquals(listOf("a", "b"), back.packs.map { it.id })
        assertEquals(2, back.nextPage, "coming back to twenty results after scrolling to eighty is its own loss")
    }

    @Test fun `two sources do not share a list`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "", snapshot("create"))

        assertNull(session.get(PackOrigin.Mirror, ""), "the mirror must not be handed Modrinth's catalogue")
    }

    @Test fun `the difference between two spellings of a query is a keystroke`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "Create", snapshot("a"))

        assertNotNull(session.get(PackOrigin.Modrinth, "  create "))
    }

    @Test fun `typing towards a query does not grow the map without end`() {
        val session = BrowseSession()
        repeat(40) { session.put(PackOrigin.Modrinth, "q$it", snapshot("a")) }

        assertNull(session.get(PackOrigin.Modrinth, "q0"), "the oldest goes out")
        assertNotNull(session.get(PackOrigin.Modrinth, "q39"), "the newest stays")
    }

    /**
     * The loop this closes: an empty snapshot was stored like any other, restored as
     * a Loaded list, and painted as a blank pane with neither the empty message nor
     * its retry. The screen then read every later failure as one it already had an
     * answer for, paging was off because the snapshot claimed the end, and leaving
     * wrote the same emptiness back. One source briefly unreachable left Browse
     * blank and inert until the process was restarted.
     */
    @Test fun `nothing is not remembered`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "create", snapshot(endReached = true))

        assertNull(session.get(PackOrigin.Modrinth, "create"))
    }

    @Test fun `an empty answer forgets the list it replaces`() {
        // A source that has genuinely stopped listing something must not leave the
        // last good list to be restored over the empty state that says so.
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "create", snapshot("a", "b"))
        session.put(PackOrigin.Modrinth, "create", snapshot())

        assertNull(session.get(PackOrigin.Modrinth, "create"))
    }

    @Test fun `an empty answer for one query leaves another alone`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "create", snapshot("a"))
        session.put(PackOrigin.Modrinth, "tech", snapshot())

        assertNotNull(session.get(PackOrigin.Modrinth, "create"))
    }

    @Test fun `looking at a list again keeps it from being the oldest`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "keep", snapshot("a"))
        repeat(20) { session.put(PackOrigin.Modrinth, "q$it", snapshot("a")) }
        session.put(PackOrigin.Modrinth, "keep", snapshot("a", "b"))
        repeat(20) { session.put(PackOrigin.Modrinth, "r$it", snapshot("a")) }

        assertNotNull(session.get(PackOrigin.Modrinth, "keep"))
    }
}
