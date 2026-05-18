package hivens.core.api.interfaces

import hivens.core.data.DashboardData
import java.util.concurrent.CompletableFuture

interface IServerListService {
    /**
     * Receives complete data for the dashboard: servers + news.
     * May return a cached snapshot from a prior successful fetch in this
     * session; for an unconditional re-fetch from upstream (e.g. user
     * explicitly clicked Retry after network recovery), use [refresh].
     */
    fun fetchDashboardData(): CompletableFuture<DashboardData>

    /**
     * Invalidate the in-memory cache and fetch fresh from upstream.
     * Equivalent to [fetchDashboardData] on a cold session, but skips
     * the cache short-circuit so a user-driven retry actually hits the
     * network. Used by the dashboard "Retry" button and by
     * `CompactNewsFeed`'s empty-state refetch button.
     */
    fun refresh(): CompletableFuture<DashboardData>
}
