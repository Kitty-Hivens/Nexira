package hivens.ui.layout

import kotlinx.coroutines.CoroutineScope
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

    // Every lambda below is instantiated here rather than at shutdown, because a
    // lambda's class loads when its first instance is created. A shutdown hook
    // that first touches its own generated classes while the process is exiting
    // cannot run at all if what it was compiled from is no longer readable -- a
    // self-update that replaced the running image, or a rebuild over a live
    // process. Losing the flush is exactly the case the flush exists for.
    private val flush: suspend CoroutineScope.() -> Unit = { repo.flush() }
    private val body: () -> Unit = { runBlocking(block = flush) }
    private val onFailure: (Throwable) -> Unit = { err ->
        LoggerFactory.getLogger("LayoutGraphFlushHook").warn("Layout flush on shutdown failed", err)
    }
    private val task = Runnable { runCatching(body).onFailure(onFailure) }

    init {
        Runtime.getRuntime().addShutdownHook(Thread(task, "nexira-layout-flush"))
    }
}
