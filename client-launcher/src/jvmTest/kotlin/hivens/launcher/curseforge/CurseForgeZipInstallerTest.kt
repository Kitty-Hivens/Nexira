package hivens.launcher.curseforge

import hivens.core.api.dto.curseforge.CfManifest
import hivens.core.api.dto.curseforge.CfModLoader
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CurseForgeZipInstallerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `resolveCfLoader maps the primary loader id to a registry id and version`() {
        assertEquals("forge" to "47.2.0", resolveCfLoader(listOf(CfModLoader("forge-47.2.0", primary = true))))
        assertEquals("neoforge" to "21.1.1", resolveCfLoader(listOf(CfModLoader("neoforge-21.1.1", primary = true))))
        assertEquals("fabric" to "0.16.0", resolveCfLoader(listOf(CfModLoader("fabric-0.16.0", primary = true))))
    }

    @Test
    fun `resolveCfLoader prefers the primary entry over others`() {
        val loaders = listOf(
            CfModLoader("forge-1.0", primary = false),
            CfModLoader("neoforge-21.1.1", primary = true),
        )
        assertEquals("neoforge" to "21.1.1", resolveCfLoader(loaders))
    }

    @Test
    fun `resolveCfLoader yields vanilla for empty or unknown loaders`() {
        assertEquals(null to "", resolveCfLoader(emptyList()))
        assertEquals(null to "", resolveCfLoader(listOf(CfModLoader("rift-1.0", primary = true))))
    }

    @Test
    fun `manifest parses the fields the installer acts on`() {
        val m = json.decodeFromString(
            CfManifest.serializer(),
            """
            {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0","primary":true}]},
             "files":[{"projectID":238222,"fileID":4912000,"required":true},{"projectID":1,"fileID":2,"required":false}],
             "overrides":"overrides","name":"My Pack","version":"1.3.0","extraKeyWeIgnore":true}
            """.trimIndent(),
        )
        assertEquals("1.20.1", m.minecraft.version)
        assertEquals("forge-47.2.0", m.minecraft.modLoaders.single().id)
        assertEquals(2, m.files.size)
        assertEquals(238222L, m.files.first().projectID)
        assertEquals("My Pack", m.name)
        assertEquals("1.3.0", m.version)
    }
}
