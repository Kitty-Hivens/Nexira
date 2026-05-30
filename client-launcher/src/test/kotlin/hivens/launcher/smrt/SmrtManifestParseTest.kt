package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtAuth
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.dto.smrt.SmrtSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips a sample v2 manifest -- structured like the live
 * Industrial response -- through the wire types. Catches schema
 * regressions when we extend the dto package (display fields landing,
 * new source variant added) without bumping schema_version on the
 * server side.
 */
class SmrtManifestParseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parses manifest with all three source types`() {
        val pm: SmrtPackManifest = json.decodeFromString(SAMPLE_MANIFEST)
        assertEquals(2, pm.schemaVersion)
        assertEquals("Industrial", pm.packId)
        assertEquals("2026.05.22.9", pm.packVersion)
        assertEquals("1.12.2", pm.minecraft.version)
        assertEquals("forge", pm.loader.name)
        assertEquals(8, pm.java.major)
        assertEquals(3, pm.mods.size)
        assertEquals(2, pm.assets.size)

        val modrinthMod = pm.mods.first { it.filename == "AppleSkin-mc1.12-1.0.14.jar" }
        val src = modrinthMod.source
        assertIs<SmrtSource.Modrinth>(src)
        assertEquals("EsAfCjCV", src.projectId)
        assertEquals("vQYqQk7L", src.versionId)
        // Display block round-trips when present
        assertNotNull(modrinthMod.display)
        assertEquals("Appleskin", modrinthMod.display!!.name)
        assertEquals("performance", modrinthMod.display!!.category)
        // Rich display fields round-trip
        assertEquals("https://cdn.modrinth.com/data/EsAfCjCV/icon.png", modrinthMod.display!!.iconUrl)
        assertEquals("recipe_viewer", modrinthMod.display!!.role)
        assertEquals(1, modrinthMod.display!!.requires.size)
        modrinthMod.display!!.requires[0].let { req ->
            assertEquals("AdvancedMachines.jar", req.filename)
            assertEquals(">=1.0", req.versionRange)
            assertFalse(req.optional)
        }

        val cacheMod = pm.mods.first { it.filename == "AdvancedMachines.jar" }
        val cacheSrc = cacheMod.source
        assertIs<SmrtSource.SmrtCache>(cacheSrc)
        assertTrue(cacheSrc.url.startsWith("https://smrt.hivens.dev/v1/cache/"))

        val osnMod = pm.mods.first { it.filename == "Smarty-1.12.2.jar" }
        assertIs<SmrtSource.SmrtCache>(osnMod.source)

        val staticAsset = pm.assets.first { it.dest == "shaderpacks/Chocapic13 V7.1 Extreme.zip" }
        val staticSrc = staticAsset.source
        assertIs<SmrtSource.SmrtStatic>(staticSrc)
        // URL must be percent-encoded (smrt-pack fix for spaces in rel_path)
        assertTrue(staticSrc.url.contains("%20"),
            "smrt_static URL must percent-encode spaces; got: ${staticSrc.url}")
    }

    @Test
    fun `parses pack summary with rich metadata`() {
        val payload = """
        {
            "pack_id": "Industrial",
            "display_name": "Industrial",
            "tagline": "Heavy industry and automation.",
            "minecraft_version": "1.12.2",
            "latest_pack_version": "2026.05.22.9",
            "tags": ["industry", "automation"],
            "featured": true,
            "icon_url": "https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/icon.png",
            "banner_url": "https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/banner.png",
            "gallery_urls": [
                "https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/g1.png",
                "https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/g2.png"
            ],
            "description_md": "# Industrial\n\nLong-form pack copy."
        }
        """.trimIndent()
        val s: SmrtPackSummary = json.decodeFromString(payload)
        assertEquals("Industrial", s.packId)
        assertEquals("https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/icon.png", s.iconUrl)
        assertEquals("https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/banner.png", s.bannerUrl)
        assertEquals(2, s.galleryUrls.size)
        assertTrue(s.descriptionMd!!.startsWith("# Industrial"))
    }

    @Test
    fun `parses pack summary without rich metadata`() {
        // Mirror manifests authored before the rich-metadata extension still
        // parse with all new fields defaulted to null / empty.
        val bare = """
        {
            "pack_id": "Bare",
            "display_name": "Bare",
            "tagline": "",
            "minecraft_version": "1.12.2",
            "latest_pack_version": "2026.06.01"
        }
        """.trimIndent()
        val s: SmrtPackSummary = json.decodeFromString(bare)
        assertNull(s.iconUrl)
        assertNull(s.bannerUrl)
        assertTrue(s.galleryUrls.isEmpty())
        assertNull(s.descriptionMd)
    }

    @Test
    fun `parses smartycraft auth block on a manifest`() {
        val payload = """
        {
            "schema_version": 2,
            "pack_id": "Industrial",
            "pack_version": "2026.05.30.1",
            "generated_at": "2026-05-30T00:00:00Z",
            "minecraft": {"version": "1.12.2"},
            "loader": {"name": "forge", "version": "14.23.5.2922"},
            "java": {"major": 8},
            "auth": {"kind": "smartycraft", "server_id": "Industrial"}
        }
        """.trimIndent()
        val pm: SmrtPackManifest = json.decodeFromString(payload)
        val auth = pm.auth
        assertIs<SmrtAuth.Smartycraft>(auth)
        assertEquals("Industrial", auth.serverId)
    }

    @Test
    fun `unknown auth kind decodes as null without failing the whole manifest`() {
        // Forward-compat: a mirror manifest carrying a future provider
        // (mojang / elyby / etc.) must NOT abort the entire parse on
        // older clients -- the launcher would lose browse + install.
        // SmrtAuthLenientSerializer folds unknown kinds to null so the
        // pack just appears unrestricted to the older client.
        val payload = """
        {
            "schema_version": 2,
            "pack_id": "FutureBound",
            "pack_version": "2027.01.01",
            "generated_at": "2027-01-01T00:00:00Z",
            "minecraft": {"version": "1.21.4"},
            "loader": {"name": "neoforge", "version": "21.4.99"},
            "java": {"major": 21},
            "auth": {"kind": "mojang", "client_id": "abcd1234"}
        }
        """.trimIndent()
        val pm: SmrtPackManifest = json.decodeFromString(payload)
        assertNull(pm.auth, "unknown auth.kind must decode as null, not throw")
        assertEquals("FutureBound", pm.packId, "the rest of the manifest must decode normally")
    }

    @Test
    fun `manifest without auth block parses with null and round-trips`() {
        val payload = """
        {
            "schema_version": 2,
            "pack_id": "Vanilla",
            "pack_version": "2026.05.30.1",
            "generated_at": "2026-05-30T00:00:00Z",
            "minecraft": {"version": "1.21.1"},
            "loader": {"name": "vanilla", "version": "1.21.1"},
            "java": {"major": 21}
        }
        """.trimIndent()
        val pm: SmrtPackManifest = json.decodeFromString(payload)
        assertNull(pm.auth)

        // Round-trip: a vanilla manifest stays vanilla.
        val encoded = json.encodeToString(SmrtPackManifest.serializer(), pm)
        val decoded: SmrtPackManifest = json.decodeFromString(encoded)
        assertEquals(pm, decoded)
    }

    @Test
    fun `smartycraft auth block round-trips byte-identically`() {
        // Lock the encode path: writing a known requirement and reading
        // it back yields the same object. Catches accidental regressions
        // in SmrtAuthLenientSerializer.serialize.
        val original = SmrtPackManifest(
            schemaVersion = 2,
            packId        = "Industrial",
            packVersion   = "2026.05.30.1",
            generatedAt   = "2026-05-30T00:00:00Z",
            minecraft     = hivens.core.api.dto.smrt.SmrtMinecraft("1.12.2"),
            loader        = hivens.core.api.dto.smrt.SmrtLoader("forge", "14.23.5.2922"),
            java          = hivens.core.api.dto.smrt.SmrtJava(8),
            auth          = SmrtAuth.Smartycraft("Industrial"),
        )
        val encoded = json.encodeToString(SmrtPackManifest.serializer(), original)
        val decoded: SmrtPackManifest = json.decodeFromString(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `tolerates absent display block and unknown fields`() {
        // Manifest without display blocks (pre-v8) AND with an unknown
        // future field at both the entry and root level. Both must parse.
        val minimal = """
        {
            "schema_version": 2,
            "pack_id": "Bare",
            "pack_version": "2026.06.01",
            "generated_at": "2026-06-01T00:00:00Z",
            "minecraft": {"version": "1.12.2"},
            "loader": {"name": "forge", "version": "14.23.5.2922"},
            "java": {"major": 8},
            "future_root_field": {"x": 1},
            "mods": [
                {
                    "filename": "X.jar",
                    "sha1": "0000000000000000000000000000000000000000",
                    "size_bytes": 100,
                    "required": true,
                    "source": {"type": "smrt_cache", "url": "https://example/v1/cache/00/00.jar"},
                    "future_entry_field": "ignored"
                }
            ]
        }
        """.trimIndent()
        val pm: SmrtPackManifest = json.decodeFromString(minimal)
        assertNull(pm.mods[0].display)
        assertTrue(pm.assets.isEmpty())
    }

    private val SAMPLE_MANIFEST = """
    {
        "schema_version": 2,
        "pack_id": "Industrial",
        "pack_version": "2026.05.22.9",
        "generated_at": "2026-05-22T21:20:33Z",
        "minecraft": {"version": "1.12.2"},
        "loader": {"name": "forge", "version": "14.23.5.2922"},
        "java": {"major": 8},
        "mods": [
            {
                "filename": "AdvancedMachines.jar",
                "sha1": "33833ac7fb6f32a9d84a451f20a68530b7096f0f",
                "size_bytes": 187502,
                "required": true,
                "source": {
                    "type": "smrt_cache",
                    "url": "https://smrt.hivens.dev/v1/cache/33/33833ac7fb6f32a9d84a451f20a68530b7096f0f.jar"
                }
            },
            {
                "filename": "AppleSkin-mc1.12-1.0.14.jar",
                "sha1": "abc",
                "size_bytes": 50000,
                "required": true,
                "source": {
                    "type": "modrinth",
                    "project_id": "EsAfCjCV",
                    "version_id": "vQYqQk7L"
                },
                "display": {
                    "name": "Appleskin",
                    "description": "Hunger and saturation visualization.",
                    "category": "performance",
                    "url": "https://modrinth.com/mod/appleskin",
                    "icon_url": "https://cdn.modrinth.com/data/EsAfCjCV/icon.png",
                    "role": "recipe_viewer",
                    "requires": [
                        {"filename": "AdvancedMachines.jar", "version_range": ">=1.0", "optional": false}
                    ]
                }
            },
            {
                "filename": "Smarty-1.12.2.jar",
                "sha1": "ddaf68623b5902272a3486e4dd19d8c6eb0b64d6",
                "size_bytes": 14300,
                "required": true,
                "source": {
                    "type": "smrt_cache",
                    "url": "https://smrt.hivens.dev/v1/cache/dd/ddaf68623b5902272a3486e4dd19d8c6eb0b64d6.jar"
                }
            }
        ],
        "assets": [
            {
                "dest": "config/quark.cfg",
                "sha1": "111",
                "size_bytes": 100000,
                "required": true,
                "source": {
                    "type": "smrt_static",
                    "url": "https://smrt.hivens.dev/v1/packs/Industrial/static/config/quark.cfg"
                },
                "display": {"category": "config"}
            },
            {
                "dest": "shaderpacks/Chocapic13 V7.1 Extreme.zip",
                "sha1": "b414730359af9385b26a017226aa653c1c3d5e82",
                "size_bytes": 824512,
                "required": false,
                "source": {
                    "type": "smrt_static",
                    "url": "https://smrt.hivens.dev/v1/packs/Industrial/static/shaderpacks/Chocapic13%20V7.1%20Extreme.zip"
                }
            }
        ]
    }
    """.trimIndent()
}
