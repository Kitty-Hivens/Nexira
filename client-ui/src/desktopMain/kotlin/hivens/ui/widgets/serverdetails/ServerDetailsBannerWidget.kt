package hivens.ui.widgets.serverdetails

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class ServerBannerProps(
    @PropLabel("widget.server.details.banner.cornerRadius") @PropRange(0.0, 32.0) val cornerRadius: Int = 16,
)

// Banner image (banner.png from the pack assets dir) or a missing
// hint when the file is absent. Fills its slot box, image scaled
// to crop so framing is consistent across asset sizes.
@Widget(id = "server.details.banner", displayName = "widget.server.details.banner", propsClass = ServerBannerProps::class)
@Composable
fun ServerDetailsBannerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ServerBannerProps>()
    val ctx = LocalServerDetailsContext.current
    val s = LocalStrings.current
    val banner by ctx.bannerImage

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(p.cornerRadius.dp))
            .background(NxTheme.colors.surface.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = NxTheme.colors.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(p.cornerRadius.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (banner != null) {
            Image(
                painter            = BitmapPainter(banner!!),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.serverDetailNoImage, color = NxTheme.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = s.serverDetailNoImageHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary.copy(alpha = 0.5f),
                )
            }
        }
    }
}
