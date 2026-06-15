package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Persisted-enum forward-compat for [SlotContent.orientation]: an orientation a
 * newer build wrote (e.g. a future Masonry) folds to [SlotOrientation.Unknown]
 * under the production Json (coercion on) rather than silently coercing to
 * Column. Unknown renders as Column at the call sites (all `== ` / else->Column)
 * while staying distinguishable from a real Column on the wire.
 */
class SlotOrientationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `unknown orientation folds to Unknown, sibling fields survive`() {
        val content = json.decodeFromString(
            SlotContent.serializer(),
            """{"orientation":"Masonry","gridColumns":4}""",
        )
        assertEquals(SlotOrientation.Unknown, content.orientation)
        assertEquals(4, content.gridColumns)
    }

    @Test
    fun `known orientation still decodes`() {
        val content = json.decodeFromString(SlotContent.serializer(), """{"orientation":"Canvas"}""")
        assertEquals(SlotOrientation.Canvas, content.orientation)
    }

    @Test
    fun `missing orientation stays the Column default`() {
        val content = json.decodeFromString(SlotContent.serializer(), """{"gridColumns":2}""")
        assertEquals(SlotOrientation.Column, content.orientation)
    }
}
