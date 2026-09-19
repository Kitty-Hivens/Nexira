package hivens.launcher.news

import hivens.core.api.dto.SmartyNews
import hivens.core.data.NewsItem

/**
 * The dashboard payload's news entries as the launcher's own.
 *
 * The upstream carries an image basename and leaves the extension off about
 * half the time; both sizes are built here because the size that should be
 * fetched is the surface's call, not the feed's.
 */
internal fun SmartyNews.toNewsItem(baseUrl: String): NewsItem {
    val imageName = if (image.endsWith(".jpg")) image else "$image.jpg"
    return NewsItem(
        id = id,
        title = name,
        views = views,
        dateEpochSeconds = date,
        imageUrl = "$baseUrl/images/news/$imageName",
        thumbnailUrl = "$baseUrl/images/news/mini/$imageName",
    )
}
