package hivens.ui.easter

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * UI-side surface to the April Fools easter-egg subsystem. Always present in
 * every build; the real chaos implementation (`RealAprilFools`) lives in the
 * `desktopAprilFoolsMain/` source dir which is only added to the desktop
 * compilation when `-PauraAprilFools=true` is on the Gradle command line.
 * Default production builds resolve to [NoOpAprilFools] via SPI --
 * `ServiceLoader.firstOrNull()` returns null and the loader falls back --
 * so no chaos code lands on the classpath.
 *
 * UI code consumes the easter-egg surface exclusively through
 * [LocalAprilFools].current; the chaos singletons (`AprilFools`,
 * `AprilFoolsProgress`, `AprilFoolsText`, `ChaosState`) are implementation
 * details of `RealAprilFools` and are not referenced from `desktopMain`.
 *
 * Severity / shape parallels [hivens.ui.puppet.PuppetServerLifecycle] --
 * same SPI gating pattern, same NoOp fallback semantics.
 */
@Stable
interface AprilFoolsLifecycle {

    // ── Calendar state ────────────────────────────────────────────────────

    /** True between April 1 and April 14 inclusive, or when [debugForceActive] flips it. */
    fun isActive(): Boolean

    /** Normalized chaos strength 0.0..1.0; 0 when inactive. */
    fun intensity(): Float

    // ── Debug overrides (read+write by [DebugPanel]) ──────────────────────

    var debugForceActive: Boolean?
    var debugIntensity: Float?

    // ── Progress regression (download bar prank) ──────────────────────────

    /** Identity when inactive; may regress by a small random step when active. */
    fun wrapProgress(downloaded: Long, total: Long): Float

    /** Resets the internal regression state; called on Downloading -> not-Downloading. */
    fun resetProgress()

    // ── Text corruption (About-screen prank) ──────────────────────────────

    fun maybeGibberish(text: String, probability: Float = 0.25f, mode: GibberishMode? = null): String

    // ── Close dialog ──────────────────────────────────────────────────────

    /**
     * Called from the window's `onCloseRequest` when chaos may be active.
     * Real impl: pops up the torturous close dialog and only invokes
     * [onActualClose] once the user wins. NoOp: invokes [onActualClose]
     * immediately.
     */
    fun requestCloseDialog(onActualClose: () -> Unit)

    // ── Card tracker (SquareServerCard manual chaos integration) ──────────

    /**
     * Server cards are not buttons, so they register manually with the chaos
     * engine. Real impl returns a live tracker; NoOp returns a stub whose
     * setters / disposer are no-ops and [ChaosCardTracker.originalVisible]
     * stays `true` (cards never go invisible because they never escape).
     */
    fun acquireCardTracker(
        id: String,
        label: String,
        widthPx: Float,
        heightPx: Float,
        onClick: () -> Unit,
    ): ChaosCardTracker

    // ── Composable surface ────────────────────────────────────────────────

    /**
     * Drop-in replacement for any Button that should participate in chaos.
     * NoOp impl renders a plain [androidx.compose.material3.Button];
     * RealAprilFools renders the chaos-aware version that the engine can
     * target (escape from layout, run/spin/flee animations).
     */
    @Composable
    fun ChaosButton(
        id: String,
        text: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        colors: ButtonColors = ButtonDefaults.buttonColors(),
    )

    /**
     * Wraps the whole application in the chaos overlay (drift, shake,
     * escaped-button rendering, close-dialog). NoOp impl renders [content]
     * directly with zero overhead.
     */
    @Composable
    fun WrapContent(
        pixelCursorState: State<Offset>,
        windowSize: IntSize,
        onRealClose: () -> Unit,
        onHideTray: (() -> Unit)?,
        content: @Composable () -> Unit,
    )

    /**
     * Developer-only debug panel that flips [debugForceActive] / [debugIntensity]
     * to test chaos behaviour out of season. NoOp impl renders nothing.
     *
     * Callers MUST gate access on [providesDebugPanel]; rendering this when
     * the impl returns false produces an invisible no-op, and any unlock
     * gesture wired to it (5-tap-Diagnostics-title) silently does nothing.
     * That mismatch was the 2.3.2 "screen jiggles but panel never shows"
     * report.
     */
    @Composable
    fun DebugPanel()

    /**
     * Whether [DebugPanel] renders real content in this build. False for
     * NoOpAprilFools (production builds without `-PauraAprilFools=true`),
     * true for RealAprilFools. Used by Diagnostics to conditionally
     * attach the 5-tap unlock gesture so users on production builds do
     * not see the title twitch on tap with nothing to show for it.
     */
    val providesDebugPanel: Boolean
}

/**
 * Opaque handle returned by [AprilFoolsLifecycle.acquireCardTracker]. The
 * card consumer reads [originalVisible] to decide whether to render itself
 * (chaos engine may have lifted the card into the overlay), pushes its
 * window-space origin every frame, refreshes the click handler when it
 * changes, and calls [release] in `onDispose`.
 */
@Stable
interface ChaosCardTracker {
    /** False when the chaos engine has yanked this card into the overlay. */
    val originalVisible: Boolean

    fun setOrigin(positionInWindow: Offset)
    fun setOnClick(onClick: () -> Unit)
    fun release()
}

/**
 * CompositionLocal that resolves the active [AprilFoolsLifecycle]. Provided
 * once at the top of `Main.kt`'s composable tree via [AprilFoolsLoader].
 * Default ([NoOpAprilFools]) is also the production fallback when the
 * SPI lookup finds no provider.
 */
val LocalAprilFools: ProvidableCompositionLocal<AprilFoolsLifecycle> =
    staticCompositionLocalOf { NoOpAprilFools }
