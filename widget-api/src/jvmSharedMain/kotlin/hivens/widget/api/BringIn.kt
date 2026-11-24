package hivens.widget.api

/**
 * Brings in a module compiled into the build that the document does not name, as if the document named
 * it: its keys, its services and its settings domains go in through the same checks, and what it carries
 * of the kinds the compiled modules take reaches them ([TakenContributions.all]). For a module the host
 * trusts that runs another module's code with what that module may use, a contribution it takes say, and
 * needs that module in for it.
 *
 * One of the host's own services: the host and the modules it trusts reach it through their host view,
 * and no jar does. Nothing is brought in before the host is up. A module brought in stays in for as long
 * as the app level lives. Safe to call from any thread, and quick for a module already in. What the
 * module requires ([CompiledModule.requires]) is brought in before it.
 */
@HostApi
fun interface BringIn {
    /**
     * Brings in the compiled module [moduleId], and what it requires before it. True where it is in
     * afterwards: the document names it, it was brought in before, or it is now. False where the build
     * carries no compiled module of that id, something it requires is not in, or the host refused its keys,
     * which the log says. A module refused once is not tried again.
     */
    fun bringIn(moduleId: String): Boolean
}
