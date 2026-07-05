package hivens.ui.threshold

import hivens.launcher.bootstrap.LauncherBootstrap
import kotlin.math.exp
import kotlin.math.min

/** Terminal state of the background boot thread. */
sealed class BootOutcome {
    class Ready(val result: LauncherBootstrap.Result) : BootOutcome()
    class Failed(val error: Throwable) : BootOutcome()
}

/**
 * The threshold bar's honest stage map. [floor] is the fraction the bar
 * targets when the stage starts; [ceiling] is how far creep may crawl while
 * the stage runs -- the bar never claims a stage it hasn't entered. Weights
 * reflect observed cost: Koin module startup dominates a steady-state boot.
 */
enum class BootStage(val floor: Float, val ceiling: Float) {
    Files(0.02f, 0.20f),
    Network(0.20f, 0.32f),
    Migration(0.32f, 0.40f),
    Modules(0.40f, 0.92f),
    Done(1f, 1f),
}

fun LauncherBootstrap.Phase.toStage(): BootStage = when (this) {
    LauncherBootstrap.Phase.Data      -> BootStage.Files
    LauncherBootstrap.Phase.Network   -> BootStage.Network
    LauncherBootstrap.Phase.Migration -> BootStage.Migration
    LauncherBootstrap.Phase.Modules   -> BootStage.Modules
}

/**
 * Smooth-fill mechanics for a discrete-stage progress bar: the displayed
 * value exponentially approaches the stage floor, then creeps toward the
 * stage ceiling while the stage runs, so the bar is never frozen and never
 * lies by more than one stage. Monotonic by construction -- real progress
 * only ever moves forward, so a displayed value that ran ahead of a
 * late-arriving target holds instead of snapping back.
 */
class BarMotion(
    private val defaultTauMs: Float = 180f,
    private val creepPerSecond: Float = 0.015f,
) {
    var displayed: Float = 0f
        private set

    /**
     * Advance by [dtMs] toward [target], creeping up to [ceiling] while
     * waiting. [tauMs] is the exponential time constant -- pass a smaller
     * value for the final sweep-to-full when boot completes early.
     */
    fun tick(dtMs: Float, target: Float, ceiling: Float, tauMs: Float = defaultTauMs): Float {
        val goal = target.coerceIn(displayed, 1f)
        val alpha = 1f - exp(-dtMs / tauMs)
        displayed += (goal - displayed) * alpha
        if (displayed >= goal - 0.0005f && displayed < ceiling) {
            displayed = min(ceiling, displayed + creepPerSecond * dtMs / 1000f)
        }
        return displayed
    }
}
