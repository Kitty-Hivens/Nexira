package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.core.api.interfaces.INewsFeed
import hivens.launcher.news.CuratedNewsFeed
import hivens.launcher.news.SyndicationNewsFeed
import hivens.ui.CompactNewsFeed
import hivens.ui.i18n.LocalStrings
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Which of the two images upstream publishes for an entry the rows fetch.
 * Thumbnail is what a 38dp row needs and several times less to download; Full
 * is the size the site shows on its own page, for a display scaled far enough
 * up that the small one would be stretched.
 */
@Serializable
enum class NewsImageSource { Thumbnail, Full }

/**
 * Which feed the rail reads.
 *
 * [Upstream] is the launcher's own: a site it is a client of, whose entries open
 * there. The other two are channels the launcher makes no claim about, so both
 * carry the same narrow policy -- rows that open nowhere, nothing but their text
 * fetched, and a handful drawn at random rather than a fixed newest-first order.
 * That policy belongs to the channel and not to this widget; see
 * [hivens.core.data.NewsChannelPolicy].
 *
 * [Curated] is a pool of lines that ships with the launcher, so it works with
 * nothing configured and shows a deliberate selection rather than whatever a site
 * published this morning. [Custom] is an RSS or Atom address the reader put in
 * settings, which is the one that needs configuring and says so when it has not
 * been.
 */
@Serializable
enum class NewsChannelKind { Upstream, Curated, Custom }

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
    @PropLabel("widget.appshell.rightrail.compactnews.channel")
    val channel: NewsChannelKind = NewsChannelKind.Upstream,
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
    val s = LocalStrings.current
    // Each resolved by its own concrete type: registering either as a second
    // INewsFeed would make "the news feed" ambiguous everywhere else in the graph.
    val upstream: INewsFeed = koinInject()
    val curated: CuratedNewsFeed = koinInject()
    val custom: SyndicationNewsFeed = koinInject()
    CompactNewsFeed(
        sslBypass   = ctx.sslBypass,
        maxItems    = props.maxItems,
        showTitle   = props.showTitle,
        imageSource = props.imageSource,
        feed        = when (props.channel) {
            NewsChannelKind.Upstream -> upstream
            NewsChannelKind.Curated  -> curated
            NewsChannelKind.Custom   -> custom
        },
        // A custom channel with no address in settings is not an empty feed, it is
        // one that was never pointed anywhere: say that instead of offering a retry
        // that cannot succeed. The curated pool needs no address, so it never lands
        // here unless the pool itself is missing from the build.
        unavailable = s.newsAltNoAddress.takeIf {
            props.channel == NewsChannelKind.Custom && !custom.configured
        },
        modifier    = Modifier.fillMaxSize(),
    )
}
