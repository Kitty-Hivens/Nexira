package hivens.ui.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the entry point should react to a shell-composition crash. */
enum class ShellRecovery {
    /** Re-enter `application {}` with a fresh composition. */
    RETRY,

    /** Crash loop: the next launch is the standalone safe-mode window. */
    SAFE_MODE,

    /** Safe mode itself crashed -- give up on Compose, show a terminal dialog. */
    FATAL,
}

/**
 * Process-global UI self-healing state. The entry point runs `application {}`
 * inside a restart loop (see hivens.ui.Main): when the shell composition throws,
 * the exception unwinds `application {}` and kills that recomposer, so recovery
 * cannot happen *inside* the composition -- it has to re-enter `application {}`
 * with a brand-new composition. This is the "reload the shell" model (a crashed
 * panel comes back), not a frozen window.
 *
 * Persisted state (layout graph, settings, theme, session, audio) lives in Koin
 * singletons and files that outlive the composition, so a restart loses only
 * transient composition state (current screen, scroll), never user data.
 *
 * [recordShellCrash] bounds restarts with TWO windows so both failure shapes
 * latch [safeMode] (after which the loop launches a standalone quit-only window
 * that never builds the shell scaffolding):
 *   - a FAST window catches a tight crash loop (the rebuild keeps hitting the
 *     same fault instantly);
 *   - a LONG window catches a slow-recurring crash (one that fires slower than
 *     the fast window prunes, which would otherwise restart forever).
 * A crash while already in safe mode is [ShellRecovery.FATAL] -- the backstop
 * against an infinite restart spin.
 */
object UiRecoverySignal {
    private val _safeMode = MutableStateFlow(false)

    /** Latches once the crash-loop guard trips. Read by the entry-point loop. */
    val safeMode: StateFlow<Boolean> = _safeMode.asStateFlow()

    /** Why the entry point should show the recovery surface instead of the shell. */
    enum class RecoveryReason { None, CrashLoop, UserRequest }

    private val _recoveryReason = MutableStateFlow(RecoveryReason.None)

    /** Read fresh by the entry-point loop each iteration to pick the surface. */
    val recoveryReason: StateFlow<RecoveryReason> = _recoveryReason.asStateFlow()

    /**
     * A user-initiated recovery request (env / --recovery / one-shot marker /
     * hold-key), resolved pre-shell for a launcher that starts wrong but does not
     * crash. A latched crash loop is the harder failure and wins, so this never
     * downgrades [RecoveryReason.CrashLoop].
     */
    fun requestRecovery() {
        if (_recoveryReason.value == RecoveryReason.None) _recoveryReason.value = RecoveryReason.UserRequest
    }

    /**
     * Crash side-channel for the render path. A crash thrown while rendering a
     * frame runs on the AWT event thread, which catches it and keeps the window
     * alive -- so it never unwinds `application {}` and the restart loop never
     * sees it. The window-exception handler (see hivens.ui.Main) stashes it here
     * and exits the application; the loop reads it after `application {}` returns
     * and drives the same recovery a thrown crash would. First writer wins: a
     * broken frame can fire the handler repeatedly before teardown, and the
     * first throwable is the cause.
     */
    @Volatile private var pendingCrash: Throwable? = null

    fun recordPendingCrash(crash: Throwable) {
        if (pendingCrash == null) pendingCrash = crash
    }

    /** Read and clear the stashed render-path crash, if any. */
    fun consumePendingCrash(): Throwable? = pendingCrash.also { pendingCrash = null }

    /**
     * One-shot "the shell just reloaded after a crash" signal. Set before the
     * loop re-enters `application {}` on a retry; the fresh shell reads it once
     * on first composition to surface a notification, so a reload that resets the
     * current screen is never silent.
     */
    @Volatile private var recovered: Boolean = false

    fun markRecovered() { recovered = true }

    /** Read and clear the recovered flag; true only on the composition that follows a crash restart. */
    fun consumeRecovered(): Boolean = recovered.also { recovered = false }

    private val crashTimes = ArrayDeque<Long>()

    /**
     * The shell composed. Clears the crash history, because the guard below
     * counts crashes in a time window and a window cannot tell a crash LOOP from
     * a run of separate faults.
     *
     * The loop it is meant to catch is one where the rebuild hits the same fault
     * before the shell is ever on screen, so no composition happens between the
     * restarts. Three faults that each let the shell come up, be used, and then
     * fail are not that: the user got a working launcher back every time, and
     * dropping them into the recovery surface on the third takes away a launcher
     * that demonstrably works.
     *
     * So a successful composition is the thing that resets the count, and a
     * composition that never happens is what lets it accumulate.
     */
    @Synchronized
    fun noteShellComposed() {
        crashTimes.clear()
    }

    /**
     * Record a shell-composition crash and decide how to recover. Thread-safe;
     * called from the entry point's main thread after `application {}` unwinds.
     */
    @Synchronized
    fun recordShellCrash(now: Long = System.currentTimeMillis(), crash: Throwable? = null): ShellRecovery {
        if (_safeMode.value) return ShellRecovery.FATAL

        crashTimes.addLast(now)
        // Keep history for the long (absolute) window; drop anything older.
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > LONG_WINDOW_MS) {
            crashTimes.removeFirst()
        }

        // A LinkageError (NoClassDefFoundError etc.) is a DETERMINISTIC fault:
        // the class is gone on every restart, so retrying only reproduces it --
        // reviving what already vanished. Latch safe mode one crash sooner for
        // these, still keeping a single retry: a class re-patched during a
        // recompile-while-running can reappear by the time the loop re-enters,
        // but if the retry hits the same linkage fault the class really is gone,
        // so stop burning restarts on it.
        val fastLimit = if (crash != null && isStructural(crash)) 1 else MAX_FAST_CRASHES

        val recentFast = crashTimes.count { now - it <= FAST_WINDOW_MS }
        val crashLoop = recentFast > fastLimit || crashTimes.size > MAX_TOTAL_CRASHES
        return if (crashLoop) {
            _safeMode.value = true
            _recoveryReason.value = RecoveryReason.CrashLoop
            ShellRecovery.SAFE_MODE
        } else {
            ShellRecovery.RETRY
        }
    }

    /** A class-linkage failure (a missing or re-patched class) reproduces on every
     *  restart, so recovery must not spend the full retry budget on it. Walks the
     *  cause chain, bounded against a cyclic `cause`. Internal for unit tests --
     *  the count-based decision cannot be tested without latching safeMode. */
    internal fun isStructural(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 16) {
            if (cur is LinkageError || cur is ClassNotFoundException) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private const val FAST_WINDOW_MS = 20_000L
    private const val LONG_WINDOW_MS = 300_000L
    private const val MAX_FAST_CRASHES = 2
    private const val MAX_TOTAL_CRASHES = 6
}
