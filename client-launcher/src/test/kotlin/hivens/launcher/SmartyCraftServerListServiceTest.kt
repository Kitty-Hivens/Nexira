package hivens.launcher

import hivens.core.api.ServerRepository
import hivens.core.api.dto.SmartyServer
import hivens.core.api.protocol.LoaderResponse
import hivens.core.cache.Cache
import hivens.core.cache.CacheConfig
import hivens.core.cache.DefaultCache
import hivens.core.cache.NoOpDiskStore
import hivens.core.data.DashboardData
import hivens.core.time.SystemClock
import hivens.test.FakeServerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/**
 * #189 -- fetchDashboardData must single-flight: when N coroutines hit it
 * concurrently on a cold cache, only one underlying repo fetch should fire.
 * The pre-fix `@Volatile` stopgap allowed all N to slip past the null check
 * and each kick off their own future.
 */
class SmartyCraftServerListServiceTest {

    // The single-flight + in-memory dedup now live in the injected cache (the
    // service's own field is gone), so the tests provide a real in-memory one.
    private fun memCache(): Cache<DashboardData> = DefaultCache(
        diskStore = NoOpDiskStore(),
        config = CacheConfig(ttlMs = Long.MAX_VALUE / 2, shouldStore = { it.servers.isNotEmpty() }),
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        clock = SystemClock,
        namespace = "dashboard-test",
    )

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
        val svc = SmartyCraftServerListService(repo, dashboardCache = memCache())

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
            // Cache only memorizes a non-empty result -- give it something to bite on.
            loaderResult = { LoaderResponse(status = "OK", servers = listOf(SmartyServer(id = "Industrial", ip = "127.0.0.1"))) }
        }
        val svc = SmartyCraftServerListService(ServerRepository(protocol), dashboardCache = memCache())

        val first = svc.fetchDashboardData().await()
        val second = svc.fetchDashboardData().await()

        assertEquals(1, protocol.loaderCalls.size, "second call should hit the cache, not the repo")
        assertSame(first, second, "second call should return the cached instance")
    }

    @Test
    fun `empty fetch is not cached so the next call still tries`() = runBlocking {
        // fetchDashboard returns empty servers when the repo errors. The cache
        // should NOT memoize that empty state -- otherwise a transient outage
        // freezes the dashboard at empty until launcher restart.
        val protocol = FakeServerProtocol().apply {
            loaderResult = { LoaderResponse(status = "ERROR") }
        }
        val svc = SmartyCraftServerListService(ServerRepository(protocol), dashboardCache = memCache())

        svc.fetchDashboardData().await()
        delay(10.milliseconds)
        svc.fetchDashboardData().await()

        assertEquals(2, protocol.loaderCalls.size, "empty result must not be cached")
    }

    private class RecordingStore : ServerListCacheStore {
        val saved = mutableListOf<List<hivens.core.api.model.ServerProfile>>()
        override fun load(): List<hivens.core.api.model.ServerProfile> = emptyList()
        override suspend fun save(servers: List<hivens.core.api.model.ServerProfile>) { saved += servers }
    }

    @Test
    fun `tray seed is written on success but not on an empty result`() = runBlocking {
        val ok = FakeServerProtocol().apply {
            loaderResult = { LoaderResponse(status = "OK", servers = listOf(SmartyServer(id = "Industrial", ip = "127.0.0.1"))) }
        }
        val store = RecordingStore()
        SmartyCraftServerListService(ServerRepository(ok), cache = store, dashboardCache = memCache())
            .fetchDashboardData().await()
        assertEquals(1, store.saved.size, "a successful fetch seeds the tray cache")
        assertEquals("Industrial", store.saved.single().single().assetDir)

        val down = FakeServerProtocol().apply { loaderResult = { LoaderResponse(status = "ERROR") } }
        val store2 = RecordingStore()
        SmartyCraftServerListService(ServerRepository(down), cache = store2, dashboardCache = memCache())
            .fetchDashboardData().await()
        assertEquals(0, store2.saved.size, "an empty/failed fetch must not overwrite the tray seed")
    }
}
