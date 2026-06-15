package hivens.ui.widgets.state

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
    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    runCatching {
                        runBlocking { store.flush() }
                    }.onFailure { err ->
                        LoggerFactory.getLogger("WidgetStateFlushHook")
                            .warn("Widget state flush on shutdown failed", err)
                    }
                },
                "nexira-widget-state-flush",
            )
        )
    }
}
