package hivens.ui.easter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * The chaos event engine. Call [run] from a [LaunchedEffect] inside [AprilFoolsWrapper].
 *
 * Architecture:
 *  - A tilt-drift coroutine runs continuously, updating [ChaosState.globalTiltDeg].
 *  - A shake coroutine runs for screen earthquakes, updating [ChaosState.shakeOffset].
 *  - The main event loop picks random events at [AprilFools.intervalMs] intervals
 *    and launches them as child coroutines.
 *  - Each event drives [FloatingButton] Animatable properties directly —
 *    no Compose calls, pure coroutine code.
 */
object AprilFoolsEngine {

    // ─── Entry point ──────────────────────────────────────────────────────────

    suspend fun run(
        scope: CoroutineScope,
        cursorState: () -> Offset,
        windowSize: () -> IntSize,
    ) = coroutineScope {
        launch { runTiltDrift() }
        launch { runEventLoop(scope, cursorState, windowSize) }
    }

    // ─── Tilt drift ───────────────────────────────────────────────────────────
    // Slowly tilts the entire UI back and forth — more extreme each day.

    private suspend fun runTiltDrift() {
        while (true) {
            val maxTilt   = AprilFools.intensity() * 5f
            val target    = (Random.nextFloat() * 2f - 1f) * maxTilt
            val current   = ChaosState.globalTiltDeg
            val holdDelay = (20_000L - (AprilFools.intensity() * 16_000).toLong())
                .coerceAtLeast(3_500L)

            // Smooth lerp to new tilt over 2 seconds
            val steps = 120
            repeat(steps) { i ->
                val t = i / steps.toFloat()
                ChaosState.globalTiltDeg = lerp(current, target, easeInOut(t))
                delay(16L)
            }
            delay(holdDelay)
        }
    }

    // ─── Main event loop ──────────────────────────────────────────────────────

    private suspend fun runEventLoop(
        scope: CoroutineScope,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        while (true) {
            delay(AprilFools.intervalMs())
            if (!AprilFools.isActive()) break

            // Spawn up to maxParallel events simultaneously
            val slots = AprilFools.maxParallel() - ChaosState.activeCount()
            if (slots <= 0) continue

            repeat(slots.coerceAtMost(2)) {
                val btn = ChaosState.randomIdle() ?: return@repeat
                val event = pickEvent()
                scope.launch {
                    runCatching { event(btn, cursor, ws) }
                        .onFailure { /* event coroutines should never crash the app */ }
                }
                delay(Random.nextLong(300L, 800L)) // stagger multi-event starts
            }
        }
    }

    // ─── Event pool ───────────────────────────────────────────────────────────

    private typealias Event = suspend (FloatingButton, () -> Offset, () -> IntSize) -> Unit

    private fun pickEvent(): Event {
        val t = AprilFools.intensity()

        // (event, weight) — higher weight = more likely.
        // Weirder events unlock as intensity grows.
        val pool = buildList<Pair<Event, Float>> {
            add(::eventCursorSticky  to 1.2f)
            add(::eventDrunkWobble   to 1.0f)
            add(::eventFleeing       to 0.9f)
            add(::eventSpinAndFly    to 0.8f)
            if (t > 0.15f) add(::eventTeleport    to 1.0f)
            if (t > 0.25f) add(::eventGhostClone  to 0.7f)
            if (t > 0.40f) add(::eventLegsWalk    to 0.8f)
            if (t > 0.55f) add(::eventEarthquake  to 0.5f)
            if (t > 0.70f) add(::eventMassEscape  to 0.4f)
        }

        var r = Random.nextFloat() * pool.sumOf { it.second.toDouble() }.toFloat()
        for ((event, w) in pool) {
            r -= w
            if (r <= 0f) return event
        }
        return pool.first().first
    }

    // ─── EVENT: Cursor sticky ─────────────────────────────────────────────────
    // Button breaks free, magnetically attaches to cursor, then flings away.

