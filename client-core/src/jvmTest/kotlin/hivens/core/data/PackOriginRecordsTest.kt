package hivens.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every origin a pack record was ever written with, decoded as the open source id and
 * written back the way the closed list wrote it, so a record of any age opens on this
 * build and a record this build writes opens on an older one.
 *
 * The strings are the enum names the closed list wrote, `Unknown` among them: an older
 * build wrote that where it read an origin it did not know.
 */
class PackOriginRecordsTest {

    /** The launcher's own record Json, as the engine's graph builds it. */
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val written = listOf("Smartycraft", "Mirror", "Modrinth", "Local", "Unknown")

    /** A record as the launcher stores it, its own source and the one it was detached from. */
    private fun record(origin: String, forkedFrom: String?) = """
        {
          "id": "industrial",
          "packRef": {"origin": "$origin", "id": "Industrial", "version": "0.3.1"},
          "displayName": "Industrial",
          "instanceDirName": "Industrial",
          "createdAtEpoch": 1767225600,
          "lastPlayedEpochOrZero": 1767312000,
          "playtimeSeconds": 3600,
          "pinnedPackVersion": "0.3.1",
          ${if (forkedFrom != null) """"forkedFrom": {"origin": "$forkedFrom", "id": "upstream", "version": null},""" else ""}
          "notes": "",
          "followLatest": true
        }
    """.trimIndent()

    @Test
    fun `every origin a record was written with decodes to its own id`() {
        written.forEach { origin ->
            val decoded = json.decodeFromString(PackInstance.serializer(), record(origin, forkedFrom = null))
            assertEquals(origin, decoded.packRef.origin.id)
            assertEquals("Industrial", decoded.packRef.id, "the rest of the record is read as before")
            assertEquals(3600L, decoded.playtimeSeconds)
        }
    }

    @Test
    fun `the core's two meanings are the strings the records carry`() {
        assertEquals(PackOrigin.Local, json.decodeFromString(PackInstance.serializer(), record("Local", null)).packRef.origin)
        assertEquals(PackOrigin.Smartycraft, json.decodeFromString(PackInstance.serializer(), record("Smartycraft", null)).packRef.origin)
    }

    @Test
    fun `a record is written back with the origin strings it was read with`() {
        written.forEach { origin ->
            written.forEach { from ->
                val decoded = json.decodeFromString(PackInstance.serializer(), record(origin, from))
                val again = json.parseToJsonElement(json.encodeToString(PackInstance.serializer(), decoded)).jsonObject
                assertEquals(JsonPrimitive(origin), again.getValue("packRef").jsonObject.getValue("origin"))
                assertEquals(from, (again.getValue("forkedFrom") as JsonObject).getValue("origin").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `a source this build does not carry keeps its id through a read and a write`() {
        val decoded = json.decodeFromString(PackInstance.serializer(), record("Curseforge", "Curseforge"))
        assertEquals(PackOrigin("Curseforge"), decoded.packRef.origin)
        assertEquals(PackOrigin("Curseforge"), decoded.forkedFrom?.origin)
        val again = json.decodeFromString(PackInstance.serializer(), json.encodeToString(PackInstance.serializer(), decoded))
        assertEquals(decoded, again)
    }

    @Test
    fun `a pack's own record keeps its origin string`() {
        written.forEach { origin ->
            val text = """{"id":"x","origin":"$origin","displayName":"X","mcVersion":"1.21.1","loader":"Forge"}"""
            val pack = json.decodeFromString(Pack.serializer(), text)
            assertEquals(origin, pack.origin.id)
            assertEquals(origin, json.parseToJsonElement(json.encodeToString(Pack.serializer(), pack)).jsonObject.getValue("origin").jsonPrimitive.content)
        }
    }
}
