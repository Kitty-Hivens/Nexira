package hivens.catalogue

import hivens.catalogue.api.ProjectLinkKind
import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CatalogueLink
import hivens.core.api.catalogue.CatalogueLinkKind
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a pack's page reaches for, shows and hands the rail. */
class PackPageTest {

    private fun version(id: String, mods: Int? = null, size: Long? = null) =
        CataloguePackVersion(id = id, name = id, versionNumber = id, modsCount = mods, sizeBytes = size)

    private fun details(
        versions: List<CataloguePackVersion> = listOf(version("2"), version("1")),
        latest: String? = null,
        banner: String? = null,
        gallery: List<CatalogueGalleryItem> = emptyList(),
    ) = CataloguePackDetails(
        origin = PackOrigin.Mirror,
        id = "Industrial",
        title = "Industrial",
        tagline = "",
        bannerUrl = banner,
        gallery = gallery,
        versions = versions,
        latestVersionId = latest,
    )

    @Test
    fun `an install reaches for the build the source names, else the newest`() {
        assertEquals("1", details(latest = "1").toDetails().latest?.id, "the mirror's pointer may sit behind the newest")
        assertEquals("2", details(latest = null).toDetails().latest?.id)
        assertEquals("2", details(latest = "gone").toDetails().latest?.id, "a pointer at a build the listing lost falls back")
        assertNull(details(versions = emptyList()).toDetails().latest)
    }

    @Test
    fun `a banner the source keeps apart leads the gallery, and is not shown twice`() {
        val shot = CatalogueGalleryItem(full = "s.png", thumb = "s.png")
        assertEquals(listOf("b.png", "s.png"), details(banner = "b.png", gallery = listOf(shot)).galleryWithBanner().map { it.full })
        val featured = CatalogueGalleryItem(full = "b.png", thumb = "b_350.png")
        assertEquals(listOf("b.png"), details(banner = "b.png", gallery = listOf(featured)).galleryWithBanner().map { it.full })
        assertEquals(emptyList(), details().galleryWithBanner())
    }

    /**
     * The rail names each sign-in provider by the provider's own name, which the
     * accounts module lists. The page hands it the keys and nothing it would have to
     * look up in an auth registry.
     */
    @Test
    fun `the rail is handed what the pack says about itself`() {
        val d = details(versions = listOf(version("0.3.1", mods = 212, size = 700), version("0.3.0")), latest = "0.3.1").copy(
            gameVersions = listOf("1.12.2"),
            loaders = listOf("cleanroom"),
            tags = listOf("tech"),
            runtimeLabel = "Java 21",
            auth = PackAuthRequirement.SmartyCraft("Industrial"),
            links = listOf(CatalogueLink(CatalogueLinkKind.Discord, "https://discord.invalid")),
        )
        val open = openPackOf("pack:mirror:Industrial", d.toDetails(), null)
        assertEquals(listOf("1.12.2"), open.gameVersions.map { it.label }, "without the catalogue's list a version is its own chip")
        assertEquals(listOf("cleanroom"), open.loaders)
        assertEquals(listOf("tech"), open.categories)
        assertEquals("Java 21", open.runtime)
        assertEquals(listOf("smartycraft"), open.signIn, "the provider's key, for the rail to name")
        assertEquals(
            listOf("microsoft", "smartycraft"),
            openPackOf("k", d.copy(auth = PackAuthRequirement.Both("Industrial")).toDetails(), null).signIn,
            "every provider the pack asks for",
        )
        assertEquals(212, open.modsCount, "the count of the build an install takes")
        assertEquals(700L, open.sizeBytes)
        assertEquals(listOf(ProjectLinkKind.Discord), open.links.map { it.kind })
        assertNull(open.projectType, "a mirror pack's tags are its own words and search nothing")
        assertEquals(false, open.answersLicence, "the mirror has no licence field, so the rail claims none")
        assertEquals(false, open.answersPublished)
    }
}
