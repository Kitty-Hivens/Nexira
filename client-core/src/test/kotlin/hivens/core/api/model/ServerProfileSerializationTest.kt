package hivens.core.api.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerProfileSerializationTest {

    // The configuration the launcher reads its caches with.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * The servers cache on disk was written by a build that carried the optional
     * mods as raw JSON under `optionalModsData`. The field is typed now and the
     * wire key is unchanged, so that file still has to load with its mods intact
     * -- an offline launch computes what to skip from exactly this.
     */
    @Test
    fun `a profile cached by an older build keeps its optional mods`() {
        val cached = """
            {
              "name": "Industrial",
              "version": "1.12.2",
              "ip": "127.0.0.1",
              "port": 25565,
              "assetDir": "Industrial",
              "optionalModsData": {
                "optifine": { "name": "OptiFine", "jars": ["optifine.jar"], "selected": true },
                "shaders":  { "name": "Shaders",  "jars": ["shaders.jar"],  "default": false }
              },
              "ignoreModulesList": null,
              "neoForgeArgs": null
            }
        """.trimIndent()

        val profile = json.decodeFromString(ServerProfile.serializer(), cached)

        assertEquals(setOf("optifine", "shaders"), profile.optionalMods.keys)
        assertTrue(profile.optionalMods.getValue("optifine").enabledByDefault)
        assertEquals(false, profile.optionalMods.getValue("shaders").enabledByDefault)
        assertEquals("OptiFine", profile.optionalMods.getValue("optifine").name)
    }

    /** The two fields that changed shape were never populated, so an old file has nothing to say about them. */
    @Test
    fun `the retyped fields fall back to empty on an older cache`() {
        val profile = json.decodeFromString(
            ServerProfile.serializer(),
            """{ "name": "Industrial", "ignoreModulesList": "client,asm", "neoForgeArgs": null }""",
        )
        assertEquals(emptyList(), profile.ignoredModules)
        assertNull(profile.neoForgeArgs)
    }

    @Test
    fun `a profile round-trips through the cache format`() {
        val subject = ServerProfile(
            name = "Industrial",
            version = "1.21.1",
            assetDir = "Industrial",
            ignoredModules = listOf("client", "asm"),
            neoForgeArgs = NeoForgeArgs(neoForgeVersion = "21.1.506", fmlVersion = "4.0.42"),
        )
        val restored = json.decodeFromString(
            ServerProfile.serializer(),
            json.encodeToString(ServerProfile.serializer(), subject),
        )
        assertEquals(subject, restored)
    }

    @Test
    fun `pinned loader arguments become fml pairs and blanks are left out`() {
        val args = NeoForgeArgs(neoForgeVersion = "21.1.506", fmlVersion = "  ", mcVersion = null)
        assertEquals(mapOf("neoForgeVersion" to "21.1.506"), args.asFmlArgs())
        assertEquals(emptyMap(), NeoForgeArgs().asFmlArgs())
    }
}
