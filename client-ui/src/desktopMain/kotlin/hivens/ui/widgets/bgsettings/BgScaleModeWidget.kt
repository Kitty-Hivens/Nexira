package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.background.ScaleMode
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.scale.mode", displayName = "widget.bg.scale.mode")
@Composable
fun BgScaleModeWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Column {
        SectionTitle(s.backgroundSectionScale)
        Spacer(Modifier.size(8.dp))
        val scaleModes = listOf(
            ScaleMode.COVER    to s.backgroundScaleCover,
            ScaleMode.CONTAIN  to s.backgroundScaleContain,
            ScaleMode.STRETCH  to s.backgroundScaleStretch,
            ScaleMode.ORIGINAL to s.backgroundScaleOriginal,
            ScaleMode.TILE     to s.backgroundScaleTile,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            scaleModes.forEach { (mode, label) ->
                val selected = settings.scaleMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (selected) CelestiaTheme.colors.primary else glassSurfaceAlpha(0.4f))
                        .clickable { ctx.update { copy(scaleMode = mode) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = label,
                        fontSize   = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color      = if (selected) Color.White else CelestiaTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

