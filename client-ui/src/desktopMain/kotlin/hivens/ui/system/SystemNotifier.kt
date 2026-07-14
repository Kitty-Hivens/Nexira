package hivens.ui.system

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationAction
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierConfig
import dev.hivens.libnotify.Urgency
import org.slf4j.LoggerFactory

/**
 * OS-level desktop notifications via libnotify (`dev.hivens:libnotify`) --
 * freedesktop Notifications over D-Bus on Linux, WinRT toast on Windows,
 * NSUserNotification on macOS. Pure Project Panama, same binding family as
 * [hivens.tray.TrayController].
 *
 * Distinct from the in-app [hivens.ui.notifications.NotificationCenter]: those
 * render inside the launcher window and vanish with it. These post to the
 * desktop's own notification surface and stay visible while the window is
 * hidden -- which is exactly what the "minimized to tray" hint needs.
 *
 * Currently drives a single message (the one-time tray hint), but kept as a
 * general manager mirroring [hivens.tray.TrayController] so future OS
 * notifications (download done, update ready) have a home.
 *
 * Lifecycle: [init] once after Koin + strings are ready; [shutdown] on exit.
 * No-throw: a missing notification daemon degrades to "no banner", never a
 * crash -- [isSupported] stays false and [notifyTrayHint] returns false.
 */
object SystemNotifier {

    private val logger = LoggerFactory.getLogger("SystemNotifier")

    enum class State { NOT_STARTED, READY, FAILED }

    @Volatile
    private var state: State = State.NOT_STARTED

    private var notifier: Notifier? = null
    private var unsubscribe: (() -> Unit)? = null

    /**
     * Restore-the-window callback, fired when the user clicks the hint banner
     * or its action button. Runs on libnotify's dispatch thread -- the
     * implementation hops to the UI thread itself (mirrors
     * [hivens.tray.TrayController.onShowWindow]).
     */
    var onShowWindow: (() -> Unit)? = null

    /** True only when a desktop notification backend is live. */
    val isSupported: Boolean get() = state == State.READY

    private const val ACTION_SHOW = "show"
    private const val TAG_TRAY_HINT = "tray-hint"

    /**
     * @param appName Source name shown by the desktop (freedesktop `app_name`).
     * @param appId   Windows Application User Model ID; ignored on Linux/macOS.
     *                Toasts need it registered, which a plain launcher install
     *                may lack -- the hint then silently no-ops on Windows, which
     *                is acceptable for an onboarding hint.
     * @param iconBytes App icon (PNG) used as the notification image.
     */
    fun init(appName: String, appId: String, iconBytes: ByteArray) {
        if (state != State.NOT_STARTED) return
        try {
            val n = Notifier.create(
                NotifierConfig(appName = appName, appId = appId, defaultIconBytes = iconBytes),
            ) ?: run {
                logger.info("libnotify Notifier.create returned null -- no notification daemon on this session")
                state = State.FAILED
                return
            }
            notifier = n
            unsubscribe = n.onEvent { event ->
                when (event) {
                    // A click on the banner body, or on the "Show window" action,
                    // both mean "bring the launcher back".
                    is NotificationEvent.Activated -> onShowWindow?.invoke()
                    is NotificationEvent.ActionInvoked ->
                        if (event.actionId == ACTION_SHOW) onShowWindow?.invoke()
                    is NotificationEvent.Dismissed -> Unit
                }
            }
            state = State.READY
            logger.info("SystemNotifier initialized via libnotify (caps={})", n.capabilities)
        } catch (t: Throwable) {
            state = State.FAILED
            logger.error("Failed to initialize SystemNotifier", t)
        }
    }

    /**
     * Post the one-time "still running in the tray" hint. Returns true if the
     * backend accepted it. Offers a "Show window" action where the platform
     * supports actions; a backend without actions just shows the text. The
     * fixed [TAG_TRAY_HINT] tag means a stale prior hint is replaced rather
     * than stacked, should this ever fire twice.
     */
    fun notifyTrayHint(title: String, body: String, showLabel: String): Boolean {
        val n = notifier ?: return false
        val handle = n.notify(
            Notification(
                title = title,
                body = body,
                urgency = Urgency.NORMAL,
                actions = listOf(NotificationAction(id = ACTION_SHOW, label = showLabel)),
                tag = TAG_TRAY_HINT,
            ),
        )
        return handle != null
    }

    fun shutdown() {
        runCatching { unsubscribe?.invoke() }
        runCatching { notifier?.close() }
        notifier = null
        unsubscribe = null
        onShowWindow = null
        state = State.NOT_STARTED
    }
}
