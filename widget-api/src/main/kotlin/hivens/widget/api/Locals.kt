package hivens.widget.api

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

// Locals provided once near the application root. Static because the
// graph and registry references swap on whole-tree events (layout
// reload, registry registration), not on per-frame state changes;
// staticCompositionLocalOf avoids per-read snapshot tracking cost.
val LocalLayoutGraph: ProvidableCompositionLocal<LayoutGraph> =
    staticCompositionLocalOf { LayoutGraph.EMPTY }

val LocalWidgetRegistry: ProvidableCompositionLocal<WidgetRegistry> =
    staticCompositionLocalOf {
        error("LocalWidgetRegistry not provided -- did you wire WidgetRegistry in Koin?")
    }
