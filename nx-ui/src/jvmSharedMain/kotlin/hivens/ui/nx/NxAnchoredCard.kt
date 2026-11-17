package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import kotlin.math.roundToInt

/**
 * A small surface that opens from a point to say something about the thing there.
 *
 * Not a menu. A menu is a list of verbs, one row, one click and it is gone, and a
 * paragraph set among its rows reads as one more row the eye has to skip. This is
 * a card: its own width, a body that can carry a sentence and a picture, and the
 * actions it explains as buttons at its foot. It hangs and unfolds exactly as a
 * menu does, from the control that opened it or from the pointer, because that is
 * how a reader tells what it is about.
 *
 * Declare the trigger form inside the `Box` that wraps the trigger.
 */
@Composable
fun NxAnchoredCard(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    align: NxMenuAlign = NxMenuAlign.End,
    width: Dp = CARD_WIDTH,
    /** Where the focus goes when the card closes, for a trigger that had it. */
    returnFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val gapPx = with(LocalDensity.current) { CARD_GAP.roundToPx() }
    val origin = remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val provider = remember(gapPx, align, origin) { MenuBelowAnchor(align, gapPx, origin) }
    NxMenuPopup(provider, origin, expanded, onDismissRequest, returnFocus = returnFocus) {
        CardSurface(modifier, width, content)
    }
}

/** The pointer form: opens with its corner at [anchorInWindow], as a right-click menu does. */
@Composable
fun NxAnchoredCard(
    anchorInWindow: Offset,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CARD_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    val gapPx = with(LocalDensity.current) { CARD_GAP.roundToPx() }
    val x = anchorInWindow.x.roundToInt()
    val y = anchorInWindow.y.roundToInt()
    val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val parent = rememberParentInWindow()
    val provider = remember(x, y, gapPx, origin, parent) { MenuAtWindowOffset(x, y, gapPx, origin, parent) }
    NxMenuPopup(provider, origin, expanded, onDismissRequest) {
        CardSurface(modifier, width, content)
    }
}

@Composable
private fun CardSurface(modifier: Modifier, width: Dp, content: @Composable ColumnScope.() -> Unit) {
    NxSurface(
        kind     = SurfaceKind.Popup,
        shape    = MaterialTheme.shapes.large,
        modifier = modifier.width(width),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Wide enough for a sentence to wrap into a paragraph rather than a column of words. */
private val CARD_WIDTH = 380.dp

private val CARD_GAP = 4.dp

@Preview
@Composable
private fun NxAnchoredCardDarkPreview() = OpenCard(dark = true)

@Preview
@Composable
private fun NxAnchoredCardLightPreview() = OpenCard(dark = false)

/** The card as it stands once open, drawn through its own surface, for the reason the menu's preview gives. */
@Composable
private fun OpenCard(dark: Boolean) {
    NxTheme(NeutralTheme, dark = dark) {
        Column(Modifier.width(420.dp).background(NxColor.page).padding(16.dp)) {
            CardSurface(Modifier, CARD_WIDTH) {
                Text("Shared folder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = NxInk.main)
                Text(
                    "Every pack reads its screenshots from this folder. Moving it moves them for all packs at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NxInk.quiet,
                )
                Row(
                    modifier              = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NxButton("Keep", onClick = {}, style = NxButtonStyle.Tertiary, compact = true)
                    NxButton("Move", onClick = {}, compact = true)
                }
            }
        }
    }
}
