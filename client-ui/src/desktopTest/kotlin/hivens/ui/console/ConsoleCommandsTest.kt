package hivens.ui.console

import hivens.ui.utils.LogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The router in GameConsoleService looks a typed line up by exact match, so a
 * duplicate or a capitalised key is not a compile error, it is a command that
 * silently shadows another or never fires at all. These assertions are the only
 * place that catches either.
 */
class ConsoleCommandsTest {

    private fun capture(command: ConsoleCommand): List<String> {
        val out = mutableListOf<String>()
        command.run { text, _ -> out += text }
        return out
    }

    private fun commands(
        withOverlay: Boolean = false,
        launcherIsBusy: Boolean = false,
        restartWorld: () -> Boolean = { false },
    ) = ConsoleCommands.builtIn(
        debugOverlayToggle = if (withOverlay) ({ }) else null,
        launcherIsBusy = { launcherIsBusy },
        restartWorld = restartWorld,
    )

    @Test
    fun `every key is unique and lowercase`() {
        val keys = commands(withOverlay = true).flatMap { it.keys }
        assertEquals(keys.size, keys.toSet().size, "two commands share a key: $keys")
        assertTrue(keys.all { it == it.lowercase() }, "the router lowercases the typed line before matching")
        assertTrue(keys.all { it == it.trim() && it.isNotEmpty() })
    }

    @Test
    fun `help lists the listed commands and admits it is not the whole story`() {
        val help = commands().single { it.name == "help" }
        val text = capture(help).joinToString("\n")
        commands().filter { it.listed }.forEach {
            assertTrue(it.name in text, "help omits ${it.name}")
        }
        assertTrue("not everything" in text, "the unlisted commands deserve a hint")
    }

    @Test
    fun `the unlisted commands stay out of help but still answer`() {
        val help = capture(commands().single { it.name == "help" }).joinToString("\n")
        val hidden = commands().filter { !it.listed }
        assertTrue(hidden.isNotEmpty())
        hidden.forEach { assertFalse(it.name in help, "${it.name} should not be advertised") }
        val stopTheWorld = hidden.single { "stop the reality!" in it.keys }
        val out = capture(stopTheWorld)
        assertTrue(out.any { "reality stopped" in it }, out.toString())
        assertTrue(out.any { "concurrent again" in it }, out.toString())
    }

    @Test
    fun `stopping everything restarts the process when there is a binary to restart`() {
        var restarts = 0
        val command = commands(restartWorld = { restarts += 1; true })
            .single { "stop the everything!" in it.keys }
        val out = capture(command)
        assertEquals(1, restarts)
        assertTrue(out.any { "reality restarts" in it }, out.toString())
        assertTrue(out.none { "not relaunchable" in it }, "the world did restart")
    }

    @Test
    fun `a dev run says so instead of pretending it restarted`() {
        val command = commands(restartWorld = { false }).single { "stop the everything!" in it.keys }
        assertTrue(capture(command).any { "not relaunchable" in it })
    }

    @Test
    fun `a launch in progress outranks the joke`() {
        var restarts = 0
        val command = commands(launcherIsBusy = true, restartWorld = { restarts += 1; true })
            .single { "stop the everything!" in it.keys }
        val out = capture(command)
        assertEquals(0, restarts, "the download and the unpack run in this process, not in the game's")
        assertTrue(out.any { "load bearing" in it }, out.toString())
    }

    @Test
    fun `the overlay command is absent on a build without the overlay`() {
        assertTrue(commands(withOverlay = false).none { it.name == "uidebug" })
        assertTrue(commands(withOverlay = true).any { it.name == "uidebug" })
    }

    @Test
    fun `the overlay command toggles exactly once per invocation`() {
        var toggles = 0
        val command = ConsoleCommands.builtIn(debugOverlayToggle = { toggles += 1 })
            .single { it.name == "uidebug" }
        command.run { _, _ -> }
        assertEquals(1, toggles)
    }

    @Test
    fun `only the command that touches compose state stays on the calling thread`() {
        val byThread = commands(withOverlay = true).groupBy { it.offThread }
        assertEquals(
            listOf("uidebug"),
            byThread[false].orEmpty().map { it.name },
            "the overlay toggle writes compose state, and everything else reads /proc or collects, "
                + "which must not happen on the ui thread",
        )
        assertTrue(byThread[true].orEmpty().isNotEmpty())
    }

    @Test
    fun `mem reports on the jvm running the test`() {
        val out = capture(commands().single { it.name == "mem" })
        assertTrue(out.any { it.contains("heap") }, out.toString())
        assertTrue(out.any { it.contains("gc") }, out.toString())
    }

    @Test
    fun `mem gc names the live set it just measured`() {
        val out = capture(commands().single { it.name == "mem gc" })
        assertTrue(out.any { "actually holds" in it }, out.toString())
    }

    @Test
    fun `a command that throws says so in the console instead of vanishing`() {
        val out = mutableListOf<Pair<String, LogType>>()
        val exploding = ConsoleCommand(name = "boom", summary = "") { error("nothing to report") }
        ConsoleCommands.runReporting(exploding) { text, type -> out += text to type }
        assertEquals(LogType.ERROR, out.single().second)
        assertTrue("boom failed" in out.single().first, out.toString())
        assertTrue("nothing to report" in out.single().first, out.toString())
    }

    @Test
    fun `the console severity of the joke is a warning and the rest is plain`() {
        val types = mutableListOf<LogType>()
        commands().single { "stop the reality!" in it.keys }.run { _, type -> types += type }
        assertEquals(LogType.WARN, types.first(), "stopping the world is worth a colour")
        assertTrue(types.drop(1).all { it == LogType.INFO })
    }
}