    private suspend fun eventCursorSticky(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        btn.snapToOrigin()
        btn.phase = ChaosPhase.CURSOR_STICKY
        btn.originalVisible = false

        // Follow cursor with slight lag for 2.5–5 seconds
        val stickEnd = System.currentTimeMillis() + Random.nextLong(2_500L, 5_000L)
        while (System.currentTimeMillis() < stickEnd) {
            val c = cursor()
            // Offset so it's not dead-center on cursor — feels more natural/creepy
            btn.overlayX.animateTo(c.x - btn.widthPx / 2f + 10f, tween(90, easing = LinearEasing))
            btn.overlayY.animateTo(c.y - btn.heightPx / 2f + 5f,  tween(90, easing = LinearEasing))
        }

        // Fling to a random spot — fast and dramatic
        val w = ws()
        val restX   = Random.nextFloat() * (w.width  - btn.widthPx  - 30f) + 15f
        val restY   = Random.nextFloat() * (w.height - btn.heightPx - 30f) + 15f
        val restRot = (Random.nextFloat() * 2f - 1f) * 175f  // possibly near upside-down

        btn.phase = ChaosPhase.RESTING
        coroutineScope {
            launch { btn.overlayX.animateTo(restX,   tween(550, easing = FastOutSlowInEasing)) }
            launch { btn.overlayY.animateTo(restY,   tween(550, easing = FastOutSlowInEasing)) }
            launch { btn.overlayRot.animateTo(restRot, tween(550)) }
            launch { btn.overlayScale.animateTo(0.9f + Random.nextFloat() * 0.4f, tween(300)) }
        }

        delay(Random.nextLong(7_000L, 16_000L))
        returnToOrigin(btn)
    }

    // ─── EVENT: Drunk wobble ──────────────────────────────────────────────────
    // Button stays roughly in place but sways drunkenly with compound sine motion.

    private suspend fun eventDrunkWobble(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        btn.snapToOrigin()
        btn.phase = ChaosPhase.DRUNK_WOBBLE
        btn.originalVisible = false

        val duration = Random.nextLong(5_000L, 10_000L)
        val end      = System.currentTimeMillis() + duration
        var t        = Random.nextFloat() * 10f  // randomize phase so each button looks different

        while (System.currentTimeMillis() < end) {
            t += 0.035f
            // Multiple sine waves summed — creates irregular, natural-looking wobble
            val dx  = sin(t * 2.1f) * 9f + sin(t * 4.3f) * 4f + cos(t * 1.1f) * 3f
            val dy  = cos(t * 1.7f) * 6f + sin(t * 3.2f) * 2f
            val rot = sin(t * 1.4f) * 9f + cos(t * 2.8f) * 4f

            btn.overlayX.snapTo(btn.originPx.x + dx)
            btn.overlayY.snapTo(btn.originPx.y + dy)
            btn.overlayRot.snapTo(rot)
            delay(16L)
        }

        returnToOrigin(btn)
    }

    // ─── EVENT: Fleeing ───────────────────────────────────────────────────────
    // Button runs away from cursor whenever you get close.
    // It stays "visible" in the overlay and physically avoids the pointer.

    private suspend fun eventFleeing(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        btn.snapToOrigin()
        btn.phase = ChaosPhase.FLEEING
        btn.originalVisible = false

        val duration = Random.nextLong(9_000L, 16_000L)
        val end      = System.currentTimeMillis() + duration
        val w        = ws()
        val fleeRadius = 180f

        while (System.currentTimeMillis() < end) {
            val c     = cursor()
            val bx    = btn.overlayX.value + btn.widthPx  / 2f
            val by    = btn.overlayY.value + btn.heightPx / 2f
            val dx    = bx - c.x
            val dy    = by - c.y
            val dist  = hypot(dx, dy)

            if (dist < fleeRadius && dist > 0.5f) {
                val spd   = (fleeRadius - dist) / fleeRadius * 18f + 3f
                val nx    = dx / dist
                val ny    = dy / dist
                val newX  = (btn.overlayX.value + nx * spd).coerceIn(0f, w.width  - btn.widthPx)
                val newY  = (btn.overlayY.value + ny * spd).coerceIn(0f, w.height - btn.heightPx)

                // Lean in the direction of movement
                val lean  = (atan2(ny, nx) * (180f / PI.toFloat())).coerceIn(-30f, 30f)
                btn.overlayX.animateTo(newX, tween(40, easing = LinearEasing))
                btn.overlayY.animateTo(newY, tween(40, easing = LinearEasing))
                btn.overlayRot.animateTo(lean, tween(80))
            } else {
                delay(30L)
            }
        }

        returnToOrigin(btn)
    }

    // ─── EVENT: Spin and fly ──────────────────────────────────────────────────
    // Spins N rotations in place, then rockets to a corner.

