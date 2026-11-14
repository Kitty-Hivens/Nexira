package hivens.launcher.update

import hivens.core.update.CompatChange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fingerprint is what a reattach holds the player's confirmation to. Whatever the
 * dialog lists under any heading has to be in it, or a change under that heading
 * after the player read the list goes through on the old confirmation.
 */
class ReattachPreviewTest {

    private val base = ReattachPreview(
        build = "b1",
        toVersion = "1.0",
        compat = CompatChange.Same,
        removed = listOf("mods/a.jar"),
        restored = listOf("mods/b.jar"),
        replaced = listOf("mods/c.jar"),
        turnedOn = listOf("mods/d.jar"),
        turnedOff = listOf("mods/e.jar"),
        kept = listOf("config/f.txt"),
        basis = ReattachBasis.Full,
        unreadable = listOf("mods/g.jar"),
    )

    @Test
    fun `every heading and the build take part in the fingerprint`() {
        val changed = mapOf(
            "build" to base.copy(build = "b2"),
            "version" to base.copy(toVersion = "1.1"),
            "compat" to base.copy(compat = CompatChange.LoaderBump),
            "removed" to base.copy(removed = base.removed + "mods/x.jar"),
            "restored" to base.copy(restored = base.restored + "mods/x.jar"),
            "replaced" to base.copy(replaced = base.replaced + "mods/x.jar"),
            "turnedOn" to base.copy(turnedOn = base.turnedOn + "mods/x.jar"),
            "turnedOff" to base.copy(turnedOff = base.turnedOff + "mods/x.jar"),
            "kept" to base.copy(kept = base.kept + "config/x.txt"),
            "basis" to base.copy(basis = ReattachBasis.NoBaseline),
            "unreadable" to base.copy(unreadable = base.unreadable + "mods/x.jar"),
        )
        val same = changed.filterValues { it.fingerprint == base.fingerprint }.keys
        assertEquals(emptySet(), same, "a change under these leaves the fingerprint as it was")
    }

    @Test
    fun `a path moved from one heading to another changes the fingerprint`() {
        val moved = base.copy(removed = emptyList(), replaced = base.replaced + base.removed)
        assertEquals(false, moved.fingerprint == base.fingerprint)
    }

    @Test
    fun `the order the lists come in does not`() {
        val reordered = base.copy(removed = listOf("mods/z.jar", "mods/a.jar"))
        val sorted = base.copy(removed = listOf("mods/a.jar", "mods/z.jar"))
        assertEquals(sorted.fingerprint, reordered.fingerprint)
    }
}
