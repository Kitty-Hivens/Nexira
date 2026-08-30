package hivens.ui.widgets.themepicker

import hivens.ui.theme.LocalMonoFamily
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxColorSurface
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CustomTheme
import hivens.widget.model.Widget

// Preview panel mirroring the current selectedTheme. Reads only,
// never writes. Safe to remove from the surface: the user keeps
// picking themes from the grid; the apply button in the header
// still commits.
@Widget(id = "theme.picker.preview", displayName = "widget.theme.picker.preview")
@Composable
fun ThemePickerPreviewWidget() {
    val ctx = LocalThemePickerContext.current
    val s = LocalStrings.current
    val theme by ctx.selectedTheme

    // The panel is painted in the theme being previewed, so everything on it has
    // to come from that theme. Text and the frame came from the LIVE one, which
    // rendered a light theme previewed from a dark one as pale grey on near-white
    // -- illegible, on the one surface whose whole job is showing whether a theme
    // reads. Opaque for the same reason: at 0.8 the current surface showed through
    // and tinted the colour being judged.
    val ground   = CustomTheme.parseHexColor(theme.background)
    val onGround = if (ground.luminance() > 0.5f) Color(0xFF1A1A1A) else Color(0xFFF2F2F2)

    NxColorSurface(
        color    = ground,
        modifier = Modifier.fillMaxSize(),
        shape    = MaterialTheme.shapes.large,
        border   = BorderStroke(1.dp, onGround.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text       = s.themePickerPreview,
                style      = MaterialTheme.typography.bodySmall,
                color      = CustomTheme.parseHexColor(theme.primary),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorRow(s.themePickerColorPrimary,    theme.primary,    onGround)
                ColorRow(s.themePickerColorSecondary,  theme.secondary,  onGround)
                ColorRow(s.themePickerColorBackground, theme.background, onGround)
                ColorRow(s.themePickerColorSurface,    theme.surface,    onGround)
                ColorRow(s.themePickerColorAccent,     theme.accent,     onGround)
                ColorRow(s.themePickerColorSuccess,    theme.success,    onGround)
                ColorRow(s.themePickerColorError,      theme.error,      onGround)
            }
            Spacer(Modifier.height(24.dp))
            // Visual sample buttons. enabled = false so the
            // cursor + click feedback signals "demo, not a
            // control" -- a user who clicks expecting a real
            // action gets the disabled grey cue. Container
            // colors remain theme-driven so the preview still
            // communicates the theme's filled/outlined look.
            Button(
                onClick  = {},
                enabled  = false,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = CustomTheme.parseHexColor(theme.primary),
                    disabledContainerColor = CustomTheme.parseHexColor(theme.primary),
                ),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Text(s.themePickerBtnSample, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick  = {},
                enabled  = false,
                modifier = Modifier.fillMaxWidth(),
                border   = BorderStroke(2.dp, CustomTheme.parseHexColor(theme.primary)),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text       = s.themePickerBtnOutlined,
                    color      = CustomTheme.parseHexColor(theme.primary),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, hexColor: String, onGround: Color) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = onGround.copy(alpha = 0.72f),
        )
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CustomTheme.parseHexColor(hexColor))
                    .border(BorderStroke(1.dp, onGround.copy(alpha = 0.3f)), RoundedCornerShape(4.dp)),
            )
            Text(
                text       = hexColor,
                style      = MaterialTheme.typography.bodySmall,
                color      = onGround,
                fontFamily = LocalMonoFamily.current,
            )
        }
    }
}
