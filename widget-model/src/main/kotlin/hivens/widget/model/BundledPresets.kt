package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Arrangements that ship with the launcher, each one for the surfaces it names.
 *
 * Partial on purpose, which is what separates them from a preset somebody saves.
 * A saved preset is the whole layout and the whole look, taken together and put
 * back together. A shipped one is a way to arrange one surface, Home as a column
 * or as a shelf of spines, and applying it must leave the rails, every other
 * screen and the person's look exactly as they were. So a shipped preset carries
 * only the surfaces it is about, and applying it replaces those and nothing else.
 *
 * Read the way the bundled default layout is: part of the jar, written at the
 * schema this build reads, and a mismatch is a broken build rather than a reader's
 * file to be carried forward.
 */
object BundledPresets {

    /** Every shipped preset, in the order they are offered. */
    val IDS: List<String> = listOf("home-type", "home-column", "home-spines", "home-dock", "home-bento")

    /** The surfaces preset [id] arranges, as a graph holding only those. */
    fun load(id: String, json: Json = LENIENT): LayoutGraph {
        val path = "$RESOURCE_DIR/$id.json"
        val stream = BundledPresets::class.java.getResourceAsStream(path)
            ?: error("Bundled preset '$id' not found at classpath:$path")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val envelope = json.decodeFromString<Envelope>(text)
        check(envelope.schemaVersion == LAYOUT_SCHEMA) {
            "Bundled preset '$id' is schema ${envelope.schemaVersion}, this build reads $LAYOUT_SCHEMA"
        }
        check(envelope.id == id) { "Bundled preset file '$id' names itself '${envelope.id}'" }
        return envelope.graph
    }

    private const val RESOURCE_DIR = "/widget/presets"

    private val LENIENT: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Envelope(
        @SerialName("schema_version") val schemaVersion: Int,
        val id: String,
        val graph: LayoutGraph,
    )
}

/**
 * This graph with [preset]'s surfaces in place of its own, and every other surface
 * as it was. What applying a shipped preset is.
 */
fun LayoutGraph.withSurfacesFrom(preset: LayoutGraph): LayoutGraph =
    copy(surfaces = surfaces + preset.surfaces)
