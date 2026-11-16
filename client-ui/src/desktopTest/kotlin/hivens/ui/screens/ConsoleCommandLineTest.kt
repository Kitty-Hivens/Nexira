package hivens.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.DeleteAllCommand
import hivens.launcher.platform.PlatformPaths
import hivens.themes.Themes
import hivens.ui.TypingScene
import hivens.ui.host.HostKoin
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.onEventThread
import hivens.ui.theme.NxTheme
import hivens.ui.utils.ConsoleSettings
import hivens.ui.utils.GameConsoleService
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The console's command line while a game runs: what reaches the game on Enter, the
 * history the arrow keys walk, and Escape clearing what is typed.
 */
class ConsoleCommandLineTest {

    private fun console(body: TypingScene.(sent: List<String>) -> Unit) {
        val dir = Files.createTempDirectory("console-line-")
        val paths = PlatformPaths(
            osName = "linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        val service = GameConsoleService(paths)
        val sent = CopyOnWriteArrayList<String>()
        service.attachCommandSink { sent += it }
        val koin = koinApplication { modules(module { single { service } }) }
        try {
            onEventThread {
                TypingScene(800, 400) {
                    HostKoin(koin) {
                        ShippedLanguageProvider("en") {
                            NxTheme(Themes.default, dark = true) {
                                ConsoleContent(ConsoleSettings(), onSettingsChange = {}, source = ConsoleSource.Live)
                            }
                        }
                    }
                }.use { scene ->
                    scene.settle()
                    // The command line is the console's one text field while its search is closed.
                    scene.focusField()
                    scene.body(sent)
                }
            }
        } finally {
            koin.close()
            runBlocking { service.close() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Enter sends the line and empties it`() = console { sent ->
        commit("say hi")
        press(Key.Enter)
        settle()
        assertEquals(listOf("say hi"), sent)
        assertEquals("", shown)
        commit("list")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(listOf("say hi", "list"), sent)
    }

    @Test
    fun `Enter on a blank line sends nothing`() = console { sent ->
        commit("   ")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `the arrows walk the history newest first and back down to an empty line`() = console { sent ->
        for (command in listOf("time set day", "weather clear", "list")) {
            commit(command)
            settle()
            press(Key.Enter)
            settle()
        }
        press(Key.DirectionUp)
        settle()
        assertEquals("list", shown)
        press(Key.DirectionUp)
        settle()
        assertEquals("weather clear", shown)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        settle()
        assertEquals("time set day", shown, "the walk stops at the oldest")
        press(Key.DirectionDown)
        settle()
        assertEquals("weather clear", shown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        settle()
        assertEquals("", shown, "past the newest the line is empty again")

        press(Key.DirectionUp)
        settle()
        press(Key.Enter)
        settle()
        assertEquals(listOf("time set day", "weather clear", "list", "list"), sent, "a recalled line is sent as it is")
    }

    @Test
    fun `typing over a recalled line starts the walk again from the newest`() = console { _ ->
        for (command in listOf("first", "second")) {
            commit(command)
            settle()
            press(Key.Enter)
            settle()
        }
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        settle()
        assertEquals("first", shown)
        edit(DeleteAllCommand())
        commit("third")
        settle()
        press(Key.DirectionUp)
        settle()
        assertEquals("second", shown)
    }

    @Test
    fun `Escape clears what is typed and keeps the line`() = console { _ ->
        commit("stop")
        settle()
        assertTrue(press(Key.Escape), "the line used the key")
        settle()
        assertEquals("", shown)
        assertFalse(press(Key.Escape), "on an empty line the key goes on to the window")
    }

    @Test
    fun `a word from an input method is sent whole on the Enter right behind it`() = console { sent ->
        compose("サ")
        settle()
        assertTrue(composing)
        compose("サーバ")
        commit("サーバー")
        press(Key.Enter)
        settle()
        assertEquals(listOf("サーバー"), sent)
        assertEquals("", shown)
    }

    @Test
    fun `a Cyrillic line typed letter by letter is sent whole`() = console { sent ->
        "скажи".forEach { commit(it.toString()) }
        press(Key.Enter)
        settle()
        assertEquals(listOf("скажи"), sent)
    }

    @Test
    fun `a recalled line takes letters at its end`() = console { _ ->
        commit("list")
        settle()
        press(Key.Enter)
        settle()
        press(Key.DirectionUp)
        settle()
        commit("x")
        settle()
        assertEquals("listx", shown)
    }

    // Sent and emptied before the next frame, so the console's value composes empty
    // both before and after.
    @Test
    fun `a letter typed at the next frame after a line and Enter starts the next command`() = console { sent ->
        commit("say hi")
        press(Key.Enter)
        settle(1)
        assertEquals("", shown, "the line is empty at the first frame")
        commit("l")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(listOf("say hi", "l"), sent)
    }

    @Test
    fun `a letter typed at the next frame after a line and Escape starts a new line`() = console { sent ->
        commit("stop")
        press(Key.Escape)
        settle(1)
        assertEquals("", shown)
        commit("x")
        settle()
        press(Key.Enter)
        settle()
        assertEquals(listOf("x"), sent)
    }

    /**
     * The line shows [expected] at the next frame, and Enter right then sends it: the console
     * holds what the field shows.
     */
    private fun TypingScene.sendsAsShown(expected: String, sent: List<String>, what: String) {
        settle(1)
        assertEquals(expected, shown, "the line after $what")
        press(Key.Enter)
        settle()
        assertEquals(expected, sent.last(), "what Enter sent after $what")
    }

    @Test
    fun `Ctrl and C copies from the command line, not from the log`() = console { sent ->
        clipboard.text = "from before"
        commit("seed")
        settle()
        press(Key.A, shortcut = true)
        pressToClipboard(Key.C)
        assertEquals("seed", clipboard.text)
        sendsAsShown("seed", sent, "a copy")
    }

    @Test
    fun `a line cut with Ctrl and X is gone and comes back with Ctrl and V`() = console { sent ->
        commit("say hi")
        settle()
        press(Key.A, shortcut = true)
        pressToClipboard(Key.X)
        assertEquals("say hi", clipboard.text)
        settle(1)
        assertEquals("", shown)
        press(Key.Enter)
        settle()
        assertEquals(emptyList(), sent, "the console holds the empty line too")
        paste()
        sendsAsShown("say hi", sent, "a paste")
    }

    @Test
    fun `a paste over a word selected with Ctrl, Shift and Left replaces the word`() = console { sent ->
        clipboard.text = "day"
        commit("time set night")
        settle()
        press(Key.DirectionLeft, word = true, shift = true)
        settle(1)
        assertEquals("night", selected)
        paste()
        sendsAsShown("time set day", sent, "a paste over the word")
    }

    @Test
    fun `Ctrl and Z takes a paste back and Ctrl and Y puts it again`() = console { sent ->
        clipboard.text = " hello"
        commit("say")
        settle()
        paste()
        settle(1)
        assertEquals("say hello", shown)
        press(Key.Z, shortcut = true)
        sendsAsShown("say", sent, "an undo of the paste")
    }

    @Test
    fun `a redo after an undo of a paste is what Enter sends`() = console { sent ->
        clipboard.text = " hello"
        commit("say")
        settle()
        paste()
        press(Key.Z, shortcut = true)
        settle(1)
        assertEquals("say", shown)
        press(Key.Z, shortcut = true, shift = true)
        sendsAsShown("say hello", sent, "a redo with Ctrl, Shift and Z")
    }

    @Test
    fun `Ctrl and Z takes a cut back`() = console { sent ->
        commit("list")
        settle()
        press(Key.A, shortcut = true)
        pressToClipboard(Key.X)
        settle(1)
        assertEquals("", shown)
        press(Key.Z, shortcut = true)
        sendsAsShown("list", sent, "an undo of the cut")
    }

    @Test
    fun `Home, End and Ctrl and Backspace edit the line where the caret is`() = console { sent ->
        commit("ime set day")
        settle()
        press(Key.MoveHome)
        commit("t")
        press(Key.MoveEnd)
        press(Key.Backspace, word = true)
        settle(1)
        assertEquals("time set ", shown)
        commit("night")
        sendsAsShown("time set night", sent, "Home, End and Ctrl and Backspace")
    }

    @Test
    fun `Ctrl and the arrows with Ctrl and Delete take a word out of the middle`() = console { sent ->
        commit("say hi there")
        settle()
        press(Key.MoveHome)
        press(Key.DirectionRight, word = true)
        press(Key.Delete, word = true)
        sendsAsShown("say there", sent, "Ctrl and Delete after the first word")
    }

    @Test
    fun `typing over Shift and End replaces the rest of the line`() = console { sent ->
        commit("weather rain")
        settle()
        press(Key.DirectionLeft, word = true)
        press(Key.MoveEnd, shift = true)
        settle(1)
        assertEquals("rain", selected)
        commit("clear")
        sendsAsShown("weather clear", sent, "typing over the selection")
    }

    @Test
    fun `Shift and the arrows select by a letter and Shift and Home to the start`() = console { sent ->
        commit("give diamond")
        settle()
        press(Key.DirectionLeft, shift = true)
        press(Key.DirectionLeft, shift = true)
        press(Key.DirectionRight, shift = true)
        settle(1)
        assertEquals("d", selected)
        commit("ds")
        settle(1)
        assertEquals("give diamonds", shown)
        press(Key.MoveHome, shift = true)
        settle(1)
        assertEquals("give diamonds", selected)
        commit("seed")
        sendsAsShown("seed", sent, "typing over Shift and Home")
    }

    // An undo skips the field's input transformation, and the console has to hear of it before
    // the Enter queued behind it reads the line.
    @Test
    fun `Enter right behind an undo of a paste sends what the undo left and empties the line`() = console { sent ->
        clipboard.text = " hello"
        commit("say")
        settle()
        paste()
        settle()
        press(Key.Z, shortcut = true)
        press(Key.Enter)
        settle(1)
        assertEquals(listOf("say"), sent)
        assertEquals("", shown, "the line is empty at the first frame")
    }
}
