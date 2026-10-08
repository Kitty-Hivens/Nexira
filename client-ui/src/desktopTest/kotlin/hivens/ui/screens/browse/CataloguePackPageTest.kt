package hivens.ui.screens.browse

import hivens.core.api.catalogue.CatalogueGalleryItem
import hivens.core.api.catalogue.CatalogueLink
import hivens.core.api.catalogue.CatalogueLinkKind
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackOrigin
import hivens.ui.i18n.EnglishStrings
import hivens.ui.screens.mod.ProjectLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a pack's page reaches for, shows and hands the rail. */
class CataloguePackPageTest {

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
        assertEquals("1", latestOf(details(latest = "1"))?.id, "the mirror's pointer may sit behind the newest")
        assertEquals("2", latestOf(details(latest = null))?.id)
        assertEquals("2", latestOf(details(latest = "gone"))?.id, "a pointer at a build the listing lost falls back")
        assertNull(latestOf(details(versions = emptyList())))
    }

    @Test
    fun `a banner the source keeps apart leads the gallery, and is not shown twice`() {
        val shot = CatalogueGalleryItem(full = "s.png", thumb = "s.png")
        assertEquals(listOf("b.png", "s.png"), galleryOf(details(banner = "b.png", gallery = listOf(shot))).map { it.full })
        val featured = CatalogueGalleryItem(full = "b.png", thumb = "b_350.png")
        assertEquals(listOf("b.png"), galleryOf(details(banner = "b.png", gallery = listOf(featured))).map { it.full })
        assertEquals(emptyList(), galleryOf(details()))
    }

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
        val open = openPackOf("pack:Mirror:Industrial", d, emptyList(), EnglishStrings) { key -> if (key == "smartycraft") "SmartyCraft" else key }
        assertEquals(listOf("1.12.2"), open.gameVersions.map { it.label }, "without the catalogue's list a version is its own chip")
        assertEquals(listOf("cleanroom"), open.loaders)
        assertEquals(listOf("tech"), open.categories)
        assertEquals("Java 21", open.runtime)
        assertEquals(listOf("SmartyCraft"), open.signIn, "named by the provider, looked up by its id")
        assertEquals(
            listOf("microsoft", "SmartyCraft"),
            openPackOf("k", d.copy(auth = PackAuthRequirement.Both("Industrial")), emptyList(), EnglishStrings) { key ->
                if (key == "smartycraft") "SmartyCraft" else key
            }.signIn,
            "a provider this build has not registered goes by its id",
        )
        assertEquals(212, open.modsCount, "the count of the build an install takes")
        assertEquals(700L, open.sizeBytes)
        assertEquals(listOf(ProjectLinkKind.Discord), open.links.map { it.kind })
        assertNull(open.projectType, "a mirror pack's tags are its own words and search nothing")
        assertEquals(false, open.answersLicence, "the mirror has no licence field, so the rail claims none")
        assertEquals(false, open.answersPublished)
    }
}
