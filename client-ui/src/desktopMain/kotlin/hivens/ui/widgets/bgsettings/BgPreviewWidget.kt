package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.background.CustomBackground
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Live preview of the current bg settings. Tracks mouse position
// (normalised to widget size) so the parallax effect feels correct
// during slider edits. Writes ctx.previewMousePos / ctx.previewSize
// for shared reads, even though no other widget currently reads
// them -- the future PackDetail / Library hover-preview widgets
// will share the same handles.
@Widget(id = "bg.preview", displayName = "widget.bg.preview")
@Composable
fun BgPreviewWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { ctx.previewSize.value = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) {
                            val pos  = event.changes.firstOrNull()?.position
                            val size = ctx.previewSize.value
                            if (pos != null && size.width > 0 && size.height > 0) {
                                ctx.previewMousePos.value = Offset(pos.x / size.width, pos.y / size.height)
                            }
                        }
                    }
                }
            },
    ) {
        CustomBackground(settings = settings, mousePosProvider = { ctx.previewMousePos.value })
        Column(
            modifier            = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text       = s.backgroundPreview,
                style      = MaterialTheme.typography.labelSmall,
                color      = Color.White.copy(alpha = 0.5f),
                fontWeight = FontWeight.Bold,
            )
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(glassSurfaceAlpha(0.6f))
                        .border(1.dp, CelestiaTheme.colors.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Text(s.backgroundPreviewServer, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            text  = "1.21.1 • 42/100",
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CelestiaTheme.colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.launchButton, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
