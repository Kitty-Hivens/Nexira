package hivens.ui.widgets.home.new

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which pack the home widgets lead with. */
class RecentFirstTest {

    private fun pack(id: String, installed: Long, played: Long = 0L) = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Local, id, "1.0"),
        displayName = id,
        instanceDirName = id,
        createdAtEpoch = installed,
        lastPlayedEpochOrZero = played,
    )

    @Test
    fun `with nothing played yet the newest install leads`() {
        val packs = listOf(pack("first", installed = 10), pack("newest", installed = 30), pack("middle", installed = 20))

        assertEquals("newest", packs.minWithOrNull(RecentFirst)?.id)
        assertEquals(listOf("newest", "middle", "first"), packs.sortedWith(RecentFirst).map { it.id })
    }

    @Test
    fun `a played pack leads over a newer install`() {
        val packs = listOf(pack("fresh", installed = 90), pack("played", installed = 10, played = 50))

        assertEquals(listOf("played", "fresh"), packs.sortedWith(RecentFirst).map { it.id })
    }
}
