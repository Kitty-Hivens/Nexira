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

    private fun withSource(source: String) = """
        {"schema_version":2,"pack_id":"p","pack_version":"0.1.2","generated_at":"t",
         "minecraft":{"version":"1.21.1"},"loader":{"name":"neoforge","version":"21.1.186"},"java":{"major":21},
         "mods":[{"filename":"a.jar","sha1":"aa","size_bytes":1,"source":$source}]}
    """.trimIndent()

    @Test
    fun `a github source decodes and keys an optional toggle by its repository`() {
        val body = withSource(
            """{"type":"github","repo":"Kitty-Hivens/hidemymods","tag":"v0.2.0",
                "asset":"hidemymods-1.7.10.jar",
                "url":"https://github.com/Kitty-Hivens/hidemymods/releases/download/v0.2.0/hidemymods-1.7.10.jar"}""",
        )
        val mod = json.decodeFromString(SmrtPackManifest.serializer(), body).mods.single()
        val source = mod.source as SmrtSource.Github
        assertEquals("Kitty-Hivens/hidemymods", source.repo)
        assertEquals("v0.2.0", source.tag)
        assertEquals("hidemymods-1.7.10.jar", source.asset)
        // Neither the tag nor the asset name may reach the key: both carry the
        // version, the tag by definition and the asset name by convention, and
        // either one re-keys the entry at the next release. The repository stays.
        assertEquals("github:Kitty-Hivens/hidemymods", mod.stableKey)
    }

    /**
     * The property the key exists for, stated as the release it has to survive:
     * the same mod at a new tag, published under a versioned asset name, is the
     * same entry and keeps whatever the player chose for it.
     */
    @Test
    fun `a github release bump does not re-key the entry`() {
        fun keyAt(tag: String, asset: String) = json.decodeFromString(
            SmrtPackManifest.serializer(),
            withSource(
                """{"type":"github","repo":"Kitty-Hivens/hidemymods","tag":"$tag","asset":"$asset",
                    "url":"https://github.com/Kitty-Hivens/hidemymods/releases/download/$tag/$asset"}""",
            ),
        ).mods.single().stableKey

        assertEquals(
            keyAt("v0.2.0", "hidemymods-0.2.0.jar"),
            keyAt("v0.3.1", "hidemymods-0.3.1.jar"),
            "a new release is the same optional mod, so the toggle must follow it",
        )
    }

    @Test
    fun `an unknown source type leaves the rest of the manifest readable`() {
        val body = withSource("""{"type":"gopher","url":"gopher://x"}""")
        val m = json.decodeFromString(SmrtPackManifest.serializer(), body)
        assertEquals(SmrtSource.Unknown, m.mods.single().source)
        assertEquals("p", m.packId)
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
