package hivens.core.api.interfaces

import hivens.core.data.NewsPage

/**
 * The news feed behind the launcher's news surfaces, read a page at a time.
 *
 * Paged because the source is an archive rather than a headline: the dashboard
 * payload the launcher used to read carries three entries and nothing more, so
 * a widget asked to show twenty had nothing to draw from. A page is fetched when
 * something needs it -- opening the rail costs one page, and the rest arrive as
 * the reader scrolls.
 */
interface INewsFeed {
    /**
     * News [page], 1-based, newest first. An unreachable or unreadable source
     * returns an empty page rather than throwing, so a rail that cannot load its
     * feed shows its empty state instead of taking the surface down with it.
     *
     * [forceRefresh] goes past whatever the implementation caches. It belongs to
     * a reload the user asked for -- answering "try again" out of the entry that
     * just failed to produce anything is not a retry.
     */
    suspend fun page(page: Int, forceRefresh: Boolean = false): NewsPage
}
