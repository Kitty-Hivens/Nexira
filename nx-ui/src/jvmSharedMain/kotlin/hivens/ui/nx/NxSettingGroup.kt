package hivens.ui.nx

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status

/**
 * A group of settings: its title, then its rows on one plane with a hairline
 * between each pair.
 *
 * The title is set in the ink, not the accent. The accent marks what is selected
 * or active, and a page of accent-coloured headings left it nothing to mark. A
 * [danger] group is the exception that earns colour: its title says what clicking
 * in it can cost.
 *
 * The hairlines are drawn by the group from where its rows land, so a caller lists
 * rows and never places a divider, and a row that draws nothing (a setting that
 * does not apply here) leaves no doubled line behind it.
 */
@Composable
fun NxSettingGroup(
    title: String?,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleSmall,
                color      = if (danger) NxColor.status(Status.Error, text = true) else NxInk.main,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
        NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
            RuledColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), content)
        }
    }
}

/**
 * One setting: what it is, a line on why it matters, and its control at the far
 * edge. The row every group is made of, so a switch, a button and a chip pair all
 * sit on the same baseline and the same two type sizes.
 */
@Composable
fun NxSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val alpha = if (enabled) 1f else 0.4f
    val still = LocalCustomization.current.reduceMotion
    val reveal = Motion.reveal
    Row(
        // The height follows the text rather than jumping to it: a detail line that
        // arrives late (a folder size being measured) or wraps differently once the
        // sheet widens would otherwise move every row under it in one frame.
        modifier              = modifier.fillMaxWidth()
            .animateContentSize(if (still) snap() else reveal.of())
            .heightIn(min = 60.dp).padding(vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.bodyMedium,
                color      = NxInk.main.copy(alpha = alpha),
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text     = detail,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = NxInk.quiet.copy(alpha = alpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** A setting that needs more than a control at the edge: a slider, a pair of fields. */
@Composable
fun NxSettingBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier            = modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content             = content,
    )
}

/**
 * Lays its children out in a column and draws a hairline in the gap between each
 * pair that has a height. Where the lines go is decided by the placement and read
 * by the draw, so it is held as state the draw observes rather than worked out
 * twice.
 */
@Composable
private fun RuledColumn(modifier: Modifier, content: @Composable () -> Unit) {
    val line = NxInk.line
    var rules by remember { mutableStateOf(emptyList<Int>()) }
    Layout(
        content  = content,
        modifier = modifier.drawBehind {
            val thickness = 1.dp.toPx()
            rules.forEach { y -> drawRect(line, Offset(0f, y.toFloat()), Size(size.width, thickness)) }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }.filter { it.height > 0 }
        val gap = 1.dp.roundToPx()
        val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            val at = ArrayList<Int>(placeables.size)
            placeables.forEachIndexed { i, p ->
                if (i > 0) {
                    at += y
                    y += gap
                }
                p.place(0, y)
                y += p.height
            }
            if (at != rules) rules = at
        }
    }
}
