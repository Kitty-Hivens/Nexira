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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.components.GlassCard
import hivens.ui.customization.scaledAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.ui.theme.CustomTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Preview panel mirroring the current selectedTheme. Reads only,
// never writes. Safe to remove from the surface: the user keeps
// picking themes from the grid; the apply button in the header
// still commits.
@Widget(id = "theme.picker.preview", displayName = "widget.theme.picker.preview")
@Composable
fun ThemePickerPreviewWidget(instance: WidgetInstance) {
    val ctx = LocalThemePickerContext.current
    val s = LocalStrings.current
    val theme by ctx.selectedTheme

    GlassCard(
        modifier        = Modifier.fillMaxSize(),
        shape           = MaterialTheme.shapes.large,
        backgroundColor = scaledAlpha(CustomTheme.parseHexColor(theme.background), 0.8f),
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
                ColorRow(s.themePickerColorPrimary,    theme.primary)
                ColorRow(s.themePickerColorSecondary,  theme.secondary)
                ColorRow(s.themePickerColorBackground, theme.background)
                ColorRow(s.themePickerColorSurface,    theme.surface)
                ColorRow(s.themePickerColorAccent,     theme.accent)
                ColorRow(s.themePickerColorSuccess,    theme.success)
                ColorRow(s.themePickerColorError,      theme.error)
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
private fun ColorRow(label: String, hexColor: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NxTheme.colors.textSecondary,
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
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), RoundedCornerShape(4.dp)),
            )
            Text(
                text       = hexColor,
                style      = MaterialTheme.typography.bodySmall,
                color      = NxTheme.colors.textPrimary,
                fontFamily = LocalMonoFamily.current,
            )
        }
    }
}
