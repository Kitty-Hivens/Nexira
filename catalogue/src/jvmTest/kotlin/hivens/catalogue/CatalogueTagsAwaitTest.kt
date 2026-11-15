package hivens.catalogue

import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.CategoryTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What a tag clicked on a project page gets when it asks for the catalogue's lists. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueTagsAwaitTest {

    /** The catalogue as an offline launcher reaches it. */
    private val offline: suspend () -> CatalogueTags = { throw IOException("offline") }

    @Test
    fun `lists already read are handed over at once`() = runTest {
        val known = CatalogueTags(categories = listOf(CategoryTag("64x", projectType = "resourcepack", header = "resolutions")))
        val tags = CatalogueTagsService(offline, backgroundScope, initial = known)

        assertSame(known, tags.await(3.seconds))
        assertTrue(currentTime == 0L, "nothing was waited for")
    }

    @Test
    fun `a read that fails leaves the tag without the lists`() = runTest {
        val tags = CatalogueTagsService(offline, backgroundScope)

        assertNull(tags.await(3.seconds), "without the lists the tag searches as it is")
    }
}
