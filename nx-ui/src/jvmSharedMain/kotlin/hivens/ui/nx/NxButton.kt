package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.theme.Status

/**
 * The one button. Four emphasis roles -- a call site picks a role, not colours. The per-widget freedom the old de-facto base button
 * had (an arbitrary Material ButtonColors) moves up to the Flexible event layer.
 *
 *  - [Primary]     -- filled accent; the one obvious next action (Play, Login). One per screen.
 *  - [Secondary]   -- outlined, transparent; alternative actions (Cancel, Open folder).
 *  - [Tertiary]    -- text only, no border; low-emphasis / navigation (View on GitHub, Register).
 *  - [Destructive] -- filled error; a destructive or critical action (Delete, Force re-download).
 *
 * Sizing stays provisional ([compact] / [minHeight]) until the dimens token scale
 * lands; decorative glow lives at the call site or the effects layer, not in the
 * button -- both are the separate styling track, not a button role.
 */
enum class NxButtonStyle { Primary, Secondary, Tertiary, Destructive }

@Composable
fun NxButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NxButtonStyle = NxButtonStyle.Primary,
    icon: IconKey? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    minHeight: Dp? = null,
) {
    val shape = MaterialTheme.shapes.small

    val container: Color = when (style) {
        NxButtonStyle.Primary     -> NxColor.lead()
        NxButtonStyle.Destructive -> NxColor.status(Status.Error)
        NxButtonStyle.Secondary   -> Color.Transparent
        NxButtonStyle.Tertiary    -> Color.Transparent
    }
    // Alpha alone does not read as off on a filled style: a destructive button at
    // forty-five percent is still a saturated slab, so a blocked delete looked
    // armed. Disabled drops the fill entirely, which is what "disabled" already
    // looks like on the quiet styles: one appearance for one state.
    //
    // Which means the ink has to move with it. On a fill the label is whichever ink
    // reads on that fill. With the fill gone it is the plane's own ink, since a
    // disabled control has to be readable, that is the whole point of showing it.
    val content: Color = when {
        !enabled -> NxInk.main
        style == NxButtonStyle.Primary || style == NxButtonStyle.Destructive -> NxColor.on(container)
        style == NxButtonStyle.Secondary -> NxInk.main
        else -> NxColor.lead()
    }
    val outline = NxInk.line
    val bordered = style == NxButtonStyle.Secondary
    val dim = if (enabled) 1f else 0.45f
    val pad = if (compact) PaddingValues(horizontal = 12.dp, vertical = 6.dp)
              else PaddingValues(horizontal = 18.dp, vertical = 10.dp)

    // Hover/press feedback through the shared ShapedStateLayer, provided as
    // LocalIndication so the clickable picks it up.
    CompositionLocalProvider(
        LocalIndication provides ShapedStateLayer(8.dp, content),
    ) {
        Row(
            modifier              = modifier
                .let { if (minHeight != null) it.heightIn(min = minHeight) else it }
                .clip(shape)
                .background(
                    if (enabled) container
                    else Color.Transparent,
                )
                .let { if (bordered) it.border(1.dp, outline.copy(alpha = dim), shape) else it }
                .layerTrigger()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(pad),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            if (icon != null) {
                Symbol(icon, contentDescription = null, tint = content.copy(alpha = dim), size = if (compact) 16.dp else 18.dp)
            }
            Text(
                text       = label,
                color      = content.copy(alpha = dim),
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview
@Composable
private fun NxButtonDarkPreview() = ButtonStyles(dark = true)

@Preview
@Composable
private fun NxButtonLightPreview() = ButtonStyles(dark = false)

@Composable
private fun ButtonStyles(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(560.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (enabled in listOf(true, false)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (style in NxButtonStyle.entries) {
                        NxButton(style.name, onClick = {}, style = style, enabled = enabled)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NxButton("Play", onClick = {}, icon = NxIcon.PlayArrow)
                NxButton("Open folder", onClick = {}, style = NxButtonStyle.Secondary, icon = NxIcon.FolderOpen)
                NxButton("Delete", onClick = {}, style = NxButtonStyle.Destructive, icon = NxIcon.Delete)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NxButton("Compact", onClick = {}, compact = true)
                NxButton("Compact", onClick = {}, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
                NxButton("Tall", onClick = {}, style = NxButtonStyle.Secondary, minHeight = 48.dp)
            }
        }
    }
}
