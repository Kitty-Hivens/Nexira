package hivens.ui.easter

import androidx.compose.material3.ButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Real [AprilFoolsLifecycle] implementation. Thin facade over the chaos
 * singletons that have always done the work (`AprilFools` calendar logic,
 * `AprilFoolsProgress` regression, `AprilFoolsText` corruption,
 * `ChaosState` overlay state, plus the top-level `AprilFoolsButton` /
 * `AprilFoolsWrapper` / `AprilFoolsDebugPanel` composables).
 *
 * In B3 sub-batch 12.1 this class + every file it delegates to still
 * lives in `desktopMain/`. The next sub-batch (12.2) `git mv`s the
 * implementation files plus this facade to `desktopAprilFoolsMain/` and
 * adds the SPI descriptor at
 * `META-INF/services/hivens.ui.easter.AprilFoolsLifecycle`. Production
 * builds will then resolve to [NoOpAprilFools] and ship none of the
 * chaos code.
 *
 * Must have a public no-arg constructor so `ServiceLoader` can
 * instantiate it.
 */
class RealAprilFools : AprilFoolsLifecycle {

    override fun isActive(): Boolean = AprilFools.isActive()
    override fun intensity(): Float = AprilFools.intensity()

    override var debugForceActive: Boolean?
        get() = AprilFools.debugForceActive
        set(value) { AprilFools.debugForceActive = value }

    override var debugIntensity: Float?
        get() = AprilFools.debugIntensity
        set(value) { AprilFools.debugIntensity = value }

    override fun wrapProgress(downloaded: Long, total: Long): Float =
        AprilFoolsProgress.wrap(downloaded, total)

    override fun resetProgress() = AprilFoolsProgress.reset()

    override fun maybeGibberish(text: String, probability: Float, mode: GibberishMode?): String =
        AprilFoolsText.maybeGibberish(text, probability, mode)

    override fun requestCloseDialog(onActualClose: () -> Unit) {
        if (AprilFools.isActive()) {
            // Flip the chaos state; AprilFoolsCloseDialog inside WrapContent
            // will pick it up on the next recomposition and run the torturous
            // escape-the-close-button game.
            ChaosState.showCloseDialog = true
        } else {
            // Out of season -- nothing to render, just close.
            onActualClose()
        }
    }

    override fun acquireCardTracker(
        id: String,
        label: String,
        widthPx: Float,
        heightPx: Float,
        onClick: () -> Unit,
    ): ChaosCardTracker = RealCardTracker(id, label, widthPx, heightPx, onClick)

    @Composable
    override fun ChaosButton(
        id: String,
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        colors: ButtonColors,
    ) {
        AprilFoolsButton(
            id       = id,
            text     = text,
            onClick  = onClick,
            modifier = modifier,
            enabled  = enabled,
            colors   = colors,
        )
    }

    @Composable
    override fun WrapContent(
        pixelCursorState: State<Offset>,
        windowSize: IntSize,
        onRealClose: () -> Unit,
        onHideTray: (() -> Unit)?,
        content: @Composable () -> Unit,
    ) {
        AprilFoolsWrapper(
            pixelCursorState = pixelCursorState,
            windowSize       = windowSize,
            onRealClose      = onRealClose,
            onHideTray       = onHideTray,
            content          = content,
        )
    }

    @Composable
    override fun DebugPanel() {
        AprilFoolsDebugPanel()
    }
}

/**
 * Real card tracker -- delegates to the existing [FloatingButton] +
 * [ChaosState] machinery. Registration happens only when chaos is active
 * (mirrors the prior `if (AprilFools.isActive())` guards in
 * `SquareServerCard`); during a non-April session the tracker still
 * answers `originalVisible = true` and the card renders normally.
 */
private class RealCardTracker(
    id: String,
    label: String,
    widthPx: Float,
    heightPx: Float,
    onClick: () -> Unit,
) : ChaosCardTracker {

    private val active = AprilFools.isActive()

    private val btn: FloatingButton? = if (active) {
        ChaosState.find(id) ?: FloatingButton(
            id       = id,
            label    = label,
            widthPx  = widthPx,
            heightPx = heightPx,
            onClick  = onClick,
        ).also { ChaosState.register(it) }
    } else {
        null
    }

    override val originalVisible: Boolean
        get() = btn?.originalVisible ?: true

    override fun setOrigin(positionInWindow: Offset) {
        btn?.originPx = positionInWindow
    }

    override fun setOnClick(onClick: () -> Unit) {
        btn?.onClick = onClick
    }

    override fun release() {
        btn?.let { ChaosState.unregister(it.id) }
    }
}
