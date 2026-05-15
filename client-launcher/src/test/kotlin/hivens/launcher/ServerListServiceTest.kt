package hivens.launcher

import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyServer
import hivens.core.api.protocol.LoaderResponse
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #189 — fetchDashboardData must single-flight: when N coroutines hit it
 * concurrently on a cold cache, only one underlying repo fetch should fire.
 * The pre-fix `@Volatile` stopgap allowed all N to slip past the null check
 * and each kick off their own future.
 */
class ServerListServiceTest {

    @Test
    fun `concurrent fetchDashboardData fires the underlying repo only once`() = runBlocking {
        val protocol = FakeServerProtocol().apply {
            // Make the fake suspend long enough that the first request is
            // definitely still in-flight when subsequent callers arrive,
            // forcing the single-flight code path.
            loaderResult = {
                Thread.sleep(50)
                LoaderResponse(status = "OK", servers = listOf(SmartyServer(id = "Industrial", ip = "127.0.0.1")))
            }
        }
        val repo = ServerRepository(protocol)
        val svc = ServerListService(repo)

        val parallel = 32
        val results = coroutineScope {
            (1..parallel).map { async { svc.fetchDashboardData().await() } }.awaitAll()
        }

        assertEquals(parallel, results.size)
        assertEquals(1, protocol.loaderCalls.size, "expected single underlying fetch under contention")
    }

    @Test
    fun `cached result is shared across callers (identity)`() = runBlocking {
        val protocol = FakeServerProtocol().apply {
            // Cache only memoizes a non-empty result — give it something to bite on.
            loaderResult = { LoaderResponse(status = "OK", servers = listOf(SmartyServer(id = "Industrial", ip = "127.0.0.1"))) }
        }
        val svc = ServerListService(ServerRepository(protocol))

        val first = svc.fetchDashboardData().await()
        val second = svc.fetchDashboardData().await()

        assertEquals(1, protocol.loaderCalls.size, "second call should hit the cache, not the repo")
        assertSame(first, second, "second call should return the cached instance")
    }

    @Test
    fun `empty fetch is not cached so the next call still tries`() = runBlocking {
        // fetchDashboard returns empty servers when the repo errors. The cache
        // should NOT memoize that empty state — otherwise a transient outage
        // freezes the dashboard at empty until launcher restart.
        val protocol = FakeServerProtocol().apply {
            loaderResult = { LoaderResponse(status = "ERROR") }
        }
        val svc = ServerListService(ServerRepository(protocol))

        svc.fetchDashboardData().await()
        delay(10)
        svc.fetchDashboardData().await()

        assertEquals(2, protocol.loaderCalls.size, "empty result must not be cached")
    }
}
