package hivens.widget.api

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SurfaceId(val value: String)

@JvmInline
@Serializable
value class SlotId(val value: String)

@JvmInline
@Serializable
value class WidgetKind(val value: String)
