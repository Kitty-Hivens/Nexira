package hivens.launcher.sources

import hivens.core.data.PackOrigin
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine finds its two sources' packs by the strings their records carry, so the ids
 * must be those strings exactly: a record written by any build decodes to one of them.
 */
class EngineSourcesTest {

    @Test
    fun `the ids are the strings the records carry`() {
        assertEquals("Mirror", EngineSources.Mirror.id)
        assertEquals("Modrinth", EngineSources.Modrinth.id)
    }

    @Test
    fun `a record's origin decodes to the engine's source`() {
        assertEquals(EngineSources.Mirror, Json.decodeFromString<PackOrigin>("\"Mirror\""))
        assertEquals(EngineSources.Modrinth, Json.decodeFromString<PackOrigin>("\"Modrinth\""))
    }
}
