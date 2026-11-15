package hivens.ui.widgets.modules

import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the modules brought in contribute of the kinds [compiled] take, as the host hands it
 * to the modules it trusts: the contributions of every compiled module in [named], in the
 * build's order, then those of every jar [jars] carries, in the order they loaded.
 *
 * A compiled module's key contributions are the host's own business and never in it, and
 * neither is anything of a kind no compiled module takes. What every compiled module
 * carries, named or not, is handed over beside it as [TakenContributions.compiledIn].
 */
internal fun hostTakenContributions(
    named: Set<String>,
    compiled: List<CompiledModule>,
    jars: StateFlow<WidgetModules.Snapshot>,
    scope: CoroutineScope,
): TakenContributions {
    val kinds = compiled.flatMap { it.contributionKinds }.distinct()
    fun taken(item: ModuleContribution) = kinds.any { it.isInstance(item) }
    val everyCompiled = compiled.flatMap { module ->
        module.contributions.filter(::taken).map { TakenContribution(module.id, it) }
    }
    val fromCompiled = everyCompiled.filter { it.moduleId in named }
    fun all(snapshot: WidgetModules.Snapshot): List<TakenContribution<ModuleContribution>> =
        fromCompiled + snapshot.contributions.filter { taken(it.item) }.map { TakenContribution(it.moduleId, it.item) }
    val flow = jars.map(::all).stateIn(scope, SharingStarted.Eagerly, all(jars.value))
    return object : TakenContributions {
        override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = flow
        override val compiledIn: List<TakenContribution<ModuleContribution>> = everyCompiled
    }
}