    private suspend fun eventSpinAndFly(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        btn.snapToOrigin()
        btn.phase = ChaosPhase.SPINNING
        btn.originalVisible = false

        val spins = Random.nextInt(3, 8)
        val rpm   = tween<Float>(700 + spins * 80, easing = FastOutSlowInEasing)

        coroutineScope {
            launch { btn.overlayRot.animateTo(360f * spins, rpm) }
            launch {
                delay(200)
                btn.overlayScale.animateTo(1.25f, tween(300))
            }
        }

        // Fly to a random corner
        val w  = ws()
        val cx = if (btn.originPx.x < w.width / 2f) w.width  - btn.widthPx  - 12f else 12f
        val cy = if (btn.originPx.y < w.height/ 2f) w.height - btn.heightPx - 12f else 12f

        btn.phase = ChaosPhase.RESTING
        coroutineScope {
            launch { btn.overlayX.animateTo(cx, tween(380, easing = FastOutSlowInEasing)) }
            launch { btn.overlayY.animateTo(cy, tween(380, easing = FastOutSlowInEasing)) }
            launch { btn.overlayRot.animateTo(btn.overlayRot.value + Random.nextFloat() * 90f - 45f, tween(380)) }
            launch { btn.overlayScale.animateTo(1f, tween(300)) }
        }

        delay(Random.nextLong(6_000L, 14_000L))
        returnToOrigin(btn)
    }

    // ─── EVENT: Teleport ─────────────────────────────────────────────────────
    // Shrinks to nothing, reappears somewhere random at the wrong size.

    private suspend fun eventTeleport(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        btn.snapToOrigin()
        btn.phase = ChaosPhase.TELEPORTING
        btn.originalVisible = false

        // Shrink + spin out
        coroutineScope {
            launch { btn.overlayScale.animateTo(0f,    tween(280, easing = FastOutSlowInEasing)) }
            launch { btn.overlayRot.animateTo(360f,    tween(280)) }
            launch { btn.overlayAlpha.animateTo(0f,    tween(200)) }
        }

        // Teleport to random position
        val w = ws()
        btn.overlayX.snapTo(Random.nextFloat() * (w.width  - btn.widthPx  - 30f) + 15f)
        btn.overlayY.snapTo(Random.nextFloat() * (w.height - btn.heightPx - 30f) + 15f)
        btn.overlayRot.snapTo((Random.nextFloat() * 2f - 1f) * 25f)
        btn.overlayAlpha.snapTo(0f)

        // Pop in at a weird scale (could be huge or tiny)
        val weirdScale = if (Random.nextBoolean()) {
            0.45f + Random.nextFloat() * 0.35f    // tiny
        } else {
            1.4f + Random.nextFloat() * 0.7f       // oversized
        }

        btn.overlayAlpha.animateTo(1f, tween(80))
        btn.overlayScale.animateTo(weirdScale * 1.3f, tween(160, easing = FastOutSlowInEasing))
        btn.overlayScale.animateTo(weirdScale,         tween(100, easing = LinearEasing))

        btn.phase = ChaosPhase.RESTING
        delay(Random.nextLong(8_000L, 15_000L))
        returnToOrigin(btn)
    }

    // ─── EVENT: Ghost clone ───────────────────────────────────────────────────
    // A semi-transparent ghost copy floats upward and fades — original stays put.

    private suspend fun eventGhostClone(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        val ghost = FloatingButton(
            id      = "ghost_${btn.id}_${System.currentTimeMillis()}",
            label   = btn.label,
            widthPx = btn.widthPx,
            heightPx= btn.heightPx,
        )
        ghost.originPx = btn.originPx
        ghost.overlayX.snapTo(btn.originPx.x)
        ghost.overlayY.snapTo(btn.originPx.y)
        ghost.overlayAlpha.snapTo(0.55f)
        ghost.overlayScale.snapTo(1.05f)
        ghost.overlayRot.snapTo((Random.nextFloat() * 2f - 1f) * 12f)
        ghost.phase = ChaosPhase.GHOST

        ChaosState.ghosts.add(ghost)

        val drift = (Random.nextFloat() * 2f - 1f) * 120f
        coroutineScope {
            launch {
                ghost.overlayY.animateTo(
                    ghost.overlayY.value - Random.nextFloat() * 280f - 80f,
                    tween(3_200, easing = LinearEasing)
                )
            }
            launch {
                ghost.overlayX.animateTo(
                    ghost.overlayX.value + drift,
                    tween(3_200, easing = LinearEasing)
                )
            }
            launch {
                ghost.overlayRot.animateTo(ghost.overlayRot.value + (Random.nextFloat() * 2f - 1f) * 30f, tween(3_200))
            }
            launch {
                delay(1_600)
                ghost.overlayAlpha.animateTo(0f, tween(1_600))
            }
        }

        ChaosState.ghosts.remove(ghost)
    }

    // ─── EVENT: Legs walk ─────────────────────────────────────────────────────
    // Pixel stick-legs sprout and the button walks across the screen.

