package hivens.ui.widgets.modules

import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the modules brought in contribute of the kinds [compiled] take, as the host hands it
 * to the modules it trusts: the contributions of every compiled module in [brought], the
 * ones the document names and the ones brought in since, in the build's order, then those
 * of every jar [jars] carries, in the order they loaded.
 *
 * A compiled module's key contributions are the host's own business and never in it, and
 * neither is anything of a kind no compiled module takes. What every compiled module
 * carries, brought in or not, is handed over beside it as [TakenContributions.compiledIn].
 */
internal fun hostTakenContributions(
    brought: StateFlow<Set<String>>,
    compiled: List<CompiledModule>,
    jars: StateFlow<WidgetModules.Snapshot>,
    scope: CoroutineScope,
): TakenContributions {
    val kinds = takenKinds(compiled)
    val everyCompiled = compiledTaken(compiled, kinds)
    fun all(snapshot: WidgetModules.Snapshot, ids: Set<String>): List<TakenContribution<ModuleContribution>> =
        everyCompiled.filter { it.moduleId in ids } +
            snapshot.contributions.filter { item -> kinds.any { it.isInstance(item.item) } }.map { TakenContribution(it.moduleId, it.item) }
    val flow = combine(jars, brought, ::all).stateIn(scope, SharingStarted.Eagerly, all(jars.value, brought.value))
    return object : TakenContributions {
        override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = flow
        override val compiledIn: List<TakenContribution<ModuleContribution>> = everyCompiled
    }
}

/**
 * What the host hands over before it is up, when no jar is loaded yet: [hostTakenContributions] with
 * the compiled modules alone, fixed.
 */
internal fun bootTakenContributions(named: Set<String>, compiled: List<CompiledModule>): TakenContributions {
    val everyCompiled = compiledTaken(compiled, takenKinds(compiled))
    val fromCompiled = MutableStateFlow(everyCompiled.filter { it.moduleId in named }).asStateFlow()
    return object : TakenContributions {
        override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = fromCompiled
        override val compiledIn: List<TakenContribution<ModuleContribution>> = everyCompiled
    }
}

private fun takenKinds(compiled: List<CompiledModule>): List<Class<out ModuleContribution>> = compiled.flatMap { it.contributionKinds }.distinct()

/** What every module of [compiled] carries of [kinds], in the build's order. */
private fun compiledTaken(compiled: List<CompiledModule>, kinds: List<Class<out ModuleContribution>>): List<TakenContribution<ModuleContribution>> =
    compiled.flatMap { module ->
        module.contributions.filter { item -> kinds.any { it.isInstance(item) } }.map { TakenContribution(module.id, it) }
    }
