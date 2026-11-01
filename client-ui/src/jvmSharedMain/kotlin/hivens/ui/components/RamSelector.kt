package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.jvm.SystemMemory
import hivens.ui.customization.sliderKeyboardAdjust
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxField
import hivens.ui.nx.NxReveal
import hivens.ui.nx.NxSettingBlock
import hivens.ui.nx.NxSettingRow
import hivens.ui.nx.NxSliderTrack
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import java.util.Locale
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * The heap a pack launches with, as rows of a settings group. Represents a MODE,
 * not just a number:
 *  - [isAuto] true  -> "Auto": the machine-aware Automatic baseline, refined by the
 *    adaptive sizer when it is on. The row says what that comes to right now,
 *    [resolvedAutoMb], the heap the next launch will actually use.
 *  - [isAuto] false -> a pinned (Fixed) value, [currentMb]: a slider in gigabytes
 *    over what this machine can spare, and a field beside it for an exact number.
 *
 * Gigabytes everywhere a person reads or types. The field used to take megabytes
 * while showing the current value in gigabytes as its hint, so "10" typed against a
 * hint of "10 GB" meant ten megabytes, fell outside the range and was dropped
 * without a word. A value outside the range now says so.
 *
 * The slider stops at what is recommended for the machine, because that is the
 * range worth dragging through. The field still takes anything up to the old
 * ceiling, and a value past the recommendation is kept and warned about rather than
 * refused: it is the player's machine.
 *
 * Stateless: the host owns the mode. Choosing "own" or moving a value calls
 * [onValueChanged] (pins the instance); "Auto" calls [onAutoSelected] (un-pins it).
 */
@Composable
fun RamSelector(
    isAuto: Boolean,
    resolvedAutoMb: Int,
    currentMb: Int,
    onAutoSelected: () -> Unit,
    onValueChanged: (Int) -> Unit,
) {
    val s = LocalStrings.current
    val systemRamMb = remember { SystemMemory.totalPhysicalMb() }
    val recommendedMb = (systemRamMb * RECOMMENDED_SHARE).toInt()

    NxSettingRow(
        title  = s.ramModeTitle,
        detail = if (isAuto) s.ramAutoDetail(formatRam(resolvedAutoMb, s)) else s.ramOwnDetail,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NxChoiceChip(s.ramModeAuto, selected = isAuto) { if (!isAuto) onAutoSelected() }
            // Leaving Auto starts from the number Auto was using, not from a constant.
            NxChoiceChip(s.ramModeOwn, selected = !isAuto) { if (isAuto) onValueChanged(resolvedAutoMb) }
        }
    }

    // Opened out under the row rather than swapped in: the choice above it stays
    // where it was, and the eye follows what it uncovered.
    NxReveal(visible = !isAuto) {
        NxSettingBlock {
            val sliderTopGb = maxOf(snapGb(recommendedMb / MB_PER_GB, down = true), currentMb / MB_PER_GB, MIN_SLIDER_GB)
            // The track bare, under a header set like every other row: the slider's own
            // label is a size larger, made for a page with nothing else on it.
            val sliderValue = (currentMb / MB_PER_GB).coerceIn(MIN_SLIDER_GB, sliderTopGb)
            val onSlide: (Float) -> Unit = { gb -> onValueChanged((snapGb(gb) * MB_PER_GB).roundToInt()) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.ramAllocated,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = NxInk.main,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.weight(1f),
                )
                Text(formatRam(currentMb, s), style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
            }
            NxSliderTrack(
                value         = sliderValue,
                range         = MIN_SLIDER_GB..sliderTopGb,
                onValueChange = onSlide,
                modifier      = Modifier.fillMaxWidth().sliderKeyboardAdjust(sliderValue, MIN_SLIDER_GB..sliderTopGb, STEP_GB, onSlide),
            )

            // Re-seeded from the record whenever it moves, so a drag on the slider shows
            // in the field; typing keeps what was typed until it parses.
            var typed by remember(currentMb) { mutableStateOf(gbText(currentMb)) }
            val parsed = typed.replace(',', '.').toFloatOrNull()
            val typedMb = parsed?.let { (it * MB_PER_GB).roundToInt() }
            val outOfRange = typed.isNotBlank() && (typedMb == null || typedMb !in MIN_MB..MAX_MB)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NxField(
                    value         = typed,
                    onValueChange = { raw ->
                        typed = raw.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
                        typed.replace(',', '.').toFloatOrNull()
                            ?.let { (it * MB_PER_GB).roundToInt() }
                            ?.takeIf { it in MIN_MB..MAX_MB }
                            ?.let(onValueChanged)
                    },
                    placeholder   = gbText(currentMb),
                    modifier      = Modifier.width(88.dp),
                )
                Text(s.ramUnitGb, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
            }

            val hint = when {
                outOfRange -> s.ramOutOfRange(formatRam(MIN_MB, s), formatRam(MAX_MB, s))
                currentMb > recommendedMb -> s.ramAboveRecommended(formatRam(recommendedMb, s))
                else -> s.ramSystemHint(formatRam(systemRamMb, s), formatRam(recommendedMb, s))
            }
            Text(
                text  = hint,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    outOfRange -> NxColor.status(Status.Error, text = true)
                    currentMb > recommendedMb -> NxColor.status(Status.Warning, text = true)
                    else -> NxInk.quiet
                },
            )
        }
    }
}

/** Snapped to the slider's step, rounding down when a ceiling must not be crossed. */
private fun snapGb(gb: Float, down: Boolean = false): Float {
    val steps = gb / STEP_GB
    return (if (down) floor(steps) else round(steps)) * STEP_GB
}

/** A value as the field shows it: whole gigabytes bare, anything else to a tenth. */
private fun gbText(mb: Int): String {
    val gb = mb / MB_PER_GB
    return if (gb % 1f == 0f) gb.toInt().toString() else String.format(Locale.ROOT, "%.1f", gb)
}

internal fun formatRam(mb: Int, s: AppStrings): String = when {
    mb >= 1024 && mb % 1024 == 0 -> "${mb / 1024} ${s.ramUnitGb}"
    mb >= 1024 -> String.format(Locale.ROOT, "%.1f %s", mb / MB_PER_GB, s.ramUnitGb)
    else -> "$mb ${s.ramUnitMb}"
}

private const val MB_PER_GB = 1024f

/** The share of physical memory the slider offers, leaving the rest to the system. */
private const val RECOMMENDED_SHARE = 0.75

private const val STEP_GB = 0.5f
private const val MIN_SLIDER_GB = 1f

/** What the field accepts: the range the launcher has always taken. */
private const val MIN_MB = 512
private const val MAX_MB = 32768
