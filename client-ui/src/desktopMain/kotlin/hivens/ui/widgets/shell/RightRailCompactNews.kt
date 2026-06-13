package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.CompactNewsFeed
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class CompactNewsProps(
    // 0 = show the whole feed; > 0 caps it (after the title filter).
    @PropLabel("widget.appshell.rightrail.compactnews.maxItems") @PropRange(0.0, 50.0)
    val maxItems: Int = 0,
)

@Widget(
    id = "appshell.rightrail.compactnews",
    displayName = "widget.appshell.rightrail.compactnews",
    propsClass = CompactNewsProps::class,
)
@Composable
fun RightRailCompactNews(instance: WidgetInstance) {
    val ctx = LocalRightRailContext.current
    val props = instance.rememberProps<CompactNewsProps>()
    CompactNewsFeed(
        sslBypass = ctx.sslBypass,
        maxItems  = props.maxItems,
        modifier  = Modifier.fillMaxSize(),
    )
}
