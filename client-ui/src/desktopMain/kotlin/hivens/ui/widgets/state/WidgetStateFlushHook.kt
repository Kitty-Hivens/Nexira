package hivens.ui.widgets.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * JVM shutdown hook that flushes [WidgetStateStore]'s pending debounced write
 * before exit -- the per-instance-state counterpart of LayoutGraphFlushHook.
 * Without it, state typed inside the debounce window (e.g. a note) is lost on a
 * tray-quit or SIGTERM. flush() is mutex-locked and synchronous, so racing the
 * other shutdown hooks is safe.
 */
class WidgetStateFlushHook(
    private val store: WidgetStateStore,
) {

    // Every lambda below is instantiated here rather than at shutdown, because a
    // lambda's class loads when its first instance is created. A shutdown hook
    // that first touches its own generated classes while the process is exiting
    // cannot run at all if what it was compiled from is no longer readable -- a
    // self-update that replaced the running image, or a rebuild over a live
    // process. Losing the flush is exactly the case the flush exists for.
    private val flush: suspend CoroutineScope.() -> Unit = { store.flush() }
    private val body: () -> Unit = { runBlocking(block = flush) }
    private val onFailure: (Throwable) -> Unit = { err ->
        LoggerFactory.getLogger("WidgetStateFlushHook").warn("Widget state flush on shutdown failed", err)
    }
    private val task = Runnable { runCatching(body).onFailure(onFailure) }

    init {
        Runtime.getRuntime().addShutdownHook(Thread(task, "nexira-widget-state-flush"))
    }
}
