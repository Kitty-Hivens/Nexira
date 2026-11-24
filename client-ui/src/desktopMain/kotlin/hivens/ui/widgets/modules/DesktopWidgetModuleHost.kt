package hivens.ui.widgets.modules

import hivens.widget.api.ModuleStrings
import hivens.widget.api.ModuleContribution
import hivens.widget.api.WidgetRegistry
import hivens.widget.loader.LoadedWidgetModule
import hivens.widget.loader.WidgetModuleLoader
import hivens.widget.model.ApiVersion
import java.nio.file.Files
import java.nio.file.Path

/**
 * Widget modules as jars in a folder, each opened in a class loader of its own that
 * shows the jar the widget kernel and the libraries it shares with the launcher, and
 * nothing of the launcher itself (see [WidgetModuleLoader]). [offered] is what a jar
 * can require of this build, the module APIs it carries and its modules by id, each in
 * a version that has to serve the one the jar names, and [apiPackages] where each API
 * lives, which a jar that names it is shown. [contributionKinds] is what is read out of each jar besides its widgets.
 */
class DesktopWidgetModuleHost(
    private val directory: Path,
    private val shadowDir: Path,
    private val offered: Map<String, ApiVersion> = emptyMap(),
    private val apiPackages: Map<String, Set<String>> = emptyMap(),
    private val contributionKinds: List<Class<out ModuleContribution>> = ModuleContribution.kinds,
) : WidgetModuleHost {

    override fun scan(disabled: Set<String>): ModuleScan {
        val scan = WidgetModuleLoader(
            directory         = directory,
            shadowDir         = shadowDir,
            disabled          = disabled,
            contributionKinds = contributionKinds,
            offered           = offered,
            apiPackages       = apiPackages,
        ).scan()
        return ModuleScan(
            loaded   = scan.loaded.map(::JarModule),
            disabled = scan.disabled.map { OffModule(it.id, it.name, it.file) },
            rejected = scan.rejected.map { RefusedModule(it.file, it.reason) },
            waiting  = scan.waiting.map { WaitingModule(it.id, it.name, it.file, it.reasons) },
            graph    = scan.graph,
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
    override val contributions: List<ModuleContribution> get() = module.contributions
    override val strings: ModuleStrings get() = module.strings
    override val requires: Set<String> get() = module.requires.keys

    override val filesInUse: Set<String>
        get() = module.loader.urLs.map { Path.of(it.toURI()).fileName.toString() }.toSet()

    override fun isSameLoad(other: LoadedModule): Boolean = other is JarModule && other.module.loader === module.loader

    override fun close() = module.loader.close()
}
