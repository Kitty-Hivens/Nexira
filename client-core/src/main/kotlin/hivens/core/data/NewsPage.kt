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
    /**
     * True when this is what a source could still answer after the real one
     * could not be read -- a floor rather than the feed. It reads as one whole
     * page with nothing after it, so a surface that only checked [hasMore] would
     * take it for the entire archive and stop asking; this is how it can tell
     * the difference and offer a reload.
     */
    val fallback: Boolean = false,
) {
    /** True when a further page exists to ask for. */
    val hasMore: Boolean get() = page < totalPages
}
