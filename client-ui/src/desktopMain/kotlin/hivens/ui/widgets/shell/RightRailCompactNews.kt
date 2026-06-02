package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.CompactNewsFeed
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "appshell.rightrail.compactnews", displayName = "widget.appshell.rightrail.compactnews")
@Composable
fun RightRailCompactNews(instance: WidgetInstance) {
    val ctx = LocalRightRailContext.current
    CompactNewsFeed(
        sslBypass = ctx.sslBypass,
        modifier  = Modifier.fillMaxSize(),
    )
}
