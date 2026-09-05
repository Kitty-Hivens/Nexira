package hivens.core.data

/**
 * One entry of the upstream news feed, carrying values rather than rendered
 * text: the launcher's own language decides how a date and a count read, and
 * the fetch layer does not know it. [dateEpochSeconds] is 0 when upstream sent
 * nothing usable, which reads as "no date" rather than as 1970.
 */
data class NewsItem(
    val id: Int = 0,
    val title: String = "",
    val views: Int = 0,
    val dateEpochSeconds: Long = 0,
    /** The entry's image at the size the site publishes it. */
    val imageUrl: String? = null,
    /**
     * The same image as the thumbnail the site keeps beside it, several times
     * smaller on the wire. Both travel because the size that should be fetched
     * is the surface's call, not the feed's: a row 38dp tall wants the small
     * one, and a surface that shows the image large would be handed something
     * to upscale. Null when the source has no thumbnail for the entry.
     */
    val thumbnailUrl: String? = null,
)
