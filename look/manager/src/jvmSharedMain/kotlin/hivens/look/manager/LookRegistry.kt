package hivens.look.manager

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookAxis
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookPack
import hivens.look.manager.api.LookPackEntry
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookProviderEntry
import hivens.look.manager.api.LookSource
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import hivens.widget.model.ApiVersion
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

internal val log = LoggerFactory.getLogger("hivens.look.Manager")

/**
 * [block], or what it threw where that is a module's own failure: anything at all but a failing virtual
 * machine, whose stack overflowing is still the module's, and a cancellation while [alive] says the
 * manager's own work is cancelled. A cancellation the module throws while the manager goes on, a timeout
 * inside a provider say, is the module's failure like any other.
 */
internal inline fun <T> guarded(alive: () -> Boolean, block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Throwable) {
    if (e is CancellationException && !alive()) throw e
    if (e is VirtualMachineError && e !is StackOverflowError) throw e
    Result.failure(e)
}

/** An axis as a module brought it, its declarations read once. */
internal class AxisEntry(
    val moduleId: String,
    val axis: LookAxis<Any>,
    val id: String,
    val contract: ApiVersion,
    val base: Any,
) {
    fun accepts(value: Any?): Boolean = value != null && axis.type.isInstance(value)
}

/** A provider as a module brought it, its declarations read once. */
internal class ProviderEntry(
    val moduleId: String,
    val provider: LookProvider<Any>,
    val axis: String,
    val family: String,
    val contract: ApiVersion,
    val label: String,
    val capabilities: Set<String>,
) {
    fun view() = LookProviderEntry(moduleId, axis, family, label, capabilities)
}

/** A provider that answers an axis, and where in the choice it came from. */
internal class Candidate(val provider: ProviderEntry, val source: LookSource)

/**
 * The axes, providers and packs the modules brought in, in the order they came. The first of an axis id,
 * of a family for one axis and of a pack id is kept, and a later one is refused with a line in the log.
 * So is a pack of the default pack's id from a module not in [compiled], a provider built against a
 * contract its axis's version does not serve, and a contribution whose declarations cannot be read or
 * whose base answers are not of its own type, so it costs only itself.
 */
internal class LookRegistry private constructor(
    val axes: List<AxisEntry>,
    val providers: List<ProviderEntry>,
    val packs: List<LookPackEntry>,
) {
    private val byFamily = providers.associateBy { it.axis to it.family }
    private val packsById = packs.associateBy { it.id }

    fun provider(axis: String, family: String): ProviderEntry? = byFamily[axis to family]

    /**
     * The providers that may answer [axis] under [choice], in the order the choice tries them: the
     * person's family for the axis, the chosen pack's, then the default pack's.
     */
    fun candidates(axis: String, choice: LookChoice): List<Candidate> {
        val out = ArrayList<Candidate>(3)
        fun add(family: String?, source: LookSource) {
            family?.let { provider(axis, it) }?.let { out += Candidate(it, source) }
        }
        add(choice.overrides[axis], LookSource.Override)
        val pack = choice.pack
        if (pack != null) add(packsById[pack]?.providers?.get(axis), LookSource.Pack)
        if (pack != LookApi.DEFAULT_PACK) add(packsById[LookApi.DEFAULT_PACK]?.providers?.get(axis), LookSource.DefaultPack)
        return out
    }

    companion object {
        val EMPTY = LookRegistry(emptyList(), emptyList(), emptyList())

        /** What [taken] brings of the look, in its order, where [compiled] are the modules compiled into the launcher. */
        @Suppress("UNCHECKED_CAST")
        // An axis and a provider are read as answering in Any: the manager holds every answer to the axis's own type before it is used.
        fun of(taken: List<TakenContribution<ModuleContribution>>, compiled: Set<String>, alive: () -> Boolean): LookRegistry {
            val axes = LinkedHashMap<String, AxisEntry>()
            val providers = LinkedHashMap<Pair<String, String>, ProviderEntry>()
            val packs = LinkedHashMap<String, LookPackEntry>()
            taken.forEach { (moduleId, item) ->
                when (item) {
                    is LookAxis<*> -> guarded(alive) { axisEntry(moduleId, item as LookAxis<Any>) }
                        .onSuccess { entry -> keepFirst(axes, entry.id, entry, moduleId, "axis") }
                        .onFailure { log.warn("An axis of module '{}' could not be read, and is left out", moduleId, it) }
                    is LookProvider<*> -> guarded(alive) { providerEntry(moduleId, item as LookProvider<Any>) }
                        .onSuccess { entry -> keepFirst(providers, entry.axis to entry.family, entry, moduleId, "provider of axis '${entry.axis}' in family") }
                        .onFailure { log.warn("A look provider of module '{}' could not be read, and is left out", moduleId, it) }
                    is LookPack -> guarded(alive) { LookPackEntry(moduleId, item.id, item.label, item.providers.toMap()) }
                        .onSuccess { entry ->
                            if (entry.id == LookApi.DEFAULT_PACK && moduleId !in compiled) {
                                log.warn("Module '{}' brings a pack '{}', which only a module compiled into the launcher may, and it is refused", moduleId, entry.id)
                            } else {
                                keepFirst(packs, entry.id, entry, moduleId, "pack")
                            }
                        }
                        .onFailure { log.warn("A look pack of module '{}' could not be read, and is left out", moduleId, it) }
                }
            }
            val served = providers.values.filter { entry ->
                val axis = axes[entry.axis] ?: return@filter true
                axis.contract.serves(entry.contract).also { serves ->
                    if (!serves) {
                        log.warn(
                            "Look provider '{}' of module '{}' was built against contract {} of axis '{}', which {} does not serve, and is refused",
                            entry.family, entry.moduleId, entry.contract, entry.axis, axis.contract,
                        )
                    }
                }
            }
            return LookRegistry(axes.values.toList(), served, packs.values.toList())
        }

        private fun axisEntry(moduleId: String, axis: LookAxis<Any>): AxisEntry {
            val id = axis.id
            require(id.isNotBlank()) { "an axis has no id" }
            val base = axis.base
            require(axis.type.isInstance(base)) { "the base answers of axis '$id' are not its own type ${axis.type.qualifiedName}" }
            return AxisEntry(moduleId, axis, id, axis.contract, base)
        }

        private fun providerEntry(moduleId: String, provider: LookProvider<Any>): ProviderEntry {
            val axis = provider.axis
            val family = provider.family
            require(axis.isNotBlank() && family.isNotBlank()) { "provider '${provider.id}' names no axis or no family" }
            return ProviderEntry(moduleId, provider, axis, family, provider.contract, provider.label, provider.capabilities.toSet())
        }

        private fun <K, V> keepFirst(into: MutableMap<K, V>, key: K, entry: V, moduleId: String, what: String) {
            if (key in into) log.warn("Module '{}' brings a {} '{}' another module brought first, and it is refused", moduleId, what, key)
            else into[key] = entry
        }
    }
}

private operator fun <T : ModuleContribution> TakenContribution<T>.component1(): String = moduleId
private operator fun <T : ModuleContribution> TakenContribution<T>.component2(): T = item
