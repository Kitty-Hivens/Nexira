package hivens.ui.widgets.modules

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every module a test's hosts loaded, so the test can close them before its folder is deleted.
 *
 * A loaded module's class loader holds its jar copy open, and Windows will not delete an open
 * file. The launcher keeps a live module's loader to its exit and closes one that left only after
 * a grace period on its scope, which a test's teardown cancels, so here nothing else closes them.
 */
internal class OpenedModules {
    private val loaded = CopyOnWriteArrayList<LoadedModule>()

    /** [host], keeping every module it loads. */
    fun keptBy(host: WidgetModuleHost): WidgetModuleHost = object : WidgetModuleHost by host {
        override fun scan(disabled: Set<String>): ModuleScan = host.scan(disabled).also { loaded += it.loaded }
    }

    /** Closes every module loaded so far, one that will not close leaving the others to close. */
    fun closeAll() {
        loaded.forEach { val _ = runCatching { it.close() } }
    }
}
