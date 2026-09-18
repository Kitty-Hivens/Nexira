package hivens.ui.background

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a build does with a value a later one wrote.
 *
 * The record used to hold these two as enums. An older build meeting a constant
 * it did not have either failed the whole file, losing every other setting with
 * it, or coerced the field to a default and wrote that default back. Both spend
 * the user's choice to buy nothing: the newer build that could have read it never
 * sees it again.
 *
 * Carrying the wire value as a string costs one accessor and keeps the choice.
 */
class BackgroundSettingsWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a scale this build does not know reads as the default`() {
        val loaded = json.decodeFromString<BackgroundSettings>("""{"scaleMode":"FILL"}""")
        assertEquals(ScaleMode.COVER, loaded.scaleMode, "an unknown scale has to render as something")
    }

    @Test
    fun `and it is still there after this build writes the file back`() {
        val loaded = json.decodeFromString<BackgroundSettings>("""{"scaleMode":"FILL","darkenAmount":0.7}""")
        // Something unrelated changes, which is all it took: any write persisted
        // the whole record, and the record no longer remembered the word.
        val written = json.encodeToString(BackgroundSettings.serializer(), loaded.copy(opacity = 0.5f))

        assertTrue("\"FILL\"" in written, "the value a newer build wrote was spent by a build that could not read it")
        assertTrue("\"darkenAmount\":0.7" in written, "the rest of the record survives, as it always did")
    }

    @Test
    fun `a loop mode this build does not know survives the same way`() {
        val loaded = json.decodeFromString<BackgroundSettings>("""{"loopMode":"PingPong"}""")
        assertEquals(BackgroundLoopMode.UseCodec, loaded.loopMode)
        assertTrue("\"PingPong\"" in json.encodeToString(BackgroundSettings.serializer(), loaded.copy(enabled = true)))
    }

    @Test
    fun `choosing a mode writes the name this build means`() {
        val chosen = BackgroundSettings().withScaleMode(ScaleMode.TILE)
        assertEquals(ScaleMode.TILE, chosen.scaleMode)
        assertTrue("\"TILE\"" in json.encodeToString(BackgroundSettings.serializer(), chosen))
    }

    @Test
    fun `a file written before this field was a string still reads`() {
        // The enum serialised to the same bare name, so the format did not change
        // and no migration is owed. This pins that.
        val loaded = json.decodeFromString<BackgroundSettings>("""{"scaleMode":"CONTAIN","loopMode":"PlayOnce"}""")
        assertEquals(ScaleMode.CONTAIN, loaded.scaleMode)
        assertEquals(BackgroundLoopMode.PlayOnce, loaded.loopMode)
    }

    @Test
    fun `case and stray space are forgiven, because a person edits this file`() {
        assertEquals(ScaleMode.STRETCH, parseScaleMode(" stretch "))
        assertEquals(BackgroundLoopMode.LoopForever, parseLoopMode("loopforever"))
    }
}
