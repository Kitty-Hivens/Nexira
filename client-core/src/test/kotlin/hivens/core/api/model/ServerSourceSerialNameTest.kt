package hivens.core.api.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSourceSerialNameTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `each ServerSource constant has a pinned wire name`() {
        assertEquals("\"Smartycraft\"", json.encodeToString(ServerSource.serializer(), ServerSource.Smartycraft))
        assertEquals("\"Mirror\"", json.encodeToString(ServerSource.serializer(), ServerSource.Mirror))
        assertEquals(ServerSource.Smartycraft, json.decodeFromString(ServerSource.serializer(), "\"Smartycraft\""))
        assertEquals(ServerSource.Mirror, json.decodeFromString(ServerSource.serializer(), "\"Mirror\""))
    }

    @Test
    fun `ServerProfile round-trips its source`() {
        val profile = ServerProfile(name = "Industrial", source = ServerSource.Mirror)
        val back = json.decodeFromString(
            ServerProfile.serializer(),
            json.encodeToString(ServerProfile.serializer(), profile),
        )
        assertEquals(ServerSource.Mirror, back.source)
    }

    @Test
    fun `a cached profile written before the source field defaults to Smartycraft`() {
        val legacy = """{"name":"Old","version":"1.12.2","ip":"","port":0,"assetDir":"Old"}"""
        val profile = json.decodeFromString(ServerProfile.serializer(), legacy)
        assertEquals(ServerSource.Smartycraft, profile.source)
    }
}
