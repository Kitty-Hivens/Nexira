package hivens.ui.feature.catalogue.browse

import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackOrigins
import kotlin.test.Test
import kotlin.test.assertEquals

/** Whose mirror packs Browse lists, and that the choice reaches no other source. */
class BrowseBuiltByTest {

    private val own = CatalogueCard(PackOrigins.MIRROR, "industrial", "Industrial", "")
    private val members = CatalogueCard(PackOrigins.MIRROR, "u/7/cozy", "Cozy", "", community = true, author = "alex")
    private val modrinth = CatalogueCard(PackOrigins.MODRINTH, "abc", "Fabulously Optimized", "")

    @Test
    fun `both kinds are listed until one is turned off`() {
        val c = BrowseView()
        assertEquals(listOf(own, members), listOf(own, members).filter(c::shows))

        val noCommunity = c.copy(mirrorCommunity = false)
        assertEquals(listOf(own), listOf(own, members).filter(noCommunity::shows))

        val noOwn = c.copy(mirrorCommunity = true, mirrorOwn = false)
        assertEquals(listOf(members), listOf(own, members).filter(noOwn::shows))
    }

    @Test
    fun `another source's packs are listed whatever the mirror choice`() {
        val c = BrowseView(mirrorOwn = false, mirrorCommunity = false)
        assertEquals(listOf(modrinth), listOf(modrinth).filter(c::shows))
    }
}
