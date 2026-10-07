package hivens.ui.nx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk

/**
 * Sections of one thing, as words with a mark under the current one.
 *
 * Words rather than chips because a chip row already means "filter this list" in
 * the interface: two rows of identical chips, one switching what the page is about
 * and one narrowing a list, read as one control with two lines.
 *
 * It was the project page's own row before any other screen needed one, and the
 * numbers are that page's, measured off its reference: twenty between words, a
 * mark twenty-six wide and two high under the word rather than a rule its width.
 */
@Composable
fun NxTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Where each tab's mark belongs, measured rather than computed: the labels are
    // different words in different languages and the row is the only thing that
    // knows how wide each came out.
    val marks = remember { mutableStateMapOf<Int, Dp>() }
    val target = marks[selected]
    val travel = remember { Animatable(0.dp, Dp.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    val spec = Motion.track.of<Dp>()
    LaunchedEffect(target, spec) {
        val to = target ?: return@LaunchedEffect
        if (!placed) {
            // The first position is where the mark ALREADY is. Animating to it
            // would slide the underline in from the left edge on arrival, as if
            // the reader had just moved it there.
            travel.snapTo(to)
            placed = true
        } else {
            travel.animateTo(to, spec)
        }
    }

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            tabs.forEachIndexed { i, label ->
                // Measured at its heaviest, drawn at its current weight. A bold face
                // is wider, so selecting a tab used to widen its label and slide
                // every tab after it sideways, out from under the cursor that had
                // just clicked.
                NxSteadyText(
                    text = label,
                    weight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (i == selected) NxInk.main else NxInk.quiet,
                    // No indication. A tab already says where you are with its weight
                    // and its mark, and a hover plate behind the word is a second
                    // answer to a question the row has already answered.
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) }
                        .onGloballyPositioned { c ->
                            val centre = c.positionInParent().x + c.size.width / 2f
                            marks[i] = with(density) { (centre - MARK_WIDTH.toPx() / 2f).toDp() }
                        },
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        // ONE mark that travels, not one per tab that blinks on and off. The
        // underline is the same object wherever it is, so it moves the way the
        // reader's attention does -- and because every click retargets an
        // animation already in flight, a run of fast clicks is followed rather
        // than queued: the mark is always heading for the tab last asked for,
        // from wherever it had got to.
        Box(Modifier.fillMaxWidth().height(MARK_HEIGHT)) {
            if (placed) {
                Box(
                    Modifier.offset(x = travel.value)
                        .size(width = MARK_WIDTH, height = MARK_HEIGHT)
                        .background(NxColor.lead()),
                )
            }
        }
    }
}

/** The underline: a mark under the word rather than a rule the width of it. */
private val MARK_WIDTH = 26.dp
private val MARK_HEIGHT = 2.dp
