package hivens.core.api.dto.smrt

import hivens.core.update.VersionChannel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmrtManifestFieldsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun manifest(extra: String = "", modExtra: String = "") = """
        {"schema_version":2,"pack_id":"p","pack_version":"0.1.2"$extra,"generated_at":"t",
         "minecraft":{"version":"1.21.1"},"loader":{"name":"neoforge","version":"21.1.186"},"java":{"major":21},
         "mods":[{"filename":"a.jar","sha1":"aa","size_bytes":1,"source":{"type":"smrt_cache","url":"u"}$modExtra}]}
    """.trimIndent()

    @Test
    fun `channel and fingerprint decode when present`() {
        val m = json.decodeFromString(SmrtPackManifest.serializer(), manifest(""","channel":"beta","fingerprint":"ff""""))
        assertEquals("beta", m.channel)
        assertEquals("ff", m.fingerprint)
        assertEquals(VersionChannel.Beta, m.versionChannel)
    }

    @Test
    fun `a pre-channel manifest derives its channel from the version`() {
        val m = json.decodeFromString(SmrtPackManifest.serializer(), manifest())
        assertNull(m.channel)
        assertNull(m.fingerprint)
        assertEquals(VersionChannel.Release, m.versionChannel)
    }

    @Test
    fun `display presence maps known values and nulls unknown ones`() {
        val known = json.decodeFromString(
            SmrtPackManifest.serializer(),
            manifest(modExtra = ""","display":{"presence":"optional_client"}"""),
        ).mods.single().display
        assertEquals(SmrtPresence.OptionalClient, known?.presenceClass)

        val unknown = json.decodeFromString(
            SmrtPackManifest.serializer(),
            manifest(modExtra = ""","display":{"presence":"serverside_maybe"}"""),
        ).mods.single().display
        assertEquals("serverside_maybe", unknown?.presence)
        assertNull(unknown?.presenceClass)
    }

    @Test
    fun `summary decodes the read-time derived fields`() {
        val body = """
            {"pack_id":"Create","display_name":"Create","tagline":"t","minecraft_version":"1.21.1",
             "latest_pack_version":"0.1.2","latest_built_at":"2026-07-19T02:14:01Z","latest_channel":"beta","tier":"official"}
        """.trimIndent()
        val s = json.decodeFromString(SmrtPackSummary.serializer(), body)
        assertEquals("2026-07-19T02:14:01Z", s.latestBuiltAt)
        assertEquals("beta", s.latestChannel)
        assertEquals("official", s.tier)
    }
}
