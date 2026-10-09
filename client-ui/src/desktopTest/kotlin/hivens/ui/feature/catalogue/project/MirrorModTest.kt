package hivens.ui.feature.catalogue.project

import hivens.core.api.dto.smrt.SmrtCurseForgeIdentity
import hivens.core.api.dto.smrt.SmrtModDetail
import hivens.core.api.dto.smrt.SmrtModEdge
import hivens.core.api.dto.smrt.SmrtModFile
import hivens.core.api.dto.smrt.SmrtModRelease
import hivens.core.api.dto.smrt.SmrtModUse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the project page reads out of the mirror's registry page for a file. */
class MirrorModTest {

    private val detail = SmrtModDetail(
        modId = 2,
        name = "Advanced Machines",
        author = "immibis, Chocohead",
        loaders = listOf("forge"),
        mcVersions = listOf("1.12.2"),
        releases = listOf(
            SmrtModRelease(
                releaseId = 2,
                versionNumber = "61.0.0",
                channel = "unknown",
                files = listOf(
                    SmrtModFile(version = "61.0.0", targets = listOf("forge", "any"), mcVersions = listOf("1.12.2"), sha1 = "old", sizeBytes = 187502),
                    SmrtModFile(
                        version = "61.0.0", targets = listOf("forge"), mcVersions = listOf("1.12.2"), sha1 = "mine", sizeBytes = 190539,
                        filename = "AdvancedMachines.jar", curseforge = SmrtCurseForgeIdentity(projectId = 251205, fileId = 2667002),
                    ),
                ),
            ),
        ),
        edges = listOf(
            SmrtModEdge(dir = "out", otherName = "IndustrialCraft 2", kind = "requires"),
            SmrtModEdge(dir = "out", otherName = "Just Enough Items (JEI)", kind = "optional_dep"),
            SmrtModEdge(dir = "in", otherName = "Some Addon", kind = "requires"),
        ),
        usedBy = listOf(
            SmrtModUse(packId = "Industrial-Cleanroom", packVersion = "0.1.37"),
            SmrtModUse(packId = "Galaxy", packVersion = "0.1.11"),
            SmrtModUse(packId = "Nevermine", packVersion = "0.1.2"),
            SmrtModUse(packId = "Unlisted", packVersion = "1.0.0"),
        ),
    )

    private val mod = MirrorMod(
        detail,
        sha1 = "mine",
        iconUrl = null,
        packs = mapOf(
            "Industrial-Cleanroom" to MirrorPackRef("Industrial", "0.1.37"),
            "Galaxy" to MirrorPackRef("Galaxy", "0.1.11"),
            "Nevermine" to MirrorPackRef("Nevermine", "0.1.6"),
        ),
    )

    @Test
    fun `a release reads as a build, ungraded as a release and undated`() {
        val b = mod.builds().single()
        assertEquals("61.0.0", b.label)
        assertEquals("release", b.versionType, "a beta is a claim nobody made")
        assertEquals(listOf("forge"), b.loaders, "any is no loader to filter by")
        assertEquals("", b.datePublished)
        assertEquals(listOf("old", "AdvancedMachines.jar"), b.files.map { it.filename }, "a file with no name goes by its hash")
        assertEquals(listOf(false, true), b.files.map { it.primary }, "the file this page is about is the marked one")
    }

    @Test
    fun `the host that publishes the file is named and linked`() {
        assertEquals("CurseForge", mod.publishedOn)
        assertEquals(listOf("https://www.curseforge.com/projects/251205"), mod.links.map { it.url })
        val selfHosted = MirrorMod(detail, sha1 = "old", iconUrl = null)
        assertNull(selfHosted.publishedOn, "a file only the mirror holds names no other host")
        assertEquals(emptyList(), selfHosted.links)
    }

    @Test
    fun `what it requires, who wrote it and where it ships`() {
        assertEquals(listOf("IndustrialCraft 2"), mod.requires, "an optional dependency and one pointing in are not requirements")
        assertEquals(listOf("immibis", "Chocohead"), mod.authors)
        assertEquals(
            listOf("Industrial", "Galaxy"),
            mod.usedBy,
            "a pack whose current build no longer ships it, and one not listed, are left out",
        )
        assertEquals(
            listOf("Industrial-Cleanroom 0.1.37", "Galaxy 0.1.11", "Nevermine 0.1.2", "Unlisted 1.0.0"),
            MirrorMod(detail, sha1 = "mine", iconUrl = null).usedBy,
            "without the listing every build is named with its version",
        )
    }
}
