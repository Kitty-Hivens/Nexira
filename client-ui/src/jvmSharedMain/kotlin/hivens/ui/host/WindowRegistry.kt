package hivens.ui.host

import hivens.ui.chrome.WindowCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

// The host's own part of the window model, with no Compose in it. It lives here
// and not in client-window because the phone runs the same registry with its one
// window, and client-window is the desktop backend: AWT frames, Win32 and X11,
// none of which an Android build can load. What client-window gives a desktop
// window is its WindowHost, which a DesktopWindowBackend turns into a WindowBackend.

private val log = LoggerFactory.getLogger("hivens.ui.host.WindowRegistry")

/** A window's id in the host's registry, unique among the windows open at the same time. */
@JvmInline
value class WindowId(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The launcher's own window, the one a notification action lands in. */
        val Main = WindowId("main")
        val Console = WindowId("console")
    }
}

/**
 * The composition a window shows, and the one that owns a window it opened. A
 * window shows one composition for its whole life until compositions can be
 * swapped in place, so each window makes its own.
 */
@JvmInline
value class CompositionId(val value: String) {
    override fun toString(): String = value

    companion object {
        private val next = AtomicLong()

        /** A composition shown in [window], never handed out before. */
        fun of(window: WindowId): CompositionId = CompositionId("${window.value}#${next.incrementAndGet()}")
    }
}

/** Who a window belongs to, which decides how long it may live. */
sealed interface WindowOwner {
    /**
     * An independent window, such as the console: a window of the application
     * itself, beside the main one, which lives until it is closed or the launcher
     * quits. On the desktop the application lives for as long as one of these is
     * open, which is Compose's own rule for the windows of an `application`.
     */
    data object Launcher : WindowOwner

    /** A nested window, which goes when [composition] goes so it cannot be left behind. */
    data class Composition(val composition: CompositionId) : WindowOwner
}

/** What a widget may ask of the window it is drawn in. */
enum class WindowCommand { Maximize, Minimize, Restore, Close }

/**
 * A window as the host reads it and as widgets see it through the window level:
 * its size in dp, whether it is maximised, minimised and focused, and what the
 * platform lets the user do with it. Read-only: it changes when the system says
 * the window changed, never because a widget asked.
 */
data class HostWindowState(
    val width: Int,
    val height: Int,
    val maximized: Boolean,
    val minimized: Boolean,
    val focused: Boolean,
    val allowed: WindowCapabilities,
) {
    companion object {
        /** What the palette and the editor draw a widget reading the window on. */
        val Sample = HostWindowState(
            width = 1280,
            height = 800,
            maximized = false,
            minimized = false,
            focused = true,
            allowed = WindowCapabilities.Everything,
        )

        /** A window the platform has not described yet. */
        val Unknown = HostWindowState(
            width = 0,
            height = 0,
            maximized = false,
            minimized = false,
            focused = false,
            allowed = WindowCapabilities.Nothing,
        )
    }
}

/**
 * One window as the platform has it: an AWT frame under its WindowHost on the
 * desktop, the Activity on a phone. The registry reads it and passes the
 * commands on, and the platform answers through [state].
 */
interface WindowBackend {
    val state: StateFlow<HostWindowState>

    /** The commands this window takes. The window level offers widgets these and no others. */
    val commands: Set<WindowCommand>

    /** Runs [command], which is one of [commands]. One the platform does not allow right now does nothing. */
    fun run(command: WindowCommand)

    /** Brings the window to the front and gives it the focus. */
    fun raise()

    /** Takes the window down because what owned it went. */
    fun close()
}

/** How many windows the platform can show. */
sealed interface WindowPolicy {
    data object Unlimited : WindowPolicy

    /** One window and no more, with what to tell whoever asks for a second. */
    data class Single(val reason: String) : WindowPolicy
}

/** A window in the registry. */
class RegisteredWindow internal constructor(
    val id: WindowId,
    val owner: WindowOwner,
    /** The composition the window shows. */
    val composition: CompositionId,
    val backend: WindowBackend,
) {
    override fun toString(): String = "window $id ($owner, showing $composition)"
}

/** What the registry made of a window asking to be registered. */
sealed interface Registration {
    data class Opened(val window: RegisteredWindow) : Registration

    data class Refused(val reason: String) : Registration
}

/**
 * The windows the launcher has open, kept by the host, which draws nothing itself.
 *
 * A window registers when it opens and leaves when it closes. Each has an owner:
 * the launcher for an independent window, or the composition that opened it for a
 * nested one. A composition that goes takes the windows it owns with it, and the
 * system dialogs it opened, so nothing it started can outlive it. The registry
 * also knows which window holds the focus.
 *
 * One registry serves the whole process, longer than any composition in it. A
 * composition that died without leaving (a crash that stopped its disposal halfway,
 * a second Activity on a phone) can leave its window behind, so an independent
 * window registering under an id that is still held by another composition takes
 * the id over: the old entry is settled as if it had left, and the old window
 * itself is not told to close, since whatever drew it is gone already.
 *
 * [policy] is what the platform can show. A phone has one window, and asking it
 * for another gets a refusal with a reason, never a crash.
 *
 * Safe to call from any thread. The backends' own commands are theirs to put on
 * the right thread.
 */
class WindowRegistry(private val policy: WindowPolicy = WindowPolicy.Unlimited) {

    private class Entry(val window: RegisteredWindow) {
        var focusWatch: Job? = null
    }

    private class Held(val owner: CompositionId, val revoke: () -> Unit)

    private class Gone(val windows: List<RegisteredWindow>, val dialogs: List<Held>)

    private val lock = Any()

    private val entries = LinkedHashMap<WindowId, Entry>()