    private suspend fun eventLegsWalk(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        btn.snapToOrigin()
        btn.phase    = ChaosPhase.LEGS_WALKING
        btn.hasLegs  = true
        btn.originalVisible = false

        val w           = ws()
        val walkDuration= Random.nextLong(7_000L, 13_000L)
        val end         = System.currentTimeMillis() + walkDuration
        val speed       = 1.8f + AprilFools.intensity() * 2.5f
        val dirSign     = if (Random.nextBoolean()) 1f else -1f

        while (System.currentTimeMillis() < end) {
            val newX = (btn.overlayX.value + speed * dirSign)
                .coerceIn(0f, w.width - btn.widthPx)
            val bobY = sin(btn.legCycle * 2f * PI.toFloat()) * 3.5f

            btn.overlayX.snapTo(newX)
            btn.overlayY.snapTo(btn.originPx.y + bobY)
            btn.legCycle = (btn.legCycle + 0.035f) % 1f
            delay(16L)

            // Reached edge — stop early
            if (newX <= 0f || newX >= w.width - btn.widthPx) break
        }

        btn.hasLegs  = false
        btn.phase    = ChaosPhase.RESTING
        delay(Random.nextLong(4_000L, 9_000L))
        returnToOrigin(btn)
    }

    // ─── EVENT: Earthquake ────────────────────────────────────────────────────
    // Entire UI shakes violently for under a second.

    private suspend fun eventEarthquake(
        btn: FloatingButton,          // Unused — earthquakes are screen-wide
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        val amplitude = AprilFools.intensity() * 20f + 5f
        val duration  = Random.nextLong(350L, 800L)
        val end       = System.currentTimeMillis() + duration

        while (System.currentTimeMillis() < end) {
            ChaosState.shakeOffset = Offset(
                x = (Random.nextFloat() * 2f - 1f) * amplitude,
                y = (Random.nextFloat() * 2f - 1f) * amplitude * 0.55f,
            )
            delay(16L)
        }
        ChaosState.shakeOffset = Offset.Zero
    }

    // ─── EVENT: Mass escape ───────────────────────────────────────────────────
    // Every idle button escapes simultaneously (only fires on high intensity days).

    private suspend fun eventMassEscape(
        btn: FloatingButton,
        cursor: () -> Offset,
        ws: () -> IntSize,
    ) {
        val idles = ChaosState.buttons.filter { it.phase == ChaosPhase.IDLE }
        if (idles.size < 2) return

        coroutineScope {
            idles.forEach { b ->
                launch { eventSpinAndFly(b, cursor, ws) }
                delay(120L)
            }
        }
    }

    // ─── Shared helper: smooth return to origin ───────────────────────────────

    private suspend fun returnToOrigin(btn: FloatingButton) {
        btn.phase = ChaosPhase.RETURNING
        coroutineScope {
            launch { btn.overlayX.animateTo(btn.originPx.x,   tween(480, easing = FastOutSlowInEasing)) }
            launch { btn.overlayY.animateTo(btn.originPx.y,   tween(480, easing = FastOutSlowInEasing)) }
            launch { btn.overlayRot.animateTo(0f,              tween(480)) }
            launch { btn.overlayScale.animateTo(1f,            tween(320)) }
            launch { btn.overlayAlpha.animateTo(1f,            tween(200)) }
        }
        btn.originalVisible = true
        btn.phase = ChaosPhase.IDLE
    }

    // ─── Math helpers ─────────────────────────────────────────────────────────

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeInOut(t: Float): Float =
        if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t

    // ─── Public trigger methods (debug panel access) ──────────────────────────────

    internal suspend fun triggerCursorSticky(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventCursorSticky(btn, cursor, ws)

    internal suspend fun triggerDrunkWobble(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventDrunkWobble(btn, cursor, ws)

    internal suspend fun triggerFleeing(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventFleeing(btn, cursor, ws)

    internal suspend fun triggerSpinAndFly(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventSpinAndFly(btn, cursor, ws)

    internal suspend fun triggerTeleport(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventTeleport(btn, cursor, ws)

    internal suspend fun triggerGhostClone(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventGhostClone(btn, cursor, ws)

    internal suspend fun triggerLegsWalk(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventLegsWalk(btn, cursor, ws)

    internal suspend fun triggerEarthquake(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventEarthquake(btn, cursor, ws)

    internal suspend fun triggerMassEscape(
        btn: FloatingButton, cursor: () -> Offset, ws: () -> IntSize,
    ) = eventMassEscape(btn, cursor, ws)
}
