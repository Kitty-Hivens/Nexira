package hivens.core.api.dto.smrt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmrtBuildDiffTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `decodes the live diff shape`() {
        // Trimmed from a live /v1/packs/Create/diff?from=0.1.2 response.
        val body = """
            {"schema_version":2,"pack_id":"Create","from":"0.1.2","to":"0.1.9","content_changed":true,
             "loader":{"from":"neoforge 21.1.186","to":"neoforge 21.1.241"},
             "mods_added":[{"filename":"New.jar","version":"1.0"}],
             "mods_removed":[{"filename":"Old.jar"}],
             "mods_updated":[{"filename":"GeckoLib.jar","version_from":"4.8.2","version_to":"4.9.2","sha1_from":"aa","sha1_to":"bb"}],
             "mods_toggled":[{"filename":"Opt.jar","default_enabled_from":true,"default_enabled_to":false}],
             "assets_added":[],"assets_removed":[],"assets_updated":[]}
        """.trimIndent()

        val diff = json.decodeFromString(SmrtBuildDiff.serializer(), body)
        assertTrue(diff.contentChanged)
        assertEquals("neoforge 21.1.241", diff.loader?.to)
        assertNull(diff.minecraft)
        assertEquals("1.0", diff.modsAdded.single().version)
        assertNull(diff.modsRemoved.single().version)
        val updated = diff.modsUpdated.single()
        assertEquals("4.8.2", updated.versionFrom)
        assertEquals("4.9.2", updated.versionTo)
        assertEquals(false, diff.modsToggled.single().defaultEnabledTo)
    }

    @Test
    fun `a relabel decodes with content unchanged and empty lists`() {
        val body = """{"schema_version":2,"pack_id":"p","from":"a","to":"b","content_changed":false,
            "mods_added":[],"mods_removed":[],"mods_updated":[],"mods_toggled":[],
            "assets_added":[],"assets_removed":[],"assets_updated":[]}"""
        val diff = json.decodeFromString(SmrtBuildDiff.serializer(), body)
        assertTrue(!diff.contentChanged)
        assertTrue(diff.modsUpdated.isEmpty())
    }
}
