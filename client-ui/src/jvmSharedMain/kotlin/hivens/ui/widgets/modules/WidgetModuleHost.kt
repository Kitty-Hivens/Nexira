package hivens.ui.widgets.modules

import hivens.widget.api.ModuleStrings
import hivens.widget.api.ScreenEffect
import hivens.widget.api.WidgetRegistry
import java.nio.file.Path

/**
 * A widget module that is loaded: its code stays live until [close].
 *
 * What the code is read from and how it was brought in is the host's business. A
 * desktop opens a jar in a class loader of its own, a phone reads a dex file.
 */
interface LoadedModule : AutoCloseable {
    val id: String
    val name: String
    val file: Path
    val registry: WidgetRegistry

    /** The screen effects this module carries, none for one that only has widgets. */
    val effects: List<ScreenEffect> get() = emptyList()

    /** The words it brought for what it declares for a person to read. */
    val strings: ModuleStrings get() = ModuleStrings.EMPTY

    /** The names of the files this module's code is read from, so copies nothing reads any more can be swept. */
    val filesInUse: Set<String>

    /** True when [other] is this same load of this same code, and not a fresh load of it. */
    fun isSameLoad(other: LoadedModule): Boolean
}

/** A module found and switched off, so not loaded. */
class OffModule(val id: String, val name: String, val file: Path)

/** A file in the modules folder that is not a module this launcher can load, and why. */
class RefusedModule(val file: Path, val reason: String)

/** What reading the modules folder found. */
class ModuleScan(
    val loaded: List<LoadedModule> = emptyList(),
    val disabled: List<OffModule> = emptyList(),
    val rejected: List<RefusedModule> = emptyList(),
)

/** Finds the widget modules a host can load and brings them in. */
interface WidgetModuleHost {
    /** Reads the modules folder, loading every module not in [disabled]. */
    fun scan(disabled: Set<String>): ModuleScan

    /** The id of the module whose code is on [failure]'s stack, or null when none is. */
    fun moduleIdIn(failure: Throwable): String?

    /** Removes what the host keeps beside the modules that no loaded module reads: [inUse] names the files that are still read. */
    fun sweepUnused(inUse: Set<String>)
}

/** A host with no modules: only the widgets built into the application. */
object NoWidgetModuleHost : WidgetModuleHost {
    override fun scan(disabled: Set<String>): ModuleScan = ModuleScan()
    override fun moduleIdIn(failure: Throwable): String? = null
    override fun sweepUnused(inUse: Set<String>) = Unit
}
