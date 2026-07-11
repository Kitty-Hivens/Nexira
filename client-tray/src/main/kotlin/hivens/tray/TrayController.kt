package hivens.tray

import java.io.InputStream

/**
 * The launcher's system-tray seam. One implementation ([LibTrayController],
 * backed by `dev.hivens:libtray`) ships today; the interface keeps the UI free
 * of the native tray types and lets tests substitute a no-op tray.
 *
 * The tray is deliberately a thin window-and-status surface: it raises the
 * window, opens the console and quits, and it reflects the running-game state in
 * its tooltip. It does NOT launch games -- every menu action is an instant,
 * in-process window operation, so the tray never blocks on auth, network or the
 * launch pipeline. This module's dependencies enforce that: `:client-tray`
 * cannot see the launch engine or auth.
 *
 * Lifecycle: [init] once after the localized [TrayStrings] are resolved;
 * [setGameStatus] / [updateStrings] any time; [shutdown] on teardown
 * (idempotent). Callbacks fire on libtray's own event thread -- the consumer is
 * responsible for hopping to the UI thread.
 */
interface TrayController {

    /** True only when libtray has a live tray icon up. */
    val isSupported: Boolean

    /**
     * True when the tray is up OR still initializing. Close-request handlers use
     * this (not [isSupported]) so they prefer "hide to tray" over "quit" while
     * libtray is still settling its D-Bus / SNI registration -- killing the
     * launcher because the tray library is taking its time would be wrong when
     * the user's intent was clearly "minimize".
     */
    val canBeReady: Boolean

    var onShowWindow: (() -> Unit)?
    var onShowConsole: (() -> Unit)?
    var onExit: (() -> Unit)?

    /**
     * @param iconStream Tray icon bytes (PNG). Read once into memory for libtray.
     * @param strings    Localized menu labels.
     * @param appName    The tray-host-visible title (hover tooltip prefix).
     */
    fun init(iconStream: InputStream, strings: TrayStrings, appName: String)

    /** Republish the menu + tooltip after a runtime locale switch. */
    fun updateStrings(strings: TrayStrings)

    /** Reflect the running-game state in the tooltip. */
    fun setGameStatus(running: Boolean, serverName: String? = null)

    /** Tear the tray icon down. Idempotent. */
    fun shutdown()
}

/** Localized tray labels. */
data class TrayStrings(
    val statusIdle: String,
    val statusRunning: String,
    val show: String,
    val console: String,
    val exit: String,
)
