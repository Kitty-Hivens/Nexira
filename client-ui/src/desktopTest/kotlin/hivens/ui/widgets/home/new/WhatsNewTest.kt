package hivens.ui.widgets.home.new

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.update.CompatChange
import hivens.core.update.PackUpdateStatus
import hivens.core.update.UpdateDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shelf names only what moved, most recently played first. */
class WhatsNewTest {

    private fun pack(id: String, played: Long) = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Local, id, "1.0"),
        displayName = id,
        instanceDirName = id,
        createdAtEpoch = 0L,
        lastPlayedEpochOrZero = played,
    )

    @Test
    fun `a pack with nothing new says nothing`() {
        val packs = listOf(pack("a", 10), pack("b", 20), pack("c", 30))
        val statuses = mapOf(
            "a" to PackUpdateStatus.UpToDate,
            "b" to PackUpdateStatus.Checking,
            "c" to PackUpdateStatus.Failed("offline"),
        )
        assertTrue(packNews(packs, statuses).isEmpty())
    }

    @Test
    fun `waiting and taken builds are listed, the last played first`() {
        val packs = listOf(pack("old", 10), pack("new", 50), pack("quiet", 90))
        val pending = PackUpdateStatus.Pending("2.0", UpdateDirection.Newer, CompatChange.Same)
        val statuses = mapOf(
            "old" to PackUpdateStatus.Updated("1.1"),
            "new" to pending,
            "quiet" to PackUpdateStatus.UpToDate,
        )
        assertEquals(listOf("new", "old"), packNews(packs, statuses).map { it.first.id })
    }
}
