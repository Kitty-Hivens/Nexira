package hivens.ui.feature.catalogue.browse

import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Whose mirror packs Browse lists, and that the choice reaches no other source. */
class BrowseBuiltByTest {

    private val own = CataloguePack(PackOrigin.Mirror, "industrial", "Industrial", "")
    private val members = CataloguePack(PackOrigin.Mirror, "u/7/cozy", "Cozy", "", community = true, author = "alex")
    private val modrinth = CataloguePack(PackOrigin.Modrinth, "abc", "Fabulously Optimized", "")

    @Test
    fun `both kinds are listed until one is turned off`() {
        val c = BrowseController()
        assertEquals(listOf(own, members), listOf(own, members).filter(c::shows))

        c.mirrorCommunity = false
        assertEquals(listOf(own), listOf(own, members).filter(c::shows))

        c.mirrorCommunity = true
        c.mirrorOwn = false
        assertEquals(listOf(members), listOf(own, members).filter(c::shows))
    }

    @Test
    fun `another source's packs are listed whatever the mirror choice`() {
        val c = BrowseController()
        c.mirrorOwn = false
        c.mirrorCommunity = false
        assertEquals(listOf(modrinth), listOf(modrinth).filter(c::shows))
    }
}
