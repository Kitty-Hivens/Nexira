package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

interface WidgetDescriptor {
    val kind: WidgetKind
    val displayName: String

    @Composable
    fun Render(instance: WidgetInstance)
}

interface WidgetRegistry {
    fun all(): Map<WidgetKind, WidgetDescriptor>
    operator fun get(kind: WidgetKind): WidgetDescriptor?
}
