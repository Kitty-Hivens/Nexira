package hivens.ui.easter

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import java.time.LocalDate

// ─── Calendar logic ───────────────────────────────────────────────────────────

object AprilFools {
    // ── Debug overrides ───────────────────────────────────────────────────────
    // Set via the hidden debug panel in SettingsScreen.
    // Both are null by default -- production behavior unchanged.

    /** When non-null, forces isActive() = true regardless of date */
    var debugForceActive: Boolean? by mutableStateOf(null)

    /** When non-null (0.0..1.0), overrides intensity() calculation */
    var debugIntensity: Float? by mutableStateOf(null)

    // ── Style coupling ────────────────────────────────────────────────────────
    // The chaos subsystem was designed under NxTheme assumptions
    // (rounded corners, glass surfaces, lively animations). With the
    // UiStyle axis live, chaos should follow the active style instead of
    // sitting outside it. AppShell pushes these values whenever uiStyle
    // changes; the engine reads styleAnimationMultiplier per tween, and
    // components read useFlatSurface to drop elevation under Brut.

    /** Multiplier on every chaos animation duration. 1.0 = base motion,
     *  0.0 = instant snap (Brut). AppShell mirrors style.animationMultiplier
     *  here. */
    var styleAnimationMultiplier: Float by mutableStateOf(1f)

    /** When true, chaos surfaces render without tonal / button elevation --
     *  matches the active style's flat surface treatment (Brut). AppShell
     *  mirrors style.cardSurface == Flat here. */
    var useFlatSurface: Boolean by mutableStateOf(false)

    /** Scale a base ms duration by the active style multiplier. Coerces to
     *  >= 1ms because Compose animation specs reject zero. */
    fun scaledDuration(baseMs: Int): Int =
        (baseMs * styleAnimationMultiplier).toInt().coerceAtLeast(1)

    // ── Calendar logic ────────────────────────────────────────────────────────

    private fun year() = LocalDate.now().year

    fun isActive(): Boolean {
        debugForceActive?.let { return it }
        val now   = LocalDate.now()
        val start = LocalDate.of(year(), 4, 1)
        val end   = LocalDate.of(year(), 4, 14)
        return !now.isBefore(start) && !now.isAfter(end)
    }

    /**
     * Normalized chaos intensity: 0.07 on day 1, 1.0 on day 14.
     * Used as a multiplier everywhere -- crank it to 1.0 for local testing.
     */
    fun intensity(): Float {
        if (!isActive()) return 0f
        debugIntensity?.let { return it.coerceIn(0f, 1f) }
        val day = (LocalDate.now().dayOfMonth - 1).coerceIn(0, 13)
        return (day + 1) / 14f
    }

    /** How long to wait between chaos events, in ms */
    fun intervalMs(): Long {
        val t = intensity()
        return (16_000L - t * 14_000L).toLong().coerceAtLeast(2_000L)
    }

    /** How many chaos events may be active simultaneously */
    fun maxParallel(): Int = when {
        intensity() < 0.30f -> 1
        intensity() < 0.65f -> 2
        else                -> 3
    }
}

// ─── CompositionLocals ────────────────────────────────────────────────────────

/** Pixel cursor position inside the app window, updated every frame */
val LocalCursorPx: ProvidableCompositionLocal<State<Offset>> =
    compositionLocalOf { mutableStateOf(Offset.Zero) }

/** Window size in pixels */
val LocalWindowPx: ProvidableCompositionLocal<IntSize> =
    compositionLocalOf { IntSize(1920, 1080) }

// ─── Chaos phase ─────────────────────────────────────────────────────────────

enum class ChaosPhase {
    /** Sitting normally inside the layout */
    IDLE,

    /** Stuck to the cursor like glue, following it around */
    CURSOR_STICKY,

    /** Lying at a random spot on screen, possibly sideways / upside-down */
    RESTING,

    /** Wobbling like it had too much to drink */
    DRUNK_WOBBLE,

    /** Has grown pixel legs and is walking across the screen */
    LEGS_WALKING,

