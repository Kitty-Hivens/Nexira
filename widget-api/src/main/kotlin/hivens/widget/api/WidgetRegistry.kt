package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

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

    @Composable
    fun Render(instance: WidgetInstance)
}

interface WidgetRegistry {
    fun all(): Map<WidgetKind, WidgetDescriptor>
    operator fun get(kind: WidgetKind): WidgetDescriptor?
}
