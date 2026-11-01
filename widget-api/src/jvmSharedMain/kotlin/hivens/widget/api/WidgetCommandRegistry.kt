package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.WidgetCommand

// App-provided commands widgets fire (the write counterpart of WidgetDataRegistry,
// and of the QML "call a service method"). Same shape and contract as the read
// side: commands are app-static, registered once at startup, so this is a plain
// map and a missing or duplicate id is a wiring bug that fails loudly rather than
// a normal null state (unlike WidgetServiceRegistry, whose providers churn).
//
// find() and keys() are discovery seams -- they let a future editor / rule-engine
// list the available command ids. Firing a command by raw string id (the headless
// rule-engine path) is deliberately not here yet: it needs an untyped payload
// contract the rule-engine will define, so it lands with that consumer rather than
// shipping unsafe and unused now.
class WidgetCommandRegistry {

    private val commands = HashMap<String, WidgetCommand<*>>()

    fun <P> register(key: CommandKey<P>, command: WidgetCommand<P>) {
        require(key.id !in commands) { "duplicate widget command id '${key.id}'" }
        commands[key.id] = command
    }

    // Unchecked cast: register ties CommandKey<P> to WidgetCommand<P> at the call
    // site, so the only unsound path is a hand-written raw CommandKey paired with
    // a mismatched command -- the same documented seam as WidgetDataRegistry.get.
    @Suppress("UNCHECKED_CAST")
    fun <P> dispatch(key: CommandKey<P>, payload: P) {
        val command = commands[key.id] ?: error("no widget command registered for '${key.id}'")
        (command as WidgetCommand<P>).run(payload)
    }

    // Convenience for payload-less commands; arity disambiguates it from the
    // generic overload even when P resolves to Unit.
    fun dispatch(key: CommandKey<Unit>) = dispatch(key, Unit)

    @Suppress("UNCHECKED_CAST")
    fun <P> find(key: CommandKey<P>): WidgetCommand<P>? =
        commands[key.id] as WidgetCommand<P>?

    fun keys(): Set<String> = commands.keys.toSet()
}
