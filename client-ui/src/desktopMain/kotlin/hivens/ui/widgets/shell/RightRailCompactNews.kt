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

/**
 * Which of the two images upstream publishes for an entry the rows fetch.
 * Thumbnail is what a 38dp row needs and several times less to download; Full
 * is the size the site shows on its own page, for a display scaled far enough
 * up that the small one would be stretched.
 */
@Serializable
enum class NewsImageSource { Thumbnail, Full }

@Serializable
data class CompactNewsProps(
    // 0 = show the whole feed; > 0 caps it (after the title filter). The default is a
    // handful rather than everything: the feed pages in the whole archive as the
    // reader reaches the end of it, so "all" fills the rail with years of entries and
    // keeps fetching pages nobody asked for.
    @PropLabel("widget.appshell.rightrail.compactnews.maxItems") @PropRange(0.0, 50.0)
    val maxItems: Int = 4,
    @PropLabel("widget.appshell.rightrail.compactnews.showTitle") val showTitle: Boolean = true,
    @PropLabel("widget.appshell.rightrail.compactnews.imageSource")
    val imageSource: NewsImageSource = NewsImageSource.Thumbnail,
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
        sslBypass   = ctx.sslBypass,
        maxItems    = props.maxItems,
        showTitle   = props.showTitle,
        imageSource = props.imageSource,
        modifier    = Modifier.fillMaxSize(),
    )
}
