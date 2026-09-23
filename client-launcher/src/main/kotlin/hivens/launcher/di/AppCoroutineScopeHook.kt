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
 * Why: before unification, two scopes lived in the launcher, and only one of
 * them was registered for cancellation on shutdown. Both now resolve to the
 * same Koin-managed scope, and this hook cancels it however the process is
 * asked to exit (window close, tray quit, SIGTERM), so launcher work in flight
 * (a download, a preparing launch) winds down with it.
 *
 * What it does NOT do is end a running game. A spawned process outlives its
 * parent by construction, and cancelling the scope does not touch it -- on
 * purpose: quitting from the launcher asks first (QuitWithGameHost in the UI)
 * and honours "leave it running", which a hook that killed the game would
 * overrule. A launcher killed from outside, by a SIGTERM that never reaches that
 * question, leaves the game running and its session unrecorded.
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
