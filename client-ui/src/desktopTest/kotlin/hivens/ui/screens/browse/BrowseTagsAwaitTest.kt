package hivens.ui.screens.browse

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.net.TransferEngine
import hivens.launcher.modrinth.ModrinthClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What a tag clicked on a project page gets when it asks for the catalogue's lists. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseTagsAwaitTest {

    private val http = HttpClientProvider { error("offline") }
    private val modrinth = ModrinthClient(http, TransferEngine(http))

    @Test
    fun `lists already read are handed over at once`() = runTest {
        val known = BrowseTags.Tags(emptyList(), listOf(ModrinthCategoryTag("64x", projectType = "resourcepack", header = "resolutions")), emptyList())
        val tags = BrowseTags(modrinth, backgroundScope, initial = known)

        assertSame(known, tags.await(3.seconds))
        assertTrue(currentTime == 0L, "nothing was waited for")
    }

    @Test
    fun `a read that fails leaves the tag without the lists`() = runTest {
        val tags = BrowseTags(modrinth, backgroundScope)

        assertNull(tags.await(3.seconds), "without the lists the tag searches as it is")
    }
}
