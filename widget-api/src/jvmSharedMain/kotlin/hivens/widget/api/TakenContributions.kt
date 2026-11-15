package hivens.widget.api

import kotlinx.coroutines.flow.StateFlow

/**
 * A contribution of a kind a compiled module takes, with the module that brought it. The
 * module's id is also the namespace what the contribution declares for a person to read
 * is looked up in.
 */
class TakenContribution<out T : ModuleContribution>(val moduleId: String, val item: T)

/**
 * What the modules brought in contribute of the kinds the compiled modules take
 * ([CompiledModule.contributionKinds]), as it changes: the contributions of every compiled
 * module the document names, then those of every jar switched on, in the order they load.
 * A jar switched off, loaded again or taken away changes it.
 *
 * The host keeps one and hands it to the modules it trusts as one of its own services,
 * reached through their host view. Each takes the kinds its API declares out of it and
 * makes of them what that API says. Nothing registers into it: a module is in it by
 * carrying the contribution, and out of it by not being brought in.
 */
@HostApi
interface TakenContributions {
    val all: StateFlow<List<TakenContribution<ModuleContribution>>>

    /**
     * What every module compiled into the build carries of these kinds, brought in or
     * not, for a module that has to keep a jar from taking the place of one of them: a
     * jar's source declaring the id a compiled-in source carries, say. Empty by default.
     */
    val compiledIn: List<TakenContribution<ModuleContribution>> get() = emptyList()
}

/** The contributions of kind [T] among these, in the same order. */
inline fun <reified T : ModuleContribution> List<TakenContribution<ModuleContribution>>.ofKind(): List<TakenContribution<T>> =
    mapNotNull { taken -> (taken.item as? T)?.let { TakenContribution(taken.moduleId, it) } }
