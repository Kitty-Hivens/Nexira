package hivens.source.modrinth

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.ProjectBuild
import hivens.core.cache.Cache
import hivens.core.cache.CacheValue
import hivens.core.cache.Freshness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CachedPackListingTest {

    /** Records the keys it is asked for and hands back whatever it was seeded with. */
    private class RecordingCache(
        private val stored: MutableMap<String, List<CatalogueCard>> = mutableMapOf(),
    ) : Cache<List<CatalogueCard>> {
        val asked = mutableListOf<String>()
        var loaderCalls = 0

        override suspend fun get(key: String, loader: suspend () -> List<CatalogueCard>): List<CatalogueCard> {
            asked += key
            return stored.getOrPut(key) { loaderCalls++; loader() }
        }

        override suspend fun refresh(key: String, loader: suspend () -> List<CatalogueCard>): List<CatalogueCard> {
            asked += key
            loaderCalls++
            return loader().also { stored[key] = it }
        }

        override fun flow(key: String, loader: suspend () -> List<CatalogueCard>): Flow<CacheValue<List<CatalogueCard>>> = flow {
            asked += key
            stored[key]?.let { emit(CacheValue(it, Freshness.STALE)) }
            loaderCalls++
            val fresh = loader()
            stored[key] = fresh
            emit(CacheValue(fresh, Freshness.FRESH))
        }

        override suspend fun invalidate(key: String) { stored.remove(key) }
        override suspend fun invalidateAll() { stored.clear() }
    }

    private class FakeListing(private val results: List<CatalogueCard>) : PackListing {
        var searches = 0
        override suspend fun search(query: String, page: Int): List<CatalogueCard> {
            searches++
            return results
        }
        override suspend fun details(packId: String): PackDetails = error("not used")
        override suspend fun versions(packId: String): List<ProjectBuild> = error("not used")
    }

    private fun pack(id: String, origin: String = "Modrinth") = CatalogueCard(origin = origin, id = id, title = id, tagline = "")

    @Test fun `a repeated search does not reach the source again`() = runTest {
        val source = FakeListing(listOf(pack("create")))
        val listing = CachedPackListing(source, "Modrinth", RecordingCache())

        listing.search("create", page = 0)
        listing.search("create", page = 0)

        assertEquals(1, source.searches)
    }

    @Test fun `two sources asked the same words are two different answers`() = runTest {
        val cache = RecordingCache()
        val shelf = FakeListing(listOf(pack("industrial", "Shelf")))
        val modrinth = FakeListing(listOf(pack("create")))

        val fromShelf = CachedPackListing(shelf, "Shelf", cache).search("create", page = 0)
        val fromModrinth = CachedPackListing(modrinth, "Modrinth", cache).search("create", page = 0)

        assertEquals(listOf("industrial"), fromShelf.map { it.id })
        assertEquals(listOf("create"), fromModrinth.map { it.id })
        assertEquals(2, cache.asked.distinct().size, "one key for both sources would serve one source's list to the other")
    }

    @Test fun `whitespace and case are not a different question`() = runTest {
        val source = FakeListing(listOf(pack("create")))
        val listing = CachedPackListing(source, "Modrinth", RecordingCache())

        listing.search("Create", page = 0)
        listing.search("  create ", page = 0)

        assertEquals(1, source.searches)
    }

    @Test fun `a page is part of the key`() = runTest {
        val cache = RecordingCache()
        val listing = CachedPackListing(FakeListing(listOf(pack("create"))), "Modrinth", cache)

        listing.search("create", page = 0)
        listing.search("create", page = 1)

        assertEquals(2, cache.asked.distinct().size, "page two answered from page one's entry would repeat it forever")
    }

    @Test fun `the stream hands over the stale list and then the fresh one`() = runTest {
        val cache = RecordingCache()
        val listing = CachedPackListing(FakeListing(listOf(pack("create"))), "Modrinth", cache)

        listing.search("create", page = 0)
        val emissions = listing.searchStream("create", page = 0).toList()

        assertEquals(2, emissions.size, "a warm entry must reach the screen before the refresh does")
        assertEquals(listOf("create"), emissions.first().map { it.id })
    }
}
