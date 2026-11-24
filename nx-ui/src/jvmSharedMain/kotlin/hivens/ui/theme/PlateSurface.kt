package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.surface.api.Surface

/** What a widget's own plate takes for a field its node leaves empty, see [hivens.look.surface.api.PlateAnswer]. */
typealias PlateSurface = hivens.look.surface.api.PlateAnswer

/**
 * What the surface axis answers here for a widget's own plate where its node leaves a field empty.
 * See [hivens.look.surface.api.SurfaceAnswers.plate].
 */
val plateSurface: PlateSurface
    @Composable @ReadOnlyComposable
    get() = Surface.plate
