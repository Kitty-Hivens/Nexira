package hivens.tray

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Pre-init contract that the shell relies on. AppShell's window-restore fallback
 * keys off [TrayController.isSupported] being false until libtray actually brings
 * an icon up, so an unsupported tray lets the window come back instead of
 * stranding a process with no reachable UI. None of this touches native code --
 * the libtray downcalls only fire inside `init`.
 */
class LibTrayControllerTest {

    @Test
    fun `reports unsupported before init`() {
        val tray = LibTrayController()
        assertFalse(tray.isSupported)
        assertFalse(tray.canBeReady)
    }

    @Test
    fun `status and label updates before init are safe no-ops`() {
        val tray = LibTrayController()
        tray.onShowWindow = {}
        tray.onShowConsole = {}
        tray.onExit = {}
        // Guarded on a live tray internally, so neither touches libtray nor throws.
        tray.setGameStatus(running = true, serverName = "Test")
        tray.updateStrings(TrayStrings("idle", "running", "Show", "Console", "Exit"))
        assertFalse(tray.isSupported)
    }
}
