package hivens.ui.easter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.launch

/**
 * Developer-only debug panel for testing April Fools behavior.
 * Accessed by tapping the Diagnostics section header 5 times in Settings.
 *
 * Works from any screen -- registers a synthetic "TEST" button into ChaosState
 * so event triggers never require navigating to Dashboard first.
 */
@Composable
fun AprilFoolsDebugPanel() {
    val isForced  = AprilFools.debugForceActive == true
    val intensity = AprilFools.debugIntensity ?: AprilFools.intensity()
    val scope     = rememberCoroutineScope()

    // ── Synthetic test button ─────────────────────────────────────────────────
    // Registered for the lifetime of this composable so triggers always have a target.
    val testBtn = remember {
        FloatingButton(
            id       = "debug_test_btn",
            label    = "TEST",
            widthPx  = 160f,
            heightPx = 50f,
        )
    }

    LaunchedEffect(Unit) {
        if (ChaosState.buttons.none { it.id == "debug_test_btn" }) {
            ChaosState.register(testBtn)
        }
    }

    DisposableEffect(Unit) {
        onDispose { ChaosState.unregister("debug_test_btn") }
    }

    // ── Fake cursor + window size for triggers ────────────────────────────────
    val fakeWs: () -> IntSize = { IntSize(1920, 1080) }
    val fakeCursor: () -> Offset = { Offset(960f, 540f) }

    // ── Helper: fire one event on the test button ─────────────────────────────
    @Composable
    fun TriggerButton(
        label: String,
        event: suspend (FloatingButton, () -> Offset, () -> IntSize) -> Unit,
    ) {
        OutlinedButton(
            onClick = {
                // Place in center if never rendered on screen
                if (testBtn.originPx == Offset.Zero) {
                    testBtn.originPx = Offset(760f, 540f)
                }
                // Reset to idle so the engine accepts it as a target
                testBtn.phase           = ChaosPhase.IDLE
                testBtn.originalVisible = true

                scope.launch {
                    runCatching { event(testBtn, fakeCursor, fakeWs) }
                }
            },
            enabled        = isForced,
            modifier       = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            shape          = MaterialTheme.shapes.small,
        ) {
            Text(label, fontSize = 11.sp)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, CelestiaTheme.colors.error.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
            .background(CelestiaTheme.colors.error.copy(alpha = 0.06f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text       = "🐣 April Fools Debug",
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.error,
                fontSize   = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text  = "DEV ONLY (etc. clown)",
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.error.copy(alpha = 0.5f),
            )
        }

        HorizontalDivider(color = CelestiaTheme.colors.error.copy(alpha = 0.2f))

        // ── Force active toggle ───────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text  = "Force active (override date)",
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textPrimary,
            )
            Switch(
                checked         = isForced,
                onCheckedChange = { enabled ->
                    AprilFools.debugForceActive = if (enabled) true else null
                    if (!enabled) {
                        ChaosState.buttons.forEach { b ->
                            b.phase           = ChaosPhase.IDLE
                            b.originalVisible = true
                            b.hasLegs         = false
                        }
                        ChaosState.ghosts.clear()
                        ChaosState.shakeOffset   = Offset.Zero
                        ChaosState.globalTiltDeg = 0f
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CelestiaTheme.colors.error,
                    checkedTrackColor = CelestiaTheme.colors.error.copy(alpha = 0.5f),
                ),
            )
        }

        // ── Intensity slider ──────────────────────────────────────────────────
        Column {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text  = "Intensity",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isForced) CelestiaTheme.colors.textPrimary
                    else CelestiaTheme.colors.textSecondary,
                )
                Text(
                    text       = "%.0f%% (day ~${(intensity * 13f + 1f).toInt()})".format(intensity * 100),
                    style      = MaterialTheme.typography.bodySmall,
                    color      = CelestiaTheme.colors.error,
                    fontWeight = FontWeight.Bold,
                )
            }

            Slider(
                value         = intensity,
                onValueChange = { AprilFools.debugIntensity = it },
                enabled       = isForced,
                colors        = SliderDefaults.colors(
                    thumbColor       = CelestiaTheme.colors.error,
                    activeTrackColor = CelestiaTheme.colors.error,
                ),
            )

            // Quick day presets
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(
                    "Day 1"  to 0.07f,
                    "Day 4"  to 0.28f,
                    "Day 7"  to 0.50f,
                    "Day 10" to 0.71f,
                    "Day 14" to 1.00f,
                ).forEach { (label, v) ->
                    TextButton(
                        onClick  = { AprilFools.debugIntensity = v },
                        enabled  = isForced,
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(label, fontSize = 10.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = CelestiaTheme.colors.error.copy(alpha = 0.15f))

        // ── Event triggers ────────────────────────────────────────────────────
        Text(
            text  = "Trigger event now",
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriggerButton("Sticky")   { b, c, w -> AprilFoolsEngine.triggerCursorSticky(b, c, w) }
            TriggerButton("Wobble")   { b, c, w -> AprilFoolsEngine.triggerDrunkWobble(b, c, w) }
            TriggerButton("Flee")     { b, c, w -> AprilFoolsEngine.triggerFleeing(b, c, w) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriggerButton("Spin+Fly") { b, c, w -> AprilFoolsEngine.triggerSpinAndFly(b, c, w) }
            TriggerButton("Teleport") { b, c, w -> AprilFoolsEngine.triggerTeleport(b, c, w) }
            TriggerButton("Legs")     { b, c, w -> AprilFoolsEngine.triggerLegsWalk(b, c, w) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriggerButton("Ghost")    { b, c, w -> AprilFoolsEngine.triggerGhostClone(b, c, w) }
            // Earthquake is screen-wide -- testBtn is unused inside but required by signature
            TriggerButton("Quake")    { b, c, w -> AprilFoolsEngine.triggerEarthquake(b, c, w) }
            TriggerButton("Mass!")    { b, c, w -> AprilFoolsEngine.triggerMassEscape(b, c, w) }
        }

        // ── Status line ───────────────────────────────────────────────────────
        Text(
            text  = "Registered: ${ChaosState.buttons.size} | Active: ${ChaosState.activeCount()}",
            style = MaterialTheme.typography.labelSmall,
            color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.5f),
        )

        // ── Reset ─────────────────────────────────────────────────────────────
        OutlinedButton(
            onClick = {
                AprilFools.debugForceActive = null
                AprilFools.debugIntensity   = null
                ChaosState.buttons.forEach { b ->
                    b.phase           = ChaosPhase.IDLE
                    b.originalVisible = true
                    b.hasLegs         = false
                }
                ChaosState.ghosts.clear()
                ChaosState.shakeOffset   = Offset.Zero
                ChaosState.globalTiltDeg = 0f
            },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.outlinedButtonColors(
                contentColor = CelestiaTheme.colors.error,
            ),
            shape    = MaterialTheme.shapes.small,
        ) {
            Text("Reset all overrides + stop chaos")
        }
    }
}
