package hivens.ui.feature.catalogue.browse

import hivens.catalogue.api.BrowseView
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.SourceOption
import hivens.catalogue.api.SourceView
import hivens.source.mirror.MirrorPackSource
import hivens.source.mirror.MirrorSourceModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whose mirror packs Browse lists, by the filter the mirror source declares, and that
 * the choice reaches no other source.
 */
class BrowseBuiltByTest {

    private val mirror = SourceView(MirrorPackSource.id, MirrorSourceModule.ID, MirrorPackSource.label, MirrorPackSource.icon, MirrorPackSource.filters)

    /** The mirror's own cards and its community's, as its listing hands them over. */
    private val own = CatalogueCard(mirror.id, "industrial", "Industrial", "", facets = mapOf(MirrorPackSource.BUILT_BY to MirrorPackSource.OWN))
    private val members = CatalogueCard(
        mirror.id, "u/7/cozy", "Cozy", "",
        community = true, author = "alex", facets = mapOf(MirrorPackSource.BUILT_BY to MirrorPackSource.COMMUNITY),
    )
    private val elsewhere = CatalogueCard("Modrinth", "abc", "Fabulously Optimized", "")

    private val ownOption = SourceOption(mirror.id, MirrorPackSource.BUILT_BY, MirrorPackSource.OWN)
    private val communityOption = SourceOption(mirror.id, MirrorPackSource.BUILT_BY, MirrorPackSource.COMMUNITY)

    @Test
    fun `the mirror declares the one filter it always had, its own packs and its community's`() {
        val builtBy = mirror.filters.single()
        assertEquals(MirrorPackSource.BUILT_BY, builtBy.id)
        assertEquals(listOf(MirrorPackSource.OWN, MirrorPackSource.COMMUNITY), builtBy.options.map { it.id })
    }

    @Test
    fun `both kinds are listed until one is turned off`() {
        val c = BrowseView(origin = mirror.id, sources = listOf(mirror))
        assertEquals(listOf(own, members), listOf(own, members).filter(c::shows))

        val noCommunity = c.copy(hidden = setOf(communityOption))
        assertEquals(listOf(own), listOf(own, members).filter(noCommunity::shows))

        val noOwn = c.copy(hidden = setOf(ownOption))
        assertEquals(listOf(members), listOf(own, members).filter(noOwn::shows))
    }

    @Test
    fun `another source's packs are listed whatever the mirror choice`() {
        val c = BrowseView(origin = mirror.id, sources = listOf(mirror), hidden = setOf(ownOption, communityOption))
        assertEquals(listOf(elsewhere), listOf(elsewhere).filter(c::shows))
    }
}
