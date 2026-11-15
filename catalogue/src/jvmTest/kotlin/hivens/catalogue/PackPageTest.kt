package hivens.catalogue

import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectLink
import hivens.catalogue.api.ProjectLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a pack's page reaches for, shows and hands the rail. */
class PackPageTest {

    private fun version(id: String, mods: Int? = null, size: Long? = null) =
        ProjectBuild(id = id, name = id, versionNumber = id, versionType = "release", gameVersions = emptyList(), loaders = emptyList(), datePublished = "", modsCount = mods, sizeBytes = size)

    private fun details(
        versions: List<ProjectBuild> = listOf(version("2"), version("1")),
        latest: String? = null,
    ) = PackDetails(
        origin = "Shelf",
        id = "Industrial",
        title = "Industrial",
        tagline = "",
        versions = versions,
        latestVersionId = latest,
    )

    @Test
    fun `an install reaches for the build the source names, else the newest`() {
        assertEquals("1", details(latest = "1").latest?.id, "a source's pointer may sit behind the newest")
        assertEquals("2", details(latest = null).latest?.id)
        assertEquals("2", details(latest = "gone").latest?.id, "a pointer at a build the listing lost falls back")
        assertNull(details(versions = emptyList()).latest)
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
            signIn = listOf("smartycraft"),
            links = listOf(ProjectLink(ProjectLinkKind.Discord, "https://discord.invalid")),
        )
        val open = openPackOf("pack:Shelf:Industrial", d, null)
        assertEquals(listOf("1.12.2"), open.gameVersions.map { it.label }, "without the catalogue's list a version is its own chip")
        assertEquals(listOf("cleanroom"), open.loaders)
        assertEquals(listOf("tech"), open.categories)
        assertEquals("Java 21", open.runtime)
        assertEquals(listOf("smartycraft"), open.signIn, "the provider's key, for the rail to name")
        assertEquals(
            listOf("microsoft", "smartycraft"),
            openPackOf("k", d.copy(signIn = listOf("microsoft", "smartycraft")), null).signIn,
            "every provider the pack asks for",
        )
        assertEquals(212, open.modsCount, "the count of the build an install takes")
        assertEquals(700L, open.sizeBytes)
        assertEquals(listOf(ProjectLinkKind.Discord), open.links.map { it.kind })
        assertNull(open.projectType, "a pack whose tags are its source's own words searches nothing")
        assertEquals(false, open.answersLicence, "a source with no licence field has the rail claim none")
        assertEquals(false, open.answersPublished)
    }
}
