package hivens.widget.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What an instance says that its widget's declaration does not.
 *
 * Props resolve as the declaration's defaults under the instance's overrides, so
 * a stored key only ever means "this instance disagrees". Storing a key that
 * agrees costs nothing today and everything later: the value freezes at the
 * default of the day, a release that moves the declaration can no longer move
 * this instance, and the frozen value is indistinguishable from a choice
 * somebody made on purpose.
 *
 * So an edit stores the difference, and a field put back to its default drops
 * out of the record and follows the declaration again.
 */
fun propsDelta(defaults: JsonObject, values: Map<String, JsonElement>): JsonObject =
    JsonObject(values.filterNot { (key, value) -> defaults[key] == value })

/**
 * [values] with one key set, reduced to what differs from [defaults].
 *
 * The shape an editor needs: take what the instance already stored, apply the
 * one field the user just moved, and keep only what disagrees.
 */
fun propsWith(
    defaults: JsonObject,
    stored: Map<String, JsonElement>,
    key: String,
    value: JsonElement,
): JsonObject = propsDelta(defaults, stored + (key to value))