    private val dialogs = ArrayList<Held>()

    // A composition never comes back once it has left, so a dialog asked for under
    // one in here is taken back at once. One id per window ever opened in the run.
    private val ended = HashSet<CompositionId>()

    private val _windows = MutableStateFlow<List<RegisteredWindow>>(emptyList())

    /** Every registered window, in the order they opened. */
    val windows: StateFlow<List<RegisteredWindow>> = _windows.asStateFlow()

    private val _focused = MutableStateFlow<WindowId?>(null)

    /** The registered window that holds the focus, or null when none of them does. */
    val focused: StateFlow<WindowId?> = _focused.asStateFlow()

    /**
     * Registers the window [id], which shows [composition] and is owned by [owner],
     * as [backend] has it. Focus is followed from the backend's state in [scope],
     * for as long as the window is registered.
     */
    fun register(
        id: WindowId,
        owner: WindowOwner,
        composition: CompositionId,
        backend: WindowBackend,
        scope: CoroutineScope,
    ): Registration {
        val window = RegisteredWindow(id, owner, composition, backend)
        var replaced: Gone? = null
        val outcome = synchronized(lock) {
            val stale = entries[id]
            if (stale != null && owner == WindowOwner.Launcher && stale.window.owner == WindowOwner.Launcher &&
                stale.window.composition != composition
            ) {
                log.warn("Window '{}' was still held by {}, which is gone: the new one takes its place", id, stale.window.composition)
                replaced = removeLocked(stale)
            }
            val reason = refusalLocked(id, owner)
            if (reason != null) {
                Registration.Refused(reason)
            } else {
                val entry = Entry(window)
                entries[id] = entry
                publishLocked()
                // After the entry is in: a scope that runs the collector at once reports
                // the focus the window already has, and it must find the window there.
                entry.focusWatch = scope.launch {
                    backend.state.map { it.focused }.distinctUntilChanged().collect { reportFocus(id, it) }
                }
                Registration.Opened(window)
            }
        }
        replaced?.let { settle(it, root = id) }
        when (outcome) {
            is Registration.Opened -> log.info("Window registered: {}", window)
            is Registration.Refused -> log.warn("Window '{}' was refused: {}", id, outcome.reason)
        }
        return outcome
    }

    private fun refusalLocked(id: WindowId, owner: WindowOwner): String? {
        if (id in entries) return "a window '$id' is open already"
        val single = policy as? WindowPolicy.Single
        if (single != null && entries.isNotEmpty()) return single.reason
        if (owner is WindowOwner.Composition && entries.values.none { it.window.composition == owner.composition }) {
            return "the composition '${owner.composition}' that would own it is not shown in any window"
        }
        return null
    }

    /**
     * Takes [id] out of the registry because the window showing [composition]
     * closed, and with it the windows that composition owns, which are told to
     * close, and the system dialogs it opened, which are revoked. Nothing happens
     * when [id] is not here or is held by another composition by now.
     */
    fun unregister(id: WindowId, composition: CompositionId) {
        val gone = synchronized(lock) {
            val entry = entries[id]?.takeIf { it.window.composition == composition }
            if (entry == null) {
                ended += composition
                return
            }
            removeLocked(entry)
        }
        settle(gone, root = id)
        log.info("Window unregistered: {}", id)
    }

    /**
     * Holds a system dialog [owner] opened, until the returned handle is closed. If
     * [owner] goes first, [revoke] runs, which is the host taking the dialog back.
     * A composition that already went has its dialog taken back at once. One that is
     * not registered yet holds it all the same, so it goes with the composition later.
     */
    fun holdDialog(owner: CompositionId, revoke: () -> Unit): AutoCloseable {
        val held = Held(owner, revoke)
        val gone = synchronized(lock) {
            val over = owner in ended
            if (!over) dialogs += held
            over
        }
        if (gone) revokeLogged(held)
        return AutoCloseable { synchronized(lock) { dialogs.remove(held) } }
    }

    /** What leaves with [entry]: itself, then everything its composition owns, and their dialogs. */
    private fun removeLocked(entry: Entry): Gone {
        entries.remove(entry.window.id)
        entry.focusWatch?.cancel()
        if (_focused.value == entry.window.id) _focused.value = null
        val composition = entry.window.composition
        ended += composition
        val ownDialogs = dialogs.filter { it.owner == composition }
        dialogs.removeAll(ownDialogs)
        val windows = arrayListOf(entry.window)
        val heldDialogs = ArrayList(ownDialogs)
        entries.values.filter { (it.window.owner as? WindowOwner.Composition)?.composition == composition }.forEach { child ->
            val gone = removeLocked(child)
            windows += gone.windows
            heldDialogs += gone.dialogs
        }
        publishLocked()
        return Gone(windows, heldDialogs)
    }

    /** Outside the lock, because a backend closing may call back in. [root] is the window that left on its own. */
    private fun settle(gone: Gone, root: WindowId) {
        gone.dialogs.forEach(::revokeLogged)
        gone.windows.filter { it.id != root }.forEach { window ->
            log.info("Window '{}' closes with what owned it", window.id)
            runCatching { window.backend.close() }.onFailure { log.warn("Window '{}' did not close", window.id, it) }
        }
    }

    private fun revokeLogged(held: Held) {
        runCatching { held.revoke() }.onFailure { log.warn("A system dialog of '{}' could not be revoked", held.owner, it) }
    }

    private fun reportFocus(id: WindowId, focused: Boolean) {
        synchronized(lock) {
            if (id !in entries) return
            if (focused) {
                _focused.value = id
            } else if (_focused.value == id) {
                _focused.value = null
            }
        }
    }

    private fun publishLocked() {
        _windows.value = entries.values.map { it.window }
    }
}
