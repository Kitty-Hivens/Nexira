package hivens.core.api.dto.smrt

import hivens.core.data.FileData
import hivens.core.data.flatten
import kotlin.test.Test
import kotlin.test.assertEquals

class SmrtBaselineTest {

    private fun manifest(
        mods: List<SmrtModEntry> = emptyList(),
        assets: List<SmrtAssetEntry> = emptyList(),
    ) = SmrtPackManifest(
        schemaVersion = 2,
        packId = "Industrial",
        packVersion = "2026.05.22",
        generatedAt = "2026-05-22T00:00:00Z",
        minecraft = SmrtMinecraft("1.12.2"),
        loader = SmrtLoader("forge", "14.23.5.2847"),
        java = SmrtJava(8),
        mods = mods,
        assets = assets,
    )

    private fun mod(filename: String, sha1: String, size: Long) =
        SmrtModEntry(filename = filename, sha1 = sha1, sizeBytes = size, source = SmrtSource.SmrtCache("u"))

    private fun asset(dest: String, sha1: String, size: Long) =
        SmrtAssetEntry(dest = dest, sha1 = sha1, sizeBytes = size, source = SmrtSource.SmrtCache("u"))

    @Test
    fun `mods land under mods slash with sha1 and size`() {
        val baseline = manifest(mods = listOf(mod("Quark.jar", "h1", 100L))).toBaselineManifest()
        assertEquals(FileData(sha1 = "h1", size = 100L), baseline.flatten()["mods/Quark.jar"])
    }

    @Test
    fun `assets land at their dest path`() {
        val baseline = manifest(assets = listOf(asset("config/quark.cfg", "h2", 20L))).toBaselineManifest()
        assertEquals(FileData(sha1 = "h2", size = 20L), baseline.flatten()["config/quark.cfg"])
    }

    @Test
    fun `optional disabled mod still records at its canonical path`() {
        val optional = SmrtModEntry(
            filename = "Sodium.jar", sha1 = "h3", sizeBytes = 30L,
            required = false, defaultEnabled = false, source = SmrtSource.SmrtCache("u"),
        )
        val baseline = manifest(mods = listOf(optional)).toBaselineManifest()
        // Baseline is the manifest's identity, not the on-disk .disabled layout.
        assertEquals(FileData(sha1 = "h3", size = 30L), baseline.flatten()["mods/Sodium.jar"])
    }

    @Test
    fun `empty manifest yields empty baseline`() {
        assertEquals(emptyMap(), manifest().toBaselineManifest().flatten())
    }
}
