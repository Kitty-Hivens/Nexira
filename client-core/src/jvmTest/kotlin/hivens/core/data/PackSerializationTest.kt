package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Persisted-enum forward-compat for [Pack] / [PackReference]: an origin or
 * loader a newer build wrote folds to the [Unknown] sentinel under the
 * production Json (coercion on), instead of silently coercing to a wrong real
 * constant (which would pick the wrong auth/sync path or the wrong classpath).
 */
class PackSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Test
    fun `unknown origin folds to PackOrigin Unknown, sibling fields survive`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Steam","displayName":"X","mcVersion":"1.21.1","loader":"Forge"}""",
        )
        assertEquals(PackOrigin.Unknown, pack.origin)
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
        assertEquals(PackOrigin.Mirror, pack.origin)
    }

    @Test
    fun `known origin and loader still decode`() {
        val pack = json.decodeFromString(
            Pack.serializer(),
            """{"id":"x","origin":"Modrinth","displayName":"X","mcVersion":"1.21.1","loader":"NeoForge"}""",
        )
        assertEquals(PackOrigin.Modrinth, pack.origin)
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
    fun `unknown origin in a PackReference folds to Unknown`() {
        val ref = json.decodeFromString(PackReference.serializer(), """{"origin":"Steam","id":"abc"}""")
        assertEquals(PackOrigin.Unknown, ref.origin)
        assertEquals("abc", ref.id)
    }
}
