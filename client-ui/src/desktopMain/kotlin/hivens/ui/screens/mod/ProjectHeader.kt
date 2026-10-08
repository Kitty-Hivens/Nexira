package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.decorativeColor
import hivens.ui.theme.familyForText

/**
 * The top of a project's page, and of a pack's: its mark, its name, its line, the
 * facts under them, and what can be done about it on the right.
 *
 * One header for both, because a reader moving from a mod to the pack it sits in
 * should not have to learn a second page. What differs is handed in: [facts] is
 * the row of counts and tags, [actions] the buttons, [notes] the lines that answer
 * for what a button just did.
 */
@Composable
internal fun ProjectHeader(
    title: String,
    description: String?,
    iconUrl: String?,
    facts: @Composable FlowRowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    notes: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Top, not centre. With a two-line tagline and a row of counts under it,
            // centring hangs the 96dp icon halfway down the block and the title stops
            // sitting on the same line as anything.
            verticalAlignment = Alignment.Top,
        ) {
            ProjectIcon(iconUrl, title)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = NxInk.main,
                        // Semibold and tight, the way the reference sets it. Bold at this
                        // size reads as a banner rather than as a name.
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = TITLE_SIZE,
                        fontFamily = familyForText(title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NxInk.quiet,
                            // The tagline is set to a reading measure rather than to the
                            // column: run across a wide window it becomes one long line
                            // nobody tracks back from.
                            modifier = Modifier.widthIn(max = SUMMARY_MEASURE),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 26 across between facts, 8 down when they wrap. Measured: the counts
                // are separate statements and want real air, the categories are one
                // list and want none.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    content = facts,
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    content = actions,
                )
                notes()
            }
        }
        // The rule under the header, which the reference draws: it parts the project
        // from the tabs that lead into it.
        HorizontalDivider(color = NxInk.line)
    }
}

/** One count in the header's fact row: the number in the strong ink, what it counts in the quiet one. */
@Composable
internal fun HeaderStat(icon: IconKey, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Symbol(icon, contentDescription = null, tint = NxInk.quiet, size = 15.dp)
        Text(value, style = MaterialTheme.typography.labelLarge, color = NxInk.main, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
    }
}

/**
 * The project's mark at 96, its initial over its hue where it has none, and where
 * the picture it named does not come: a jar the mirror reads an icon out of may
 * carry none, and the address answers with nothing to show.
 */
@Composable
private fun ProjectIcon(url: String?, title: String) {
    val shape = RoundedCornerShape(18.dp)
    // 96, as the reference sets it. 84 was mine and left the header looking like a
    // list row that had been enlarged rather than like the top of a page.
    if (url != null) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(ICON_SIZE).clip(shape),
            error = { Initial(title) },
        )
    } else {
        Box(Modifier.size(ICON_SIZE).clip(shape)) { Initial(title) }
    }
}

@Composable
private fun Initial(title: String) {
    val tile = decorativeColor(title)
    Box(Modifier.fillMaxSize().background(tile), contentAlignment = Alignment.Center) {
        Text(
            title.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.headlineMedium,
            color = NxColor.on(tile),
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Measured off the reference: a 24 title set tight, a 704 reading measure, a 96 mark. */
private val TITLE_SIZE = 24.sp
private val SUMMARY_MEASURE = 704.dp
private val ICON_SIZE = 96.dp
