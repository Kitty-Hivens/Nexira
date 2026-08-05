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
    val imageUrl: String? = null,
)
