package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val DEFAULT_LAYOUT_RESOURCE = "/widget/default-layout.json"

// Reads the kernel-bundled default LayoutGraph out of the classpath.
// Resource ships inside :widget-model so the launcher (and any other
// non-UI surface) can fetch it without depending on :client-ui.
//
// Failure modes (missing resource, malformed JSON) throw -- the
// resource is part of the jar; a failure means a broken build, not
// a runtime user condition.
/**
 * The schema version this build reads and writes.
 *
 * It lives beside the model it versions rather than beside the ladder that walks
 * it, because the bundled default is stamped with it too and the two drifted:
 * the resource said 8 while the build had moved to 9, and [DefaultLayout.load]
 * read the number and threw it away, so nothing anywhere noticed. A stamp
 * nobody checks is a comment.
 */
const val LAYOUT_SCHEMA: Int = 13

object DefaultLayout {
    fun load(json: Json = LENIENT): LayoutGraph {
        val stream = DefaultLayout::class.java.getResourceAsStream(DEFAULT_LAYOUT_RESOURCE)
            ?: error("Bundled default layout not found at classpath:$DEFAULT_LAYOUT_RESOURCE")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val envelope = json.decodeFromString<DefaultEnvelope>(text)
        // The bundled resource is part of the jar, so a mismatch is a broken build
        // and not a user condition. Failing here is the whole point: the graph
        // would otherwise decode with every retired key silently dropped, which
        // reads on screen as the default layout quietly losing its arrangement.
        check(envelope.schemaVersion == LAYOUT_SCHEMA) {
            "Bundled default layout is schema ${envelope.schemaVersion}, this build reads $LAYOUT_SCHEMA"
        }
        return envelope.graph
    }

    private val LENIENT: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class DefaultEnvelope(
        @SerialName("schema_version") val schemaVersion: Int,
        val graph: LayoutGraph,
    )
}
