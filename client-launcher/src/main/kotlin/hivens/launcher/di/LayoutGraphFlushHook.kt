package hivens.launcher.di

import hivens.launcher.LayoutGraphRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * JVM shutdown hook that flushes the [LayoutGraphRepository]'s pending
 * debounced write before the process exits. Counterpart to
 * [AppCoroutineScopeHook]: the latter cancels the process scope (which
 * would otherwise terminate the in-flight debounce coroutine mid-delay
 * and lose the user's last edit); this hook persists the in-memory
 * state synchronously so a SIGTERM or tray-quit mid-edit cannot drop
 * work.
 *
 * Hooks run in parallel per the JVM shutdown contract. Repository
 * [flush][LayoutGraphRepository.flush] is mutex-locked and synchronous
 * with respect to the file I/O, so racing with the scope cancellation
 * hook is safe -- whichever wins the lock first completes; the other
 * proceeds against either a cancelled or already-flushed state.
 */
class LayoutGraphFlushHook(
    private val repo: LayoutGraphRepository,
) {
    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    runCatching {
                        runBlocking { repo.flush() }
                    }.onFailure { err ->
                        LoggerFactory.getLogger("LayoutGraphFlushHook")
                            .warn("Layout flush on shutdown failed", err)
                    }
                },
                "nexira-layout-flush",
            )
        )
    }
}
