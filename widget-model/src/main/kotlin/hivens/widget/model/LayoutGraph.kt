package hivens.widget.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WidgetInstance(
    val kind: WidgetKind,
    @SerialName("instance_id") val instanceId: String,
    val props: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SlotContent(val widgets: List<WidgetInstance> = emptyList())

@Serializable
data class SurfaceLayout(val slots: Map<SlotId, SlotContent> = emptyMap())

@Serializable
data class LayoutGraph(val surfaces: Map<SurfaceId, SurfaceLayout> = emptyMap()) {
    companion object {
        val EMPTY: LayoutGraph = LayoutGraph()
    }
}