    /** Shrinks to nothing, pops up somewhere else at wrong size */
    TELEPORTING,

    /** Spinning in place, then flings itself to a corner */
    SPINNING,

    /**
     * Runs away from cursor on hover.
     * The original button is still visible but keeps fleeing --
     * no overlay clone for this one, pure local offset.
     */
    FLEEING,

    /** Translucent ghost copy that floats away and fades */
    GHOST,

    /** Animating back to its home position */
    RETURNING,
}

// ─── Floating button state ────────────────────────────────────────────────────

/**
 * Represents a registered button that can be yanked out of the layout
 * and rendered in the global chaos overlay.
 *
 * All animated properties use [Animatable] so the engine can drive them
 * from plain coroutines without touching the Compose tree.
 */
class FloatingButton(
    val id: String,
    val label: String,
    /** Width in layout pixels (from onGloballyPositioned) */
    val widthPx: Float,
    /** Height in layout pixels */
    val heightPx: Float,
    var onClick: () -> Unit = {},
) {
    /** Top-left position of the real button in window space (updated each frame) */
    var originPx by mutableStateOf(Offset.Zero)

    /** When false, the original Button renders as alpha=0 (escaped to overlay) */
    var originalVisible by mutableStateOf(true)

    var phase by mutableStateOf(ChaosPhase.IDLE)

    // ── Overlay transform properties ──────────────────────────────────────────
    val overlayX     = Animatable(0f)
    val overlayY     = Animatable(0f)
    val overlayRot   = Animatable(0f)
    val overlayScale = Animatable(1f)
    val overlayAlpha = Animatable(1f)

    // ── Legs state ────────────────────────────────────────────────────────────
    var hasLegs  by mutableStateOf(false)
    var legCycle by mutableStateOf(0f)   // 0..1 walking cycle

    fun isEscaped() = phase !in setOf(ChaosPhase.IDLE, ChaosPhase.FLEEING)

    /** Snap overlay transform to match current origin -- call before escaping. */
    suspend fun snapToOrigin() {
        overlayX.snapTo(originPx.x)
        overlayY.snapTo(originPx.y)
        overlayRot.snapTo(0f)
        overlayScale.snapTo(1f)
        overlayAlpha.snapTo(1f)
    }
}

// ─── Global overlay state ─────────────────────────────────────────────────────

/**
 * Singleton holding every piece of global chaos state.
 * Composed into the UI via [AprilFoolsWrapper].
 */
object ChaosState {

    /** All buttons opted into chaos */
    val buttons: SnapshotStateList<FloatingButton> = mutableStateListOf()

    /** Ghost clones (separate list so they never get targeted by the engine) */
    val ghosts: SnapshotStateList<FloatingButton> = mutableStateListOf()

    // ── Screen-wide effects ────────────────────────────────────────────────────

    /** Applied to the root Box via graphicsLayer -- shake/earthquake effect */
    var shakeOffset by mutableStateOf(Offset.Zero)

    /** Global tilt angle in degrees -- drifts slowly */
    var globalTiltDeg by mutableStateOf(0f)

    // ── Close dialog ──────────────────────────────────────────────────────────

    var showCloseDialog   by mutableStateOf(false)
    var closeButtonEscapes by mutableStateOf(0)

    // ── Registration ──────────────────────────────────────────────────────────

    fun register(btn: FloatingButton) {
        if (buttons.none { it.id == btn.id }) buttons.add(btn)
    }

    fun unregister(id: String) {
        buttons.removeAll { it.id == id }
    }

    fun find(id: String): FloatingButton? = buttons.find { it.id == id }

    fun randomIdle(): FloatingButton? =
        buttons.filter { it.phase == ChaosPhase.IDLE }.randomOrNull()

    fun activeCount(): Int =
        buttons.count { it.phase != ChaosPhase.IDLE }

    fun clean() {
        buttons.clear()
        ghosts.clear()
        shakeOffset = Offset.Zero
        globalTiltDeg = 0f
        showCloseDialog = false
        closeButtonEscapes = 0
    }
}
