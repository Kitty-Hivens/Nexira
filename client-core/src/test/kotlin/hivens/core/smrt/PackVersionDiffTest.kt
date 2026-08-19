package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtJava
import hivens.core.api.dto.smrt.SmrtLoader
import hivens.core.update.PackBuild
import hivens.core.api.dto.smrt.SmrtMinecraft
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackVersionDiffTest {

    private fun modrinthMod(project: String, filename: String, sha1: String, size: Long = 10) = SmrtModEntry(
        filename = filename, sha1 = sha1, sizeBytes = size,
        source = SmrtSource.Modrinth(projectId = project, versionId = "v-$sha1"),
    )

    private fun cacheMod(filename: String, sha1: String, slug: String? = null, size: Long = 10) = SmrtModEntry(
        filename = filename, sha1 = sha1, sizeBytes = size, slug = slug,
        source = SmrtSource.SmrtCache(url = "https://m/$sha1.jar"),
    )

    private fun asset(dest: String, sha1: String) = SmrtAssetEntry(
        dest = dest, sha1 = sha1, sizeBytes = 5,
        source = SmrtSource.SmrtStatic(url = "https://m/$dest"),
    )

    private fun manifest(
        version: String,
        mods: List<SmrtModEntry> = emptyList(),
        assets: List<SmrtAssetEntry> = emptyList(),
        mc: String = "1.21.1",
        loader: SmrtLoader = SmrtLoader("neoforge", "21.1.186"),
        java: Int = 21,
        fingerprint: String? = null,
        channel: String? = null,
    ) = SmrtPackManifest(
        schemaVersion = 2, packId = "p", packVersion = version, channel = channel,
        generatedAt = "t", fingerprint = fingerprint,
        minecraft = SmrtMinecraft(mc), loader = loader, java = SmrtJava(java),
        mods = mods, assets = assets,
    )

    @Test
    fun `adds, removes and updates resolve by stable key across filename changes`() {
        val from = manifest("1", mods = listOf(
            modrinthMod("aaa", "Sodium-1.0.jar", sha1 = "s1"),
            modrinthMod("bbb", "Lithium.jar", sha1 = "l1"),
        ))
        val to = manifest("2", mods = listOf(
            // Same Modrinth project, new filename AND new bytes -> Updated, not remove+add.
            modrinthMod("aaa", "Sodium-2.0.jar", sha1 = "s2"),
            modrinthMod("ccc", "Iris.jar", sha1 = "i1"),
        ))

        val diff = PackVersionDiff.compute(from, to)
        assertEquals(
            mapOf(DiffKind.Added to 1, DiffKind.Removed to 1, DiffKind.Updated to 1),
            diff.mods.groupingBy { it.kind }.eachCount(),
        )
        val updated = diff.mods.single { it.kind == DiffKind.Updated }
        assertEquals("Sodium-1.0.jar", updated.from?.filename)
        assertEquals("Sodium-2.0.jar", updated.to?.filename)
        assertEquals("Iris.jar", diff.mods.single { it.kind == DiffKind.Added }.to?.filename)
        assertEquals("Lithium.jar", diff.mods.single { it.kind == DiffKind.Removed }.from?.filename)
        assertTrue(!diff.identicalContent)
    }

    @Test
    fun `slug-less cache mod version bump pairs by filename stem into Updated`() {
        val from = manifest("1", mods = listOf(cacheMod("CreativeCore_NEOFORGE_v2.13.41_mc1.21.1.jar", "c1")))
        val to = manifest("2", mods = listOf(cacheMod("CreativeCore_NEOFORGE_v2.13.45_mc1.21.1.jar", "c2")))

        val entry = PackVersionDiff.compute(from, to).mods.single()
        assertEquals(DiffKind.Updated, entry.kind)
        assertEquals("c1", entry.from?.sha1)
        assertEquals("c2", entry.to?.sha1)
    }

    @Test
    fun `ambiguous stems stay as separate add and remove`() {
        val from = manifest("1", mods = listOf(cacheMod("Thing-1.0.jar", "t1"), cacheMod("Thing-1.1.jar", "t2")))
        val to = manifest("2", mods = listOf(cacheMod("Thing-2.0.jar", "t3")))

        val diff = PackVersionDiff.compute(from, to)
        assertEquals(2, diff.mods.count { it.kind == DiffKind.Removed })
        assertEquals(1, diff.mods.count { it.kind == DiffKind.Added })
        assertEquals(0, diff.mods.count { it.kind == DiffKind.Updated })
    }

    @Test
    fun `assets diff by destination path`() {
        val from = manifest("1", assets = listOf(asset("config/x.cfg", "x1"), asset("config/gone.cfg", "g1")))
        val to = manifest("2", assets = listOf(asset("config/x.cfg", "x2"), asset("config/new.cfg", "n1")))

        val diff = PackVersionDiff.compute(from, to)
        assertEquals(DiffKind.Updated, diff.assets.single { it.to?.dest == "config/x.cfg" }.kind)
        assertEquals(DiffKind.Added, diff.assets.single { it.to?.dest == "config/new.cfg" }.kind)
        assertEquals(DiffKind.Removed, diff.assets.single { it.from?.dest == "config/gone.cfg" }.kind)
    }

    @Test
    fun `pack level changes surface and equal fields stay null`() {
        val from = manifest("1", mc = "1.21.1", loader = SmrtLoader("neoforge", "21.1.186"), java = 21)
        val to = manifest("2", mc = "1.21.4", loader = SmrtLoader("neoforge", "21.4.50"), java = 21, channel = "release")

        val diff = PackVersionDiff.compute(from, to)
        assertEquals(PackFieldChange("1.21.1", "1.21.4"), diff.minecraft)
        assertEquals(PackFieldChange("neoforge 21.1.186", "neoforge 21.4.50"), diff.loader)
        assertNull(diff.java)
        // from derives release (no SNAPSHOT prefix), to states release -> no change.
        assertNull(diff.channel)
    }

    @Test
    fun `equal non-null fingerprints short-cut to identical content`() {
        // Deliberately different mod lists: the fingerprint match must win without comparing.
        val from = manifest("1", mods = listOf(cacheMod("a.jar", "a1")), fingerprint = "ff")
        val to = manifest("2", mods = listOf(cacheMod("b.jar", "b1")), fingerprint = "ff")

        val diff = PackVersionDiff.compute(from, to)
        assertTrue(diff.identicalContent)
        assertTrue(diff.mods.isEmpty())
    }

    @Test
    fun `null fingerprints never match and fall back to comparison`() {
        val same = listOf(cacheMod("a.jar", "a1"))
        val diff = PackVersionDiff.compute(
            manifest("1", mods = same, fingerprint = null),
            manifest("2", mods = same, fingerprint = null),
        )
        assertTrue(diff.identicalContent, "computed-empty diff still reports identical")
        assertTrue(diff.mods.isEmpty())
    }

    @Test
    fun `display name drives updated-entry ordering`() {
        val from = manifest("1", mods = listOf(
            cacheMod("zzz.jar", "z1", slug = "zeta"),
            cacheMod("aaa.jar", "a1", slug = "alpha"),
        ))
        val to = manifest("2", mods = listOf(
            cacheMod("zzz.jar", "z2", slug = "zeta").copy(display = SmrtDisplay(name = "Beta Mod")),
            cacheMod("aaa.jar", "a2", slug = "alpha").copy(display = SmrtDisplay(name = "Zulu Mod")),
        ))
        val names = PackVersionDiff.compute(from, to).mods.map { it.to?.display?.name }
        assertEquals(listOf("Beta Mod", "Zulu Mod"), names)
    }

    @Test
    fun `rebuild runs group only consecutive equal non-null fingerprints`() {
        fun build(v: String, fp: String?) = PackBuild(versionNumber = v, fingerprint = fp)
        val runs = groupRebuildRuns(listOf(
            build("0.1.2", "b1"), build("0.1.1", "b1"), build("0.1.0", "b1"),
            build("s8", "724a"), build("s7", "ed4a"), build("s6", "ed4a"),
            build("old1", null), build("old2", null),
        ))
        assertEquals(
            listOf(listOf("0.1.2", "0.1.1", "0.1.0"), listOf("s8"), listOf("s7", "s6"), listOf("old1"), listOf("old2")),
            runs.map { run -> run.map { it.versionNumber } },
        )
    }
}
