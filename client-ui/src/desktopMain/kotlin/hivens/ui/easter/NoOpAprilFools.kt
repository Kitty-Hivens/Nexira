package hivens.ui.easter

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Default no-op implementation of [AprilFoolsLifecycle]. Returned by
 * [AprilFoolsLoader] when the SPI scan finds no provider on the classpath --
 * which is the production-build state. Every method either returns a
 * sensible identity value or passes the content through unchanged, so a
 * caller written against [LocalAprilFools] behaves identically to the
 * old "chaos inactive" code paths.
 */
object NoOpAprilFools : AprilFoolsLifecycle {

    override fun isActive(): Boolean = false
    override fun intensity(): Float = 0f

    // Debug knobs are still writable so the DebugPanel UI itself could
    // bind to them (the panel doesn't render in NoOp, but the contract
    // stays uniform).
    override var debugForceActive: Boolean? = null
    override var debugIntensity: Float? = null

    override fun wrapProgress(downloaded: Long, total: Long): Float =
        if (total <= 0L) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)

    override fun resetProgress() = Unit

    override fun maybeGibberish(text: String, probability: Float, mode: GibberishMode?): String = text

    override fun requestCloseDialog(onActualClose: () -> Unit) {
        // No chaos dialog -- close immediately.
        onActualClose()
    }

    override fun acquireCardTracker(
        id: String,
        label: String,
        widthPx: Float,
        heightPx: Float,
        onClick: () -> Unit,
    ): ChaosCardTracker = NoOpCardTracker

    @Composable
    override fun ChaosButton(
        id: String,
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        colors: ButtonColors,
    ) {
        // Suppress M3's default ripple/state-layer indication, then
        // render a normal M3 Button. Compose Multiplatform 1.11 + M3
        // 1.11-alpha07 paint the state-layer with a shape that doesn't
        // line up with the container shape, producing the "rectangle
        // beside the rounded spot" the user flagged 2026-05-23. With
        // LocalIndication overridden to a no-op, the Button still has
        // a rest container, still receives click events, still
        // forwards hover into its internal interactionSource -- it
        // just doesn't paint a hover overlay. Cursor change to pointer
        // is the remaining hover affordance.
        CompositionLocalProvider(LocalIndication provides NoOpIndication) {
            Button(
                onClick  = onClick,
                modifier = modifier,
                enabled  = enabled,
                colors   = colors,
                shape    = MaterialTheme.shapes.small,
            ) {
                Text(text)
            }
        }
    }

    @Composable
    override fun WrapContent(
        pixelCursorState: State<Offset>,
        windowSize: IntSize,
        onRealClose: () -> Unit,
        onHideTray: (() -> Unit)?,
        content: @Composable () -> Unit,
    ) {
        content()
    }

    @Composable
    override fun DebugPanel() {
        // Production builds: nothing to render.
    }
}

/**
 * Stub [ChaosCardTracker] returned by [NoOpAprilFools.acquireCardTracker].
 * Reports the card as always visible (it never escapes), and all mutators
 * are no-ops.
 */
private object NoOpCardTracker : ChaosCardTracker {
    override val originalVisible: Boolean = true
    override fun setOrigin(positionInWindow: Offset) = Unit
    override fun setOnClick(onClick: () -> Unit) = Unit
    override fun release() = Unit
}

/**
 * Indication that paints nothing -- the node it returns is an empty
 * [Modifier.Node] that doesn't react to any interaction. Used to
 * suppress M3's default ripple/state-layer on chaos buttons where
 * Compose Multiplatform 1.11 + M3 1.11-alpha07 was painting the
 * hover overlay with a shape that didn't line up with the Button
 * container.
 *
 * Implements [IndicationNodeFactory], the non-deprecated successor
 * to the old `Indication`/`IndicationInstance` pair. Singleton
 * object so equality / hashCode are by identity.
 */
internal object NoOpIndication : IndicationNodeFactory {
    private class EmptyNode : Modifier.Node()

    override fun create(interactionSource: InteractionSource): DelegatableNode = EmptyNode()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}
