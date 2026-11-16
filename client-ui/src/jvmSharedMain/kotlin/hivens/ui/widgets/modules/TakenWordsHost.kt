package hivens.ui.widgets.modules

import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleStrings
import hivens.widget.api.TakenWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.slf4j.LoggerFactory

/**
 * The words the launcher ships under its own namespace, then those of every module compiled into
 * the build, brought in or not, in the build's order: what the language module looks up before any
 * jar is loaded, the window the launcher boots in among them. An owner whose words cannot be read is
 * left out and logged, and costs only its own words.
 */
internal fun launcherWords(compiled: List<CompiledModule>): Map<String, ModuleStrings> =
    LinkedHashMap<String, ModuleStrings>().apply {
        guarded("the launcher's own") { put(NexiraStrings.NAMESPACE, NexiraStrings.words()) }
        compiled.forEach { module -> guarded("a compiled module's") { put(module.id, module.strings) } }
    }

private inline fun guarded(whose: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        log.warn("{} words could not be read, and are left out", whose, e)
    }
}

private val log = LoggerFactory.getLogger("hivens.ui.widgets.modules.TakenWords")

/**
 * The words every owner ships as the host hands them to the modules it trusts: [launcherWords], then
 * those of every jar [jars] carries, in the order they loaded, as the jars change.
 */
internal fun hostTakenWords(compiled: List<CompiledModule>, jars: StateFlow<WidgetModules.Snapshot>, scope: CoroutineScope): TakenWords {
    val fixed = launcherWords(compiled)
    fun all(snapshot: WidgetModules.Snapshot): Map<String, ModuleStrings> = fixed + snapshot.words
    val flow = jars.map(::all).stateIn(scope, SharingStarted.Eagerly, all(jars.value))
    return object : TakenWords {
        override val all: StateFlow<Map<String, ModuleStrings>> = flow
    }
}

/** [words] as words that never change, for the window the launcher boots in. */
internal fun fixedTakenWords(words: Map<String, ModuleStrings>): TakenWords = object : TakenWords {
    override val all: StateFlow<Map<String, ModuleStrings>> = MutableStateFlow(words).asStateFlow()
}
