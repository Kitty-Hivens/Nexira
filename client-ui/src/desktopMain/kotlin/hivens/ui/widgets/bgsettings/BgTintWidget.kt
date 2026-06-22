package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Tint preset picker + intensity slider. Kept as one widget instead
// of splitting per-preset (6 widgets) or per-control (picker +
// intensity = 2 widgets) -- the intensity slider only makes sense
// when a tint is selected, so they share fate.
@Widget(id = "bg.tint", displayName = "widget.bg.tint")
@Composable
fun BgTintWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Column {
        SectionTitle(s.backgroundSectionTint)
        Spacer(Modifier.size(8.dp))

        val tintPresets = listOf(
            null      to s.backgroundTintNone,
            "#1A1A2E" to s.backgroundTintNavy,
            "#2D1B4E" to s.backgroundTintViolet,
            "#0D3B2E" to s.backgroundTintEmerald,
            "#3B1515" to s.backgroundTintBordeaux,
            "#1B2A3B" to s.backgroundTintSteel,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tintPresets.forEach { (hex, label) ->
                val selected = settings.tintColor == hex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) NxTheme.colors.primary else Color.Transparent,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .clickable {
                            ctx.update {
                                copy(
                                    tintColor   = hex,
                                    tintOpacity = if (hex != null) 0.3f else 0f,
                                )
                            }
                        }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (hex != null) runCatching {
                                    Color(("FF" + hex.removePrefix("#")).toLong(16))
                                }.getOrDefault(Color.Gray)
                                else NxTheme.colors.surface,
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(label, fontSize = 9.sp, color = NxTheme.colors.textSecondary)
                }
            }
        }

        if (settings.tintColor != null) {
            LabeledSlider(s.backgroundTintIntensity, settings.tintOpacity, 0f..0.7f, "%.0f%%", 100f) {
                ctx.update { copy(tintOpacity = it) }
            }
        }
    }
}
