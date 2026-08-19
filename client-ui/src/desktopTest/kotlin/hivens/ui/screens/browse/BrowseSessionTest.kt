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

    @Test fun `the browsed source outlives the screen`() {
        val session = BrowseSession()
        session.origin = PackOrigin.Modrinth

        assertEquals(PackOrigin.Modrinth, session.origin, "opening a pack and coming back must not reset the switcher")
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

    @Test fun `looking at a list again keeps it from being the oldest`() {
        val session = BrowseSession()
        session.put(PackOrigin.Modrinth, "keep", snapshot("a"))
        repeat(20) { session.put(PackOrigin.Modrinth, "q$it", snapshot("a")) }
        session.put(PackOrigin.Modrinth, "keep", snapshot("a", "b"))
        repeat(20) { session.put(PackOrigin.Modrinth, "r$it", snapshot("a")) }

        assertNotNull(session.get(PackOrigin.Modrinth, "keep"))
    }
}
