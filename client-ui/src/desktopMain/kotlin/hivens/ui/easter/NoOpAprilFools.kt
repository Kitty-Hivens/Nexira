package hivens.ui.easter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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

    override val providesDebugPanel: Boolean = false

    // Debug knobs are still writable so the DebugPanel UI itself could
    // bind to them (the panel doesn't render in NoOp, but the contract
    // stays uniform).
    override var debugForceActive: Boolean? = null
    override var debugIntensity: Float? = null

    override fun wrapProgress(downloaded: Long, total: Long): Float {
        // Same sentinel contract as the Real impl: NaN means "size
        // unknown but bytes are flowing -> switch to indeterminate".
        // See [AprilFoolsProgress.wrap] kdoc.
        if (total <= 0L) {
            return if (downloaded <= 0L) 0f else Float.NaN
        }
        return (downloaded.toFloat() / total).coerceIn(0f, 1f)
    }

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
