package hivens.ui.widgets.home.new

import hivens.packs.api.PackSource
import hivens.packs.api.PackView
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which pack the home widgets lead with. */
class RecentFirstTest {

    private fun pack(id: String, installed: Long, played: Long = 0L) = PackView(
        id = id,
        name = id,
        source = PackSource("local", id, "1.0"),
        folder = id,
        createdAtEpochSecond = installed,
        lastPlayedEpochSecond = played,
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
