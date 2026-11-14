package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetCommand
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("hivens.widget.api.WidgetCommandRegistry")

/** How a level makes its own command for a key. Run once per level, the first time the key is fired or asked for there. */
typealias CommandFactory<P> = (LevelContext) -> WidgetCommand<P>

// App-provided commands widgets fire (the write counterpart of WidgetDataRegistry,
// and of the QML "call a service method"). Same shape and contract as the read
// side: app commands are app-static, registered once at startup, so this is a plain
// map and a missing or duplicate id is a wiring bug that fails loudly rather than
// a normal null state (unlike WidgetServiceRegistry, whose providers churn).
//
// A command below the app level has an answer per level, the way a source does:
// a factory each level runs for itself, or a declaration that the level's owner
// provides it (see WidgetScope.provideCommand and ProvideScopedCommand).
//
// find() and keys() are discovery seams -- they let a future editor / rule-engine
// list the available command ids. Firing a command by raw string id (the headless
// rule-engine path) is deliberately not here yet: it needs an untyped payload
// contract the rule-engine will define, so it lands with that consumer rather than
// shipping unsafe and unused now.
class WidgetCommandRegistry {

    private val commands = HashMap<String, WidgetCommand<*>>()

    private val factories = HashMap<String, Factory<*>>()

    private val declared = HashMap<String, ScopeLevel>()

    private class Factory<P>(val level: ScopeLevel, val make: CommandFactory<P>)

    fun <P> register(key: CommandKey<P>, command: WidgetCommand<P>) {
        require(key.level == ScopeLevel.App) {
            "widget command '${key.id}' lives at ${key.level}: register a factory for it"
        }
        requireNew(key.id)
        commands[key.id] = command
    }

    /**
     * Registers how a level of [key]'s kind makes its own command for [key], made
     * the first time it is asked for there and closed with the level if it holds
     * something to release.
     */
    fun <P> registerFactory(key: CommandKey<P>, factory: CommandFactory<P>) {
        requireNew(key.id)
        factories[key.id] = Factory(key.level, factory)
    }

    /**
     * Declares [key] as one the owner of each level of its kind provides. See
     * [WidgetDataRegistry.declare], which this mirrors.
     */
    fun declare(key: CommandKey<*>) {
        require(key.level != ScopeLevel.App) { "app-wide command '${key.id}' is registered with a value" }
        requireNew(key.id)
        declared[key.id] = key.level
    }

    private fun requireNew(id: String) {
        require(id !in commands && id !in factories && id !in declared) { "duplicate widget command id '$id'" }
    }

    /**
     * Runs the command registered as a value for [key]. Where none is, it does nothing
     * and says so in the log: a host may leave an app command out, and firing one is
     * no place to throw.
     */
    // Unchecked cast: register ties CommandKey<P> to WidgetCommand<P> at the call
    // site, so the only unsound path is a hand-written raw CommandKey paired with
    // a mismatched command -- the same documented seam as WidgetDataRegistry.get.
    @Suppress("UNCHECKED_CAST")
    fun <P> dispatch(key: CommandKey<P>, payload: P) {
        val command = commands[key.id]
        if (command == null) {
            log.warn("Widget command '{}' was dispatched where nothing answers it, and did nothing", key.id)
            return
        }
        (command as WidgetCommand<P>).run(payload)
    }

    // Convenience for payload-less commands; arity disambiguates it from the
    // generic overload even when P resolves to Unit.
    fun dispatch(key: CommandKey<Unit>) = dispatch(key, Unit)

    @Suppress("UNCHECKED_CAST")
    fun <P> find(key: CommandKey<P>): WidgetCommand<P>? =
        commands[key.id] as WidgetCommand<P>?

    /** The factory registered for [key] at its own level, or null. Same cast seam as [dispatch]. */
    @Suppress("UNCHECKED_CAST")
    internal fun <P> factoryFor(key: CommandKey<P>): CommandFactory<P>? {
        val factory = factories[key.id] ?: return null
        return if (factory.level == key.level) factory.make as CommandFactory<P> else null
    }

    /** Where the command [id] lives and what answers it, or null when nothing here names it. */
    fun declarationOf(id: String): KeyDeclaration? {
        if (id in commands) return KeyDeclaration(id, ScopeLevel.App, KeyKind.Command, KeyOrigin.Registered)
        factories[id]?.let { return KeyDeclaration(id, it.level, KeyKind.Command, KeyOrigin.Factory) }
        return declared[id]?.let { KeyDeclaration(id, it, KeyKind.Command, KeyOrigin.Owner) }
    }

    /**
     * The sample of the command [id] in the sense of [WidgetDataRegistry.declare]: one
     * that does nothing, for every command a factory makes or an owner provides,
     * whatever its level. A widget drawn on samples is there to be looked at, so
     * whatever it fires has to go nowhere. Null for a command registered as a value,
     * which is always there, and for an id nothing here names.
     */
    fun sampleOf(id: String): (() -> Any?)? = if (id in factories || id in declared) SAMPLE else null

    fun keys(): Set<String> = commands.keys + factories.keys + declared.keys

    private companion object {
        val NOTHING: WidgetCommand<Any?> = WidgetCommand { }
        val SAMPLE: () -> Any? = { NOTHING }
    }
}
