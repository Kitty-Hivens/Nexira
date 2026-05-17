package hivens.launcher.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

/**
 * Installs a JVM shutdown hook that cancels the process-lifetime
 * [CoroutineScope] when the JVM is about to exit. Wired as
 * `single(createdAtStart = true)` in [appModule] so the hook is
 * registered during `startKoin { modules(...) }`.
 *
 * Why: before unification, two scopes lived in the launcher:
 *   - `Main.applicationScope` -- registered for cancellation on shutdown.
 *   - `LauncherController.appScope` -- not registered; an in-flight
 *     launch coroutine could outlive a SIGTERM and leak its child
 *     process + open sockets until the OS killed the JVM hard.
 * Both now resolve to the same Koin-managed scope, and this hook
 * guarantees it is canceled on shutdown regardless of how the
 * process is asked to exit (window close, tray quit, SIGTERM).
 *
 * Logs a single line on fire so an oncall reading `launcher.log`
 * can confirm the orderly cancellation actually ran -- without this
 * line the shutdown-induced "Job was canceled" entries downstream
 * could be misread as a runtime bug.
 */
class AppCoroutineScopeHook(
    private val scope: CoroutineScope,
) {
    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    LoggerFactory.getLogger("AppCoroutineScopeHook")
                        .info("JVM shutdown: cancelling app coroutine scope")
                    scope.cancel()
                },
                "aura-scope-shutdown",
            )
        )
    }
}
