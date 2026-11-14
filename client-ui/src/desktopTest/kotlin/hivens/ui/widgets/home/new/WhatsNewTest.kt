package hivens.ui.widgets.home.new

import hivens.packs.api.PackSource
import hivens.packs.api.PackUpdateView
import hivens.packs.api.PackView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shelf names only what moved, most recently played first. */
class WhatsNewTest {

    private fun pack(id: String, played: Long) = PackView(
        id = id,
        name = id,
        source = PackSource("local", id, "1.0"),
        folder = id,
        createdAtEpochSecond = 0L,
        lastPlayedEpochSecond = played,
    )

    @Test
    fun `a pack with nothing new says nothing`() {
        val packs = listOf(pack("a", 10), pack("b", 20), pack("c", 30))
        val statuses = mapOf(
            "a" to PackUpdateView.UpToDate,
            "b" to PackUpdateView.Checking,
            "c" to PackUpdateView.Failed("offline"),
        )
        assertTrue(packNews(packs, statuses).isEmpty())
    }

    @Test
    fun `waiting and taken builds are listed, the last played first`() {
        val packs = listOf(pack("old", 10), pack("new", 50), pack("quiet", 90))
        val pending = PackUpdateView.Pending("2.0", PackUpdateView.Pending.NEWER, "same")
        val statuses = mapOf(
            "old" to PackUpdateView.Updated("1.1"),
            "new" to pending,
            "quiet" to PackUpdateView.UpToDate,
        )
        assertEquals(listOf("new", "old"), packNews(packs, statuses).map { it.first.id })
    }
}
