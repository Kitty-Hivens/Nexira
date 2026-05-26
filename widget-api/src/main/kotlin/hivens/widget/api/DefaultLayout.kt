package hivens.widget.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val DEFAULT_LAYOUT_RESOURCE = "/widget/default-layout.json"

// Reads the kernel-bundled default LayoutGraph out of the classpath.
// The resource lives in :widget-api so the launcher does not need a
// direct dependency on :client-ui to fetch it.
//
// Failure modes (missing resource, malformed JSON) throw -- the
// resource ships inside the jar and a failure means a broken build,
// not a runtime user condition.
object DefaultLayout {
    fun load(json: Json = LENIENT): LayoutGraph {
        val stream = DefaultLayout::class.java.getResourceAsStream(DEFAULT_LAYOUT_RESOURCE)
            ?: error("Bundled default layout not found at classpath:$DEFAULT_LAYOUT_RESOURCE")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val envelope = json.decodeFromString<DefaultEnvelope>(text)
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
