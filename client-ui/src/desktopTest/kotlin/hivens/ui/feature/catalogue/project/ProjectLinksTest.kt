package hivens.ui.feature.catalogue.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which catalogue links open inside the app, and as what. */
class ProjectLinksTest {

    @Test
    fun `a project link names its slug, whatever follows it`() {
        assertEquals(ModrinthLink("sodium", modpack = false), modrinthProjectLink("https://modrinth.com/mod/sodium"))
        assertEquals(ModrinthLink("faithful", modpack = false), modrinthProjectLink("https://www.modrinth.com/resourcepack/faithful/versions?g=1.21"))
        assertEquals("iris", modrinthProjectSlug("modrinth.com/shader/iris#about"))
    }

    /** A modpack has a page of its own, where it is installed as a pack rather than into one. */
    @Test
    fun `a modpack link says it is a modpack`() {
        assertEquals(ModrinthLink("fabulously-optimized", modpack = true), modrinthProjectLink("https://modrinth.com/modpack/fabulously-optimized"))
    }

    @Test
    fun `anything else is not followed into the app`() {
        assertNull(modrinthProjectLink("https://modrinth.com/user/someone"))
        assertNull(modrinthProjectLink("https://modrinth.com/mod"))
        assertNull(modrinthProjectLink("https://example.com/mod/sodium"))
    }
}
