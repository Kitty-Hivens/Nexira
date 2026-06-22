package hivens.launcher.diag

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
     * Record a shell-composition crash and decide how to recover. Thread-safe;
     * called from the entry point's main thread after `application {}` unwinds.
     */
    @Synchronized
    fun recordShellCrash(now: Long = System.currentTimeMillis()): ShellRecovery {
        if (_safeMode.value) return ShellRecovery.FATAL

        crashTimes.addLast(now)
        // Keep history for the long (absolute) window; drop anything older.
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > LONG_WINDOW_MS) {
            crashTimes.removeFirst()
        }

        val recentFast = crashTimes.count { now - it <= FAST_WINDOW_MS }
        val crashLoop = recentFast > MAX_FAST_CRASHES || crashTimes.size > MAX_TOTAL_CRASHES
        return if (crashLoop) {
            _safeMode.value = true
            ShellRecovery.SAFE_MODE
        } else {
            ShellRecovery.RETRY
        }
    }

    private const val FAST_WINDOW_MS = 20_000L
    private const val LONG_WINDOW_MS = 300_000L
    private const val MAX_FAST_CRASHES = 2
    private const val MAX_TOTAL_CRASHES = 6
}
