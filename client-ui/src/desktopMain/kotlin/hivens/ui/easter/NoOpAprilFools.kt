package hivens.ui.easter

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Text
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
        // No chaos dialog -- close immediately. Mirrors what the old
        // `if (AprilFools.isActive()) showDialog else exitApplication()`
        // call site used to do explicitly.
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
        Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
            Text(text)
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
