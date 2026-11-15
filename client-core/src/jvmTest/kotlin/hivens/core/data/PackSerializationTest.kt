package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Persisted forward-compat for [Pack] / [PackReference] under the production Json
 * (coercion on): a loader a newer build wrote folds to the [PackLoader.Unknown]
 * sentinel instead of silently coercing to a wrong real constant (the wrong
 * classpath), and an origin is an open source id kept exactly as written, so a pack
 * from a source module this build does not carry keeps its source.
 */
class PackSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Test
    fun `an origin no build declares is kept as written, sibling fields survive`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Steam","displayName":"X","mcVersion":"1.21.1","loader":"Forge"}""",
        )
        assertEquals(PackOrigin("Steam"), pack.origin)
        assertEquals("X", pack.displayName)
        assertEquals("1.21.1", pack.mcVersion)
        assertEquals(PackLoader.Forge, pack.loader)
    }

    @Test
    fun `unknown loader folds to PackLoader Unknown`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Mirror","displayName":"X","mcVersion":"1.21.1","loader":"FutureLoader"}""",
        )
        assertEquals(PackLoader.Unknown, pack.loader)
        assertEquals(PackOrigin("Mirror"), pack.origin)
    }

    @Test
    fun `known origin and loader still decode`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Modrinth","displayName":"X","mcVersion":"1.21.1","loader":"NeoForge"}""",
        )
        assertEquals(PackOrigin("Modrinth"), pack.origin)
        assertEquals(PackLoader.NeoForge, pack.loader)
    }

    @Test
    fun `cleanroom loader decodes to its own value, not Unknown`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Mirror","displayName":"X","mcVersion":"1.12.2","loader":"Cleanroom"}""",
        )
        assertEquals(PackLoader.Cleanroom, pack.loader)
    }

    @Test
    fun `lwjgl3ify loader decodes to its own value, not Unknown`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Mirror","displayName":"X","mcVersion":"1.7.10","loader":"Lwjgl3ify"}""",
        )
        assertEquals(PackLoader.Lwjgl3ify, pack.loader)
    }

    @Test
    fun `an origin no build declares in a PackReference is kept as written`() {
        val ref = json.decodeFromString(PackReference.serializer(), """{"origin":"Steam","id":"abc"}""")
        assertEquals(PackOrigin("Steam"), ref.origin)
        assertEquals("abc", ref.id)
        assertEquals("""{"origin":"Steam","id":"abc"}""", json.encodeToString(PackReference.serializer(), ref), "written back unchanged")
    }
}
