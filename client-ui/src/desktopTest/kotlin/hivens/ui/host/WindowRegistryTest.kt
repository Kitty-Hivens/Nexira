package hivens.ui.host

import hivens.ui.chrome.WindowCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The host's registry against windows that are not on a screen. */
class WindowRegistryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun stop() = scope.cancel()

    /** A window that does what the registry tells it and remembers it. */
    private class FakeWindow(focused: Boolean = false) : WindowBackend {
        val live = MutableStateFlow(HostWindowState.Sample.copy(focused = focused, allowed = WindowCapabilities.Everything))
        override val state: StateFlow<HostWindowState> = live
        override val commands: Set<WindowCommand> = WindowCommand.entries.toSet()
        var closed = 0

        override fun run(command: WindowCommand) = Unit

        override fun raise() = Unit

        override fun close() {
            closed++
        }

        fun focus(on: Boolean) {
            live.value = live.value.copy(focused = on)
        }
    }

    private fun WindowRegistry.open(
        id: String,
        owner: WindowOwner = WindowOwner.Launcher,
        window: WindowBackend = FakeWindow(),
        composition: CompositionId = CompositionId.of(WindowId(id)),
    ): RegisteredWindow = assertIs<Registration.Opened>(register(WindowId(id), owner, composition, window, scope)).window

    private val WindowRegistry.ids get() = windows.value.map { it.id.value }

    @Test
    fun `a window is in the registry from its registration until it leaves`() {
        val registry = WindowRegistry()
        val main = registry.open("main")
        assertEquals(listOf(main), registry.windows.value)
        registry.unregister(WindowId.Main, main.composition)
        assertTrue(registry.windows.value.isEmpty())
        registry.unregister(WindowId.Main, main.composition)
    }

    @Test
    fun `an id a live window of the same composition holds is refused`() {
        val registry = WindowRegistry()
        val main = registry.open("main")
        registry.open("picker", WindowOwner.Composition(main.composition))
        val again = registry.register(WindowId("picker"), WindowOwner.Composition(main.composition), CompositionId("other"), FakeWindow(), scope)
        assertIs<Registration.Refused>(again, "a nested id is never taken over")
        assertEquals(listOf("main", "picker"), registry.ids)
    }

    @Test
    fun `the focus follows what each window says, and leaves with it`() {
        val registry = WindowRegistry()
        val mainWindow = FakeWindow(focused = true)
        val consoleWindow = FakeWindow()
        val main = registry.open("main", window = mainWindow)
        registry.open("console", window = consoleWindow)
        assertEquals(WindowId.Main, registry.focused.value, "the focus the window had when it registered counts")

        // The order the toolkit uses: the window that loses it hears first.
        mainWindow.focus(false)
        consoleWindow.focus(true)
        assertEquals(WindowId.Console, registry.focused.value)

        // A gain heard before the other window's loss leaves the newer one focused.
        mainWindow.focus(true)
        consoleWindow.focus(false)
        assertEquals(WindowId.Main, registry.focused.value)

        registry.unregister(WindowId.Main, main.composition)
        assertNull(registry.focused.value, "a window that left holds nothing")
        mainWindow.focus(true)
        assertNull(registry.focused.value, "and is not heard any more")
    }

    @Test
    fun `closing a window closes what its composition owns, all the way down`() {
        val registry = WindowRegistry()
        val main = registry.open("main")
        val pickerWindow = FakeWindow()
        val picker = registry.open("picker", WindowOwner.Composition(main.composition), pickerWindow)
        val nestedWindow = FakeWindow()
        registry.open("nested", WindowOwner.Composition(picker.composition), nestedWindow)
        val consoleWindow = FakeWindow()
        registry.open("console", window = consoleWindow)

        registry.unregister(WindowId.Main, main.composition)

        assertEquals(listOf("console"), registry.ids)
        assertEquals(1, pickerWindow.closed)
        assertEquals(1, nestedWindow.closed)
        assertEquals(0, consoleWindow.closed, "an independent window is the launcher's, not the main window's")
    }

    @Test
    fun `a nested window needs a composition that is on screen`() {
        val registry = WindowRegistry()
        val orphan = WindowOwner.Composition(CompositionId("gone"))
        assertIs<Registration.Refused>(registry.register(WindowId("picker"), orphan, CompositionId("picker"), FakeWindow(), scope))
    }

    @Test
    fun `a phone answers a second window with a refusal and a reason`() {
        val registry = WindowRegistry(WindowPolicy.Single("one window on a phone"))
        val activity = registry.open("main")
        val second = registry.register(WindowId("picker"), WindowOwner.Composition(activity.composition), CompositionId("picker"), FakeWindow(), scope)
        assertEquals(Registration.Refused("one window on a phone"), second)
        val console = registry.register(WindowId.Console, WindowOwner.Launcher, CompositionId("console"), FakeWindow(), scope)
        assertEquals(Registration.Refused("one window on a phone"), console)
        assertEquals(listOf(activity), registry.windows.value, "the Activity stays the one registered window")
    }

    @Test
    fun `a second Activity takes the one window over from the first`() {
        val registry = WindowRegistry(WindowPolicy.Single("one window on a phone"))
        val first = registry.open("main")
        val second = registry.open("main")
        assertEquals(listOf(second), registry.windows.value)
        registry.unregister(WindowId.Main, first.composition)
        assertEquals(listOf(second), registry.windows.value, "the first Activity leaving later leaves the second in place")
    }

    /**
     * A crash whose disposal stopped halfway left the old shell's main window in the
     * registry. The shell restarted after it registers its own and has to work.
     */
    @Test
    fun `a shell restarted after a crash takes over the window the dead one left behind`() {
        val registry = WindowRegistry()
        val deadWindow = FakeWindow(focused = true)
        val dead = registry.open("main", window = deadWindow)
        val pickerOfDead = FakeWindow()
        registry.open("picker", WindowOwner.Composition(dead.composition), pickerOfDead)
        var deadDialog = 0
        val _ = registry.holdDialog(dead.composition) { deadDialog++ }
        assertEquals(WindowId.Main, registry.focused.value)

        val restartedWindow = FakeWindow()
        val restarted = registry.open("main", window = restartedWindow)

        assertEquals(listOf(restarted), registry.windows.value)
        assertEquals(1, pickerOfDead.closed, "what the dead shell owned goes with it")
        assertEquals(1, deadDialog, "and so do its dialogs")
        assertEquals(0, deadWindow.closed, "the old window is not asked to close: what drew it is gone")
        assertNull(registry.focused.value, "the focus the dead window had is not carried over")

        var restartedDialog = 0
        val pending = registry.holdDialog(restarted.composition) { restartedDialog++ }
        assertEquals(0, restartedDialog, "the restarted shell's pickers keep working")
        restartedWindow.live.value = restartedWindow.live.value.copy(focused = true)
        assertEquals(WindowId.Main, registry.focused.value, "and its focus is followed")

        // The dead shell's disposal catching up late changes nothing.
        registry.unregister(WindowId.Main, dead.composition)
        assertEquals(listOf(restarted), registry.windows.value)
        pending.close()
    }

    @Test
    fun `a system dialog is taken back when the composition that opened it goes`() {
        val registry = WindowRegistry()
        val main = registry.open("main")
        val console = registry.open("console")
        var mainDialog = 0
        var consoleDialog = 0
        val _ = registry.holdDialog(main.composition) { mainDialog++ }
        val answered = registry.holdDialog(console.composition) { consoleDialog++ }
        answered.close()

        registry.unregister(WindowId.Main, main.composition)
        registry.unregister(WindowId.Console, console.composition)

        assertEquals(1, mainDialog, "the main window's dialog went with it")
        assertEquals(0, consoleDialog, "a dialog already answered has nothing left to take back")
    }

    @Test
    fun `a dialog is taken back at once only for a composition that has gone`() {
        val registry = WindowRegistry()
        val main = registry.open("main")
        registry.unregister(WindowId.Main, main.composition)
        var late = false
        val _ = registry.holdDialog(main.composition) { late = true }
        assertTrue(late, "a composition that left never comes back to answer it")

        val notYet = CompositionId("not-registered-yet")
        var early = 0
        val _ = registry.holdDialog(notYet) { early++ }
        assertEquals(0, early, "a composition not registered yet holds its dialog like any other")
        registry.open("main", composition = notYet)
        registry.unregister(WindowId.Main, notYet)
        assertEquals(1, early, "and it goes when that composition does")
    }
}
