package hivens.widget.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An instance stores its disagreements, not a copy of the declaration.
 *
 * The prop panel used to write the whole effective object on every edit, so
 * nudging one slider froze every other field at that release's default. The
 * instance then looked, to every later release and to the person reading the
 * file, exactly like somebody who had deliberately chosen all of them.
 */
class PropsDeltaTest {

    private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Int -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private val defaults = obj("size" to 12, "title" to "Clock", "seconds" to true)

    @Test
    fun `changing one field stores one field`() {
        val next = propsWith(defaults, stored = emptyMap(), key = "size", value = JsonPrimitive(18))
        assertEquals(obj("size" to 18), next, "the untouched fields were snapshotted onto the instance")
    }

    @Test
    fun `a field put back to its default stops being stored`() {
        val stored = obj("size" to 18)
        val next = propsWith(defaults, stored, key = "size", value = JsonPrimitive(12))
        assertEquals(JsonObject(emptyMap()), next, "the instance should follow the declaration again")
    }

    @Test
    fun `other disagreements survive an unrelated edit`() {
        val stored = obj("title" to "Mine")
        val next = propsWith(defaults, stored, key = "seconds", value = JsonPrimitive(false))
        assertEquals(obj("title" to "Mine", "seconds" to false), next)
    }

    @Test
    fun `a key the declaration does not have is a disagreement and is kept`() {
        // A prop removed from the widget between releases. Keeping it is what lets
        // a downgrade still find it, and it costs one key.
        val next = propsWith(defaults, stored = emptyMap(), key = "retired", value = JsonPrimitive(1))
        assertEquals(obj("retired" to 1), next)
    }

    @Test
    fun `a record that agrees with the declaration everywhere reduces to nothing`() {
        assertEquals(JsonObject(emptyMap()), propsDelta(defaults, defaults))
    }
}
