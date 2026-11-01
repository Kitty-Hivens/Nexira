package hivens.core.update

import hivens.core.api.dto.smrt.SmrtManifestVersions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A build as the mirror's listing writes it, and as this client stores it back. */
class PackBuildWireTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    @Test
    fun `the listing's runtime fields are read, the loader out of its object`() {
        val listing = json.decodeFromString<SmrtManifestVersions>(
            """
            {"latest":"0.2.1","builds":[{"version_number":"0.2.1","version_type":"beta",
              "date_published":"2026-10-01T10:00:00Z","minecraft_version":"1.21.1",
              "loader":{"name":"neoforge","version":"21.1.200"},"size_bytes":734003200,
              "mods_count":212,"assets_count":4}]}
            """.trimIndent(),
        )
        val b = listing.builds.single()
        assertEquals("1.21.1", b.minecraftVersion)
        assertEquals("neoforge", b.loaderName)
        assertEquals(734003200L, b.sizeBytes)
        assertEquals(212, b.modsCount)
    }

    @Test
    fun `a stored copy round-trips through the bare name`() {
        val b = PackBuild(versionNumber = "1", minecraftVersion = "1.12.2", loaderName = "cleanroom", sizeBytes = 10)
        assertEquals(b, json.decodeFromString<PackBuild>(json.encodeToString(PackBuild.serializer(), b)))
    }

    @Test
    fun `a build that names no runtime reads as unknown`() {
        val b = json.decodeFromString<PackBuild>("""{"version_number":"1","loader":{}}""")
        assertNull(b.loaderName)
        assertNull(b.minecraftVersion)
    }
}
