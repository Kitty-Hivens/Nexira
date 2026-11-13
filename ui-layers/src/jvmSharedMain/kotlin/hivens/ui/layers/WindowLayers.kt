package hivens.ui.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.atomic.AtomicLong

/**
 * The layers of one window: what is shown above its own content, in what order,
 * and the [dismiss] stack Escape and back close from the top.
 *
 * One per window, mounted at its root by [LayerHost]. Layers and dismiss entries
 * take their stamps from one clock, so "the most recent" means the same thing to
 * both.
 */
@Stable
class WindowLayers {
    private val clock = AtomicLong()

    internal fun tick(): Long = clock.incrementAndGet()

    private val shown = mutableStateListOf<LayerResident>()

    // Reads the flags each resident holds as state, so a layer that turns modal is
    // planned again without being shown again.
    internal val plan: LayerPlan by derivedStateOf {
        if (shown.isEmpty()) LayerPlan.Empty else planOf(shown.toList())
    }

    /** What the window draws above its content right now, bottom to top. */
    internal val residents: List<LayerResident> get() = plan.order

    /** Shows [resident] on top of its siblings: a visible edge. */
    internal fun show(resident: LayerResident) {
        resident.stamp = tick()
        shown.remove(resident)
        shown.add(resident)
    }

    internal fun hide(resident: LayerResident) {
        shown.remove(resident)
    }

    /** The window's dismiss stack. */
    val dismiss: DismissStack = DismissStack(this)
}

/** Remembers the layers of the window this composition draws. */
@Composable
fun rememberWindowLayers(): WindowLayers = remember { WindowLayers() }

internal val LocalWindowLayersProvider = staticCompositionLocalOf<WindowLayers?> { null }

/** The layers of the window around this composition, or null outside a [LayerHost]. */
val LocalWindowLayers: CompositionLocal<WindowLayers?> = LocalWindowLayersProvider

internal val LocalResidentProvider = staticCompositionLocalOf<LayerResident?> { null }

/**
 * The layer this composition is drawn in, or null in the window's own content.
 * What [DismissStack.push] takes as its owner.
 */
val LocalResident: CompositionLocal<LayerResident?> = LocalResidentProvider
