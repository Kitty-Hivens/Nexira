package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 */
@Composable
fun NxTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        tabs.forEachIndexed { i, label ->
            val isSelected = i == selected
            val mark by animateColorAsState(
                if (isSelected) NxColor.lead() else Color.Transparent,
                Motion.track.of(),
                label = "tabMark",
            )
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                    .padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.titleSmall,
                    color      = if (isSelected) NxInk.main else NxInk.quiet,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines   = 1,
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(mark))
            }
        }
    }
}
