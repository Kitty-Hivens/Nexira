package hivens.ui.screens.browse

import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the pack page says a pack runs on. This replaced a column of label-and-
 * value rows, and the thing that matters about the replacement is that it says
 * nothing where the column would have said "unknown".
 */
class CompatFactsTest {

    private fun details(
        versions: List<CataloguePackVersion> = emptyList(),
        runtime: String? = null,
    ) = CataloguePackDetails(
        origin = PackOrigin.Modrinth,
        id = "p",
        title = "Pack",
        tagline = "",
        runtimeLabel = runtime,
        versions = versions,
    )

    private fun version(mc: List<String> = emptyList(), loaders: List<String> = emptyList()) =
        CataloguePackVersion(id = "1", name = "1", versionNumber = "1", mcVersions = mc, loaders = loaders)

    @Test fun `the game, then the loader, then the runtime`() {
        val d = details(listOf(version(mc = listOf("1.20.1"), loaders = listOf("fabric"))), runtime = "Java 21")

        assertEquals(listOf("Minecraft 1.20.1", "Fabric", "Java 21"), compatFacts(d))
    }

    @Test fun `the newest build is what the page speaks for`() {
        val d = details(
            listOf(
                version(mc = listOf("1.21.1"), loaders = listOf("neoforge")),
                version(mc = listOf("1.20.1"), loaders = listOf("forge")),
            ),
        )

        assertEquals(listOf("Minecraft 1.21.1", "Neoforge"), compatFacts(d), "the install button reaches for the newest, so the line must agree with it")
    }

    @Test fun `a source silent on the runtime says nothing about it`() {
        val d = details(listOf(version(mc = listOf("1.20.1"), loaders = listOf("fabric"))))

        assertEquals(listOf("Minecraft 1.20.1", "Fabric"), compatFacts(d), "a placeholder reads as a fact and is not one")
    }

    @Test fun `a vanilla pack names no loader`() {
        val d = details(listOf(version(mc = listOf("1.20.1"))))

        assertEquals(listOf("Minecraft 1.20.1"), compatFacts(d))
    }

    @Test fun `blank fields are not facts`() {
        val d = details(listOf(version(mc = listOf(""), loaders = listOf("  "))), runtime = "")

        assertEquals(emptyList(), compatFacts(d))
    }

    @Test fun `a pack with no builds listed says nothing`() {
        assertEquals(emptyList(), compatFacts(details()))
    }
}
