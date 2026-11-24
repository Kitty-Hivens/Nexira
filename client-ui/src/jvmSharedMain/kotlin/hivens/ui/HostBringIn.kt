package hivens.ui

import hivens.widget.api.BringIn
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.WidgetScope
import hivens.ui.widgets.modules.moduleGraph
import hivens.widget.model.ModuleGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The compiled modules that are in: the ones the document names, and the ones brought in since through
 * [HostBringIn]. What the host hands the trusted modules of the contributions follows [ids].
 */
internal class ModulesIn(named: Set<String>) {
    private val held = MutableStateFlow(named)

    /** The ids of the compiled modules in now. */
    val ids: StateFlow<Set<String>> = held.asStateFlow()

    fun add(moduleId: String) = held.update { it + moduleId }
}

/**
 * Brings in a module of [compiled] the document does not name, through the same registration the
 * document's modules went through ([compiledModuleKeys]), into [keys], then adds it to [modules] and has
 * the levels under [app] read its keys again where a reader found them missing. What the module requires
 * goes in before it.
 *
 * One bring-in at a time, under one lock, since checking that a key is free and registering it are two
 * steps. Registering and telling the levels are safe from any thread, so a bring-in runs on the thread
 * that asked. A module refused, or one the build does not carry, is said once in the log and answered
 * false from then on.
 */
internal class HostBringIn(
    private val keys: HostKeys,
    private val compiled: List<CompiledModule>,
    private val modules: ModulesIn,
    private val app: () -> WidgetScope?,
    private val nameOf: (moduleId: String, key: String) -> String = { _, key -> key },
) : BringIn {
    private val lock = Any()
    private val graph: ModuleGraph by lazy { moduleGraph(compiled) }
    private val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun bringIn(moduleId: String): Boolean {
        // A module already in, or already refused, is answered without waiting for a bring-in under way.
        settled(moduleId)?.let { return it }
        return synchronized(lock) { settled(moduleId) ?: bring(moduleId) }
    }

    private fun settled(moduleId: String): Boolean? = when (moduleId) {
        in refused -> false
        in modules.ids.value -> true
        else -> null
    }

    private fun bring(moduleId: String): Boolean {
        val module = compiled.firstOrNull { it.id == moduleId }
        if (module == null) {
            log.warn("The module '{}' was asked to be brought in, and this launcher carries no compiled module of that id", moduleId)
            refused += moduleId
            return false
        }
        graph.waiting[moduleId]?.let { reasons ->
            log.error("The module '{}' is not brought in, since what it requires is not in this build: {}", moduleId, reasons)
            refused += moduleId
            return false
        }
        graph.requiredBy(listOf(moduleId)).firstOrNull { !(settled(it) ?: bring(it)) }?.let { missing ->
            log.warn("The module '{}' is not brought in, since the module '{}' it requires is not", moduleId, missing)
            refused += moduleId
            return false
        }
        val results = compiledModuleKeys(keys, module, nameOf)
        // Its keys that went in stay in, as they do for a module the document names, and it counts as in.
        val whole = results.none { it is ModuleKeysResult.Refused }
        if (!whole) refused += moduleId
        modules.add(moduleId)
        app()?.forgetMade(results.filterIsInstance<ModuleKeysResult.Registered>().flatMapTo(HashSet()) { it.ids })
        if (!whole) log.warn("The module '{}' is in with only part of its keys, and is answered as not brought in", moduleId)
        return whole
    }

    private companion object {
        val log = LoggerFactory.getLogger("ModuleKeys")
    }
}
