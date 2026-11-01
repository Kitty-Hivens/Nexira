package hivens.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.theme.NxInk

/**
 * The inside of one card in the right rail: its name, then its groups.
 *
 * Shared by every rail family, so a block about a project and a block about a
 * search are the same object to the eye. The card's plane and hairline come from
 * the widget's own `@Widget(surface = ...)` record and the kernel paints them; the
 * sixteen of padding is here, because the record's padding is an outer inset and
 * putting it there shrank the card instead of insetting its text.
 */
@Composable
internal fun RailBlock(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(RAIL_CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(RAIL_SECTION_GAP),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontSize = RAIL_BLOCK_TITLE,
            // The brightest ink there is. Both this and the group label under it
            // were on textPrimary, which put an 18 semibold and a 16 normal within
            // a hair of each other: on the sheet the block's name and the name of a
            // group inside it read as the same rank. The reference uses two tones
            // and so does the palette, so use them.
            color = NxInk.main,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

/** A label and the thing it labels, held closer to each other than to the next group. */
@Composable
internal fun RailGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RAIL_GROUP_GAP)) {
        content()
    }
}

@Composable
internal fun RailLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    fontSize = RAIL_GROUP_LABEL,
    color = NxInk.main,
    fontWeight = FontWeight.Normal,
)

/**
 * The two sizes the reference uses in a sidebar block, measured rather than
 * guessed: the block's name, then the name of a group inside it.
 *
 * The first pass here used the app's small title and small label roles, 14 bold
 * and 11 secondary, which put the whole rail a step below the body text it sits
 * beside and made it read as a footnote to the page rather than as half of it.
 */
private val RAIL_BLOCK_TITLE = 18.sp
private val RAIL_GROUP_LABEL = 16.sp

/**
 * Twelve between one group and the next, eight between a group's label and what it
 * labels.
 *
 * One number for both is what the first pass used, and it detaches every label
 * from its own content: "Platforms" sat exactly as far from the platform chips as
 * from the game-version chips above it, so the column read as alternating lines
 * rather than as three groups.
 */
private val RAIL_SECTION_GAP = 12.dp
private val RAIL_GROUP_GAP = 8.dp
private val RAIL_CARD_PADDING = 16.dp
