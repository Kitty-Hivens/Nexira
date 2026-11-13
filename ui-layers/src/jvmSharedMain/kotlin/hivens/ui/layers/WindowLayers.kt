package hivens.ui.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
        resident.shown = true
        resident.releasing = false
        resident.tookFocus = false
        shown.remove(resident)
        shown.add(resident)
    }

    /**
     * Takes [resident] out of the order. A layer that holds the keyboard gives it back
     * first to what had it before the layer took it, while its content is still there
     * to let go of it.
     */
    internal fun hide(resident: LayerResident) {
        resident.shown = false
        if (resident.holdsFocus || resident.tookFocus) {
            resident.releasing = true
            giveFocusBack(resident)
        }
        resident.tookFocus = false
        resident.focusReturn = null
        resident.holdsFocus = false
        shown.remove(resident)
    }

    /**
     * The focus back out of a leaving layer, in order: to where the caller asked, to
     * the child of the group it was taken from that had it, and when that child is
     * gone to the first thing in that group, so the keyboard stays in the place the
     * layer was opened from. A layer that took the focus from nothing gives it back to
     * nothing.
     */
    private fun giveFocusBack(resident: LayerResident) {
        if (resident.returnFocus?.requestFocus(FocusDirection.Enter) == true) return
        val back = resident.focusReturn ?: return
        if (resident.focusReturnToChild && back.restoreFocusedChild()) return
        back.requestFocus(FocusDirection.Enter)
    }

    /** The focus group around the window's own content, which a layer that takes the focus returns it to. */
    internal val contentFocus = FocusRequester()

    /**
     * Where the keyboard focus is now, as the group a focusable layer that takes it has
     * to give it back to: a shown layer that holds it, or the window's own content.
     */
    internal fun focusHolder(except: LayerResident): FocusRequester =
        shown.firstOrNull { it !== except && it.focusable && it.holdsFocus }?.focus ?: contentFocus

    /** Whether [resident] is the topmost modal or a layer it was opened from. */
    internal fun holdsModal(resident: LayerResident): Boolean {
        var at = plan.modal
        while (at != null) {
            if (at === resident) return true
            at = at.parent
        }
        return false
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
