package hivens.launcher.catalogue

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackCatalogueService
import hivens.core.cache.Cache
import hivens.core.cache.CacheValue
import hivens.core.cache.Freshness
import hivens.core.data.PackOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CachedPackCatalogueTest {

    /** Records the keys it is asked for and hands back whatever it was seeded with. */
    private class RecordingCache(
        private val stored: MutableMap<String, List<CataloguePack>> = mutableMapOf(),
    ) : Cache<List<CataloguePack>> {
        val asked = mutableListOf<String>()
        var loaderCalls = 0

        override suspend fun get(key: String, loader: suspend () -> List<CataloguePack>): List<CataloguePack> {
            asked += key
            return stored.getOrPut(key) { loaderCalls++; loader() }
        }

        override suspend fun refresh(key: String, loader: suspend () -> List<CataloguePack>): List<CataloguePack> {
            asked += key
            loaderCalls++
            return loader().also { stored[key] = it }
        }

        override fun flow(key: String, loader: suspend () -> List<CataloguePack>): Flow<CacheValue<List<CataloguePack>>> = flow {
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

    private class FakeCatalogue(
        override val origin: PackOrigin,
        private val results: List<CataloguePack>,
    ) : IPackCatalogueService {
        var searches = 0
        override suspend fun search(query: String, page: Int): List<CataloguePack> {
            searches++
            return results
        }
        override suspend fun details(packId: String): CataloguePackDetails = error("not used")
        override suspend fun versions(packId: String): List<CataloguePackVersion> = error("not used")
    }

    private fun pack(id: String, origin: PackOrigin = PackOrigin.Modrinth) =
        CataloguePack(origin = origin, id = id, title = id, tagline = "")

    @Test fun `a repeated search does not reach the source again`() = runTest {
        val source = FakeCatalogue(PackOrigin.Modrinth, listOf(pack("create")))
        val catalogue = CachedPackCatalogue(source, RecordingCache())

        catalogue.search("create", page = 0)
        catalogue.search("create", page = 0)

        assertEquals(1, source.searches)
    }

    @Test fun `two sources asked the same words are two different answers`() = runTest {
        val cache = RecordingCache()
        val mirror = FakeCatalogue(PackOrigin.Mirror, listOf(pack("industrial", PackOrigin.Mirror)))
        val modrinth = FakeCatalogue(PackOrigin.Modrinth, listOf(pack("create")))

        val fromMirror = CachedPackCatalogue(mirror, cache).search("create", page = 0)
        val fromModrinth = CachedPackCatalogue(modrinth, cache).search("create", page = 0)

        assertEquals(listOf("industrial"), fromMirror.map { it.id })
        assertEquals(listOf("create"), fromModrinth.map { it.id })
        assertEquals(2, cache.asked.distinct().size, "one key for both sources would serve one source's list to the other")
    }

    @Test fun `whitespace and case are not a different question`() = runTest {
        val source = FakeCatalogue(PackOrigin.Modrinth, listOf(pack("create")))
        val catalogue = CachedPackCatalogue(source, RecordingCache())

        catalogue.search("Create", page = 0)
        catalogue.search("  create ", page = 0)

        assertEquals(1, source.searches)
    }

    @Test fun `a page is part of the key`() = runTest {
        val cache = RecordingCache()
        val catalogue = CachedPackCatalogue(FakeCatalogue(PackOrigin.Modrinth, listOf(pack("create"))), cache)

        catalogue.search("create", page = 0)
        catalogue.search("create", page = 1)

        assertEquals(2, cache.asked.distinct().size, "page two answered from page one's entry would repeat it forever")
    }

    @Test fun `the stream hands over the stale list and then the fresh one`() = runTest {
        val cache = RecordingCache()
        val source = FakeCatalogue(PackOrigin.Modrinth, listOf(pack("create")))
        val catalogue = CachedPackCatalogue(source, cache)

        catalogue.search("create", page = 0)
        val emissions = catalogue.searchStream("create", page = 0).toList()

        assertEquals(2, emissions.size, "a warm entry must reach the screen before the refresh does")
        assertEquals(listOf("create"), emissions.first().map { it.id })
    }
}
