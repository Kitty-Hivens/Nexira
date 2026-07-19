package hivens.core.api.dto.smrt

import hivens.core.update.VersionChannel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmrtManifestVersionsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `decodes the live builds listing shape`() {
        // Trimmed from a live /v1/packs/Create/manifest/versions response.
        val body = """
            {"schema_version":2,"pack_id":"Create","latest":"0.1.2","builds":[
              {"version_number":"0.1.2","version_type":"beta","date_published":"2026-07-19T02:14:01.869744662Z","fingerprint":"b17b8860e0f0a4dfab1ac2101a6359704a56ea17","mods_count":87,"assets_count":1},
              {"version_number":"SNAPSHOT-0.0.0-2026.07.17","version_type":"beta","date_published":"2026-07-17T22:25:16.937790162Z","fingerprint":"9a534efcb5d7f158a5a3e00478961df964edd366","mods_count":50,"assets_count":0}
            ]}
        """.trimIndent()

        val listing = json.decodeFromString(SmrtManifestVersions.serializer(), body)
        assertEquals("0.1.2", listing.latest)
        assertEquals(listOf("0.1.2", "SNAPSHOT-0.0.0-2026.07.17"), listing.builds.map { it.versionNumber })
        assertEquals(VersionChannel.Beta, listing.builds.first().channel)
        assertEquals(87, listing.builds.first().modsCount)
        assertEquals(1, listing.builds.first().assetsCount)
    }

    @Test
    fun `a build without channel or fingerprint derives and stays null`() {
        val body = """{"builds":[{"version_number":"SNAPSHOT-0.0.0-2026.05.01","mods_count":10,"assets_count":0}]}"""
        val build = json.decodeFromString(SmrtManifestVersions.serializer(), body).builds.single()
        assertEquals(VersionChannel.Beta, build.channel)
        assertNull(build.fingerprint)
        assertNull(build.datePublished)
    }

    @Test
    fun `the retired versions-array shape decodes to an empty listing`() {
        // Pre-migration servers emitted a plain versions[] array. Nothing serves
        // it anymore; it must degrade to empty rather than throw, letting the
        // catalogue's single-latest fallback take over.
        val body = """{"schema_version":2,"versions":["2026.02.02","2026.01.01"]}"""
        val listing = json.decodeFromString(SmrtManifestVersions.serializer(), body)
        assertNull(listing.latest)
        assertTrue(listing.builds.isEmpty())
    }
}
