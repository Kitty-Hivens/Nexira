package hivens.ui.widgets.modules

import hivens.widget.api.WidgetRegistry
import hivens.widget.loader.LoadedWidgetModule
import hivens.widget.loader.WidgetModuleLoader
import java.nio.file.Files
import java.nio.file.Path

/** Widget modules as jars in a folder, each opened in a class loader of its own. */
class DesktopWidgetModuleHost(
    private val directory: Path,
    private val shadowDir: Path,
) : WidgetModuleHost {

    override fun scan(disabled: Set<String>): ModuleScan {
        val scan = WidgetModuleLoader(directory, shadowDir = shadowDir, disabled = disabled).scan()
        return ModuleScan(
            loaded   = scan.loaded.map(::JarModule),
            disabled = scan.disabled.map { OffModule(it.id, it.name, it.file) },
            rejected = scan.rejected.map { RefusedModule(it.file, it.reason) },
        )
    }

    override fun moduleIdIn(failure: Throwable): String? = WidgetModuleLoader.moduleIdIn(failure)

    /** Copies no live loader reads from any more. Best effort: a host that will not delete an open file keeps it for later. */
    override fun sweepUnused(inUse: Set<String>) {
        val _ = runCatching {
            Files.list(shadowDir).use { files ->
                files.filter { it.fileName.toString() !in inUse }.forEach { val _ = runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}

private class JarModule(private val module: LoadedWidgetModule) : LoadedModule {
    override val id: String get() = module.id
    override val name: String get() = module.name
    override val file: Path get() = module.file
    override val registry: WidgetRegistry get() = module.registry

    override val filesInUse: Set<String>
        get() = module.loader.urLs.map { Path.of(it.toURI()).fileName.toString() }.toSet()

    override fun isSameLoad(other: LoadedModule): Boolean = other is JarModule && other.module.loader === module.loader

    override fun close() = module.loader.close()
}
