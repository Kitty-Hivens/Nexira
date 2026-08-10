package hivens.core.data

/**
 * One page of the upstream news feed, newest first.
 *
 * [page] and [totalPages] are the upstream's own paging, carried through rather
 * than flattened into a "has more" flag: a feed that knows how many pages it has
 * can say where the archive ends, and a page that arrives from a source with no
 * paging at all is simply page 1 of 1.
 */
data class NewsPage(
    val items: List<NewsItem> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
) {
    /** True when a further page exists to ask for. */
    val hasMore: Boolean get() = page < totalPages
}
