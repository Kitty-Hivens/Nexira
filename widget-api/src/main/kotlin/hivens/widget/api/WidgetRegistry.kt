package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

interface WidgetDescriptor {
    val kind: WidgetKind
    val displayName: String

    // false hides the remove affordance in the editor. Drives the
    // safety check on nav rail / auth panel widgets.
    val removable: Boolean

    // Slot ids this widget exposes for nested sub-widgets. Empty for
    // leaves. Populated by the KSP processor from @Widget(slots = ...).
    // Editor uses this to drive drop-target discovery inside container
    // chromes; the layout-graph mutator uses it (indirectly, via the
    // structural keys in WidgetInstance.children) to validate moves.
    val slots: List<SlotId>
        get() = emptyList()

    // Typed-props support, populated by the KSP processor from
    // @Widget(propsClass = ...). A null serializer means the widget is
    // propless (the default) -- the editor shows no prop affordance.
    // When non-null, the editor builds its form from
    // propsSerializer.descriptor (element names / kinds / @SerialInfo
    // annotations) and reads current values from defaultPropsJson
    // overlaid with the instance's stored props. Widgets read typed
    // values via WidgetInstance.rememberProps.
    val propsSerializer: KSerializer<*>?
        get() = null

    val defaultPropsJson: JsonObject
        get() = JsonObject(emptyMap())

    @Composable
    fun Render(instance: WidgetInstance)
}

interface WidgetRegistry {
    fun all(): Map<WidgetKind, WidgetDescriptor>
    operator fun get(kind: WidgetKind): WidgetDescriptor?
}
