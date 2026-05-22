package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
                    "url": "https://modrinth.com/mod/appleskin"
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
