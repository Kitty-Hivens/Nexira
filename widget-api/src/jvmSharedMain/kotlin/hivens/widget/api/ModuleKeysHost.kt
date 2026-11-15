package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

private val log = LoggerFactory.getLogger("hivens.widget.api.ModuleKeysHost")

/** What registering a module's keys came to. */
@HostApi
sealed interface ModuleKeysResult {
    /** Everything the module declared is in, these source and command ids among it. */
    data class Registered(val sources: Set<String>, val commands: Set<String>) : ModuleKeysResult {
        /** Every key id the module brought. */
        val ids: Set<String> get() = sources + commands
    }

    /**
     * Nothing the module declared is in, for [reason]. [cause] is what the module's own
     * code threw, null when the declaration ran and did not fit, an id already taken.
     */
    data class Refused(val reason: String, val cause: Throwable? = null) : ModuleKeysResult
}

/**
 * Registers what [contribution] of the module [moduleId] declares, all of it or none.
 * For a module whose code never changes under the launcher, the ones compiled in.
 * See the other overload for [trusted].
 */
@HostApi
fun registerModuleKeys(
    moduleId: String,
    contribution: KeyContribution,
    sources: WidgetDataRegistry,
    commands: WidgetCommandRegistry,
    services: ContributedServices,
    trusted: Boolean = false,
): ModuleKeysResult = registerModuleKeys(moduleId, { contribution }, sources, commands, services, trusted)

/**
 * Registers what the module [moduleId] declares into [sources], [commands] and
 * [services], all of it or none.
 *
 * [current] answers the module's contribution as it is now: null once the module is
 * gone, a fresh instance once it was loaded again. Every factory, service maker and
 * sample the module brought asks it at the moment it runs, and runs the declaration
 * of that instance, declared again when the instance changed. So a module loaded
 * again answers its keys with its new code, a module that left leaves its keys
 * missing, and neither runs code of a class loader that was closed. A key the new
 * code no longer declares, or declares at another level, is missing too.
 *
 * What the module's code throws is contained: the declaration's failure refuses the
 * module and is handed back as [ModuleKeysResult.Refused.cause] for the host to pin
 * on the module, and a factory's or a maker's failure leaves its key missing the way
 * a failing factory does. Only the errors after which the VM cannot go on are passed
 * through.
 *
 * [trusted] says whether the module may reach the host's own services through
 * [LevelContext.hostServices] and [ServiceContext.hostServices]. A module that is not
 * trusted sees there what every module sees in `services`: the services modules
 * declared and the host types the host exposed (see [ContributedServices.expose]).
 * The host trusts the modules compiled into it and no jar.
 *
 * Whoever the module is, the [SettingsStores] its factories and service makers find
 * answer only the settings domains it declared itself (see [ContributedServices.claimDomain]),
 * so no module reads or replaces another's.
 */
@HostApi
fun registerModuleKeys(
    moduleId: String,
    current: () -> KeyContribution?,
    sources: WidgetDataRegistry,
    commands: WidgetCommandRegistry,
    services: ContributedServices,
    trusted: Boolean = false,
): ModuleKeysResult {
    val contribution = try {
        current()
    } catch (e: Throwable) {
        if (e.fatal()) throw e
        return ModuleKeysResult.Refused("it could not be read: ${e.message ?: e.javaClass.simpleName}", e)
    } ?: return ModuleKeysResult.Refused("it is not loaded")
    val first = try {
        Declaration.of(contribution)
    } catch (e: Throwable) {
        if (e.fatal()) throw e
        log.error("Module '{}' could not declare its keys", moduleId, e)
        return ModuleKeysResult.Refused("its keys could not be declared: ${e.message ?: e.javaClass.simpleName}", e)
    }
    first.fault(moduleId, sources, commands, services, trusted)?.let { return ModuleKeysResult.Refused(it) }
    val live = Live(moduleId, current, contribution, first, services)
    first.commit(moduleId, live, sources, commands, services, trusted)
    return ModuleKeysResult.Registered(
        sources = first.sources.keys + first.ownedSources.keys,
        commands = first.commands.keys + first.ownedCommands.keys,
    )
}

/** An error after which nothing in the VM can be relied on. A stack overflow is the module's alone. */
private fun Throwable.fatal(): Boolean = this is VirtualMachineError && this !is StackOverflowError

/**
 * Runs [block], module code, and turns whatever it throws that is not fatal and not an
 * ordinary exception into one, so the level that called it leaves the key missing
 * instead of crashing the frame on a `TODO()` or a linkage error.
 *
 * The thread's context class loader is [loader], the module's own, while it runs, so a
 * library the module bundles that finds its providers through it finds the module's.
 */
private inline fun <R> contained(moduleId: String, what: String, loader: ClassLoader?, block: () -> R): R =
    try {
        inModule(loader, block)
    } catch (e: Exception) {
        throw e
    } catch (e: Throwable) {
        if (e.fatal()) throw e
        throw IllegalStateException("module '$moduleId' failed making '$what': ${e.message ?: e.javaClass.simpleName}", e)
    }

/** Runs [block] with [loader] as the thread's context class loader, and puts the one before back. */
private inline fun <R> inModule(loader: ClassLoader?, block: () -> R): R {
    val thread = Thread.currentThread()
    val before = thread.contextClassLoader
    if (loader != null) thread.contextClassLoader = loader
    try {
        return block()
    } finally {
        if (loader != null) thread.contextClassLoader = before
    }
}

/**
 * The module's current declaration, declared again whenever [current] answers another
 * instance. A declaration of the module loaded again that names a settings domain another
 * module holds is refused, so the keys it would answer are missing rather than reaching
 * the other module's file.
 */
private class Live(
    private val moduleId: String,
    private val current: () -> KeyContribution?,
    private var instance: KeyContribution,
    private var declaration: Declaration,
    private val services: ContributedServices,
) {
    @Synchronized
    fun declaration(what: String): Declaration = contained(moduleId, what, instance.javaClass.classLoader) {
        val now = current() ?: error("module '$moduleId' is no longer loaded, so '$what' cannot be made")
        if (now !== instance) {
            val fresh = Declaration.of(now)
            fresh.domains.keys.forEach { id ->
                val owner = services.ownerOfDomain(id)
                check(owner == null || owner == moduleId) { "module '$moduleId' loaded again declares the settings domain '$id', which is '$owner's" }
            }
            declaration = fresh
            instance = now
        }
        declaration
    }
}

/** What one [KeyContribution.declare] said, by id, and [loader], the class loader of the module that said it. */
private class Declaration(val loader: ClassLoader?) : ModuleKeys {

    class Source(val key: SourceKey<*>, val sample: (() -> Any?)?, val factory: SourceFactory<*>)
    class Command(val key: CommandKey<*>, val factory: CommandFactory<*>)
    class Service(val level: ScopeLevel, val hostOnly: Boolean, val make: (ServiceContext) -> Any)

    val sources = LinkedHashMap<String, Source>()
    val commands = LinkedHashMap<String, Command>()
    val ownedSources = LinkedHashMap<String, Pair<SourceKey<*>, (() -> Any?)?>>()
    val ownedCommands = LinkedHashMap<String, CommandKey<*>>()
    val services = LinkedHashMap<KClass<*>, Service>()
    val domains = LinkedHashMap<String, SettingsDomain<*>>()

    /** The first thing the declaration said that cannot stand on its own. */
    private var flaw: String? = null

    private fun flaw(text: String) {
        if (flaw == null) flaw = text
    }

    private fun takenSource(id: String) = id in sources || id in ownedSources
    private fun takenCommand(id: String) = id in commands || id in ownedCommands

    override fun <T> source(key: SourceKey<T>, sample: (() -> T)?, factory: SourceFactory<T>) {
        if (takenSource(key.id)) return flaw("it declares the source '${key.id}' twice")
        sources[key.id] = Source(key, sample, factory)
    }

    override fun <P> command(key: CommandKey<P>, factory: CommandFactory<P>) {
        if (takenCommand(key.id)) return flaw("it declares the command '${key.id}' twice")
        commands[key.id] = Command(key, factory)
    }

    override fun <T> owned(key: SourceKey<T>, sample: (() -> T)?) {
        if (key.level == ScopeLevel.App) return flaw("app source '${key.id}' is made by a factory, not owned")
        if (takenSource(key.id)) return flaw("it declares the source '${key.id}' twice")
        ownedSources[key.id] = key to sample
    }

    override fun owned(key: CommandKey<*>) {
        if (key.level == ScopeLevel.App) return flaw("app command '${key.id}' is made by a factory, not owned")
        if (takenCommand(key.id)) return flaw("it declares the command '${key.id}' twice")
        ownedCommands[key.id] = key
    }

    override fun <T : Any> service(level: ScopeLevel, type: KClass<T>, make: (ServiceContext) -> T) {
        if (type in services) return flaw("it declares the service ${type.qualifiedName} twice")
        services[type] = Service(level, hostOnly = false, make)
    }

    // The value is read and changed through the store the host keeps, held to this module's
    // own domains and looked up when the key is made, so a module loaded again reads through
    // the domain its new code declared.
    override fun <T : Any> settings(domain: SettingsDomain<T>, source: SourceKey<T>, update: CommandKey<SettingsChange<T>>) {
        SettingsDomain.faultIn(domain.id)?.let { return flaw(it) }
        if (domain.id in domains) return flaw("it declares the settings domain '${domain.id}' twice")
        if (source.level != ScopeLevel.App) return flaw("settings source '${source.id}' is not an app key")
        if (update.level != ScopeLevel.App) return flaw("settings command '${update.id}' is not an app key")
        domains[domain.id] = domain
        source(source, sample = { domain.defaults() }) { app -> flowSource(app.services.get<SettingsStores>().of(domain).value) }
        command(update) { app ->
            val store = app.services.get<SettingsStores>().of(domain)
            command { change -> store.update(change) }
        }
    }

    override fun <T : Any> hostService(level: ScopeLevel, type: KClass<T>, make: (ServiceContext) -> T) {
        if (type in services) return flaw("it declares the service ${type.qualifiedName} twice")
        services[type] = Service(level, hostOnly = true, make)
    }

    /**
     * Why these cannot all be registered beside what [sources], [commands] and
     * [services] hold, for a module [trusted] or not, or null when they can.
     */
    fun fault(
        moduleId: String,
        sources: WidgetDataRegistry,
        commands: WidgetCommandRegistry,
        services: ContributedServices,
        trusted: Boolean,
    ): String? {
        flaw?.let { return it }
        if (!trusted) {
            this.services.entries.firstOrNull { it.value.hostOnly }
                ?.let { return "only a module the host trusts may keep the service ${it.key.qualifiedName} for the host" }
        }
        (this.sources.keys + ownedSources.keys).firstOrNull { sources.declarationOf(it) != null }
            ?.let { return "the source '$it' is already answered elsewhere" }
        (this.commands.keys + ownedCommands.keys).firstOrNull { commands.declarationOf(it) != null }
            ?.let { return "the command '$it' is already answered elsewhere" }
        this.services.keys.firstNotNullOfOrNull { type -> services.ownerOf(type)?.let { type to it } }
            ?.let { (type, owner) -> return "the service ${type.qualifiedName} is already declared by '$owner'" }
        domains.keys.firstNotNullOfOrNull { id -> services.ownerOfDomain(id)?.let { id to it } }
            ?.let { (id, owner) -> return "the settings domain '$id' is already declared by '$owner'" }
        if (moduleId.isBlank()) return "it has no id"
        return null
    }

    /**
     * Registers every key and service through [live], so each runs the module's
     * declaration as it is when it runs, not this one.
     */
    @Suppress("UNCHECKED_CAST")
    fun commit(
        moduleId: String,
        live: Live,
        sources: WidgetDataRegistry,
        commands: WidgetCommandRegistry,
        services: ContributedServices,
        trusted: Boolean,
    ) {
        this.sources.values.forEach { declared ->
            val key: SourceKey<Any?> = declared.key
            val factory: SourceFactory<Any?> = { context ->
                val declaration = live.declaration(key.id)
                val now = declaration.sources[key.id]?.takeIf { it.key.level == key.level }
                    ?: error("module '$moduleId' no longer declares the source '${key.id}' at ${key.level}")
                contained(moduleId, key.id, declaration.loader) { now.factory(context.forModule(moduleId, trusted, services)) }
            }
            if (declared.sample == null) {
                sources.registerFactory(key, factory)
            } else {
                sources.registerFactory(key, {
                    val declaration = live.declaration(key.id)
                    val now = declaration.sources[key.id]?.sample
                        ?: error("module '$moduleId' no longer has a sample for '${key.id}'")
                    contained(moduleId, key.id, declaration.loader) { now() }
                }, factory)
            }
        }
        this.commands.values.forEach { declared ->
            val key = declared.key as CommandKey<Any?>
            commands.registerFactory(key) { context ->
                val declaration = live.declaration(key.id)
                val now = declaration.commands[key.id]?.takeIf { it.key.level == key.level }
                    ?: error("module '$moduleId' no longer declares the command '${key.id}' at ${key.level}")
                contained(moduleId, key.id, declaration.loader) { (now.factory as CommandFactory<Any?>)(context.forModule(moduleId, trusted, services)) }
            }
        }
        ownedSources.values.forEach { (key, sample) ->
            if (sample == null) {
                sources.declare(key)
            } else {
                sources.declare(key) {
                    val declaration = live.declaration(key.id)
                    val now = declaration.ownedSources[key.id]?.second
                        ?: error("module '$moduleId' no longer has a sample for '${key.id}'")
                    contained(moduleId, key.id, declaration.loader) { now() }
                }
            }
        }
        ownedCommands.values.forEach { commands.declare(it) }
        domains.keys.forEach { services.claimDomain(it, moduleId) }
        this.services.forEach { (type, declared) ->
            val name = type.simpleName ?: "a service"
            services.declare(declared.level, type as KClass<Any>, moduleId, trusted, declared.hostOnly) { context ->
                val declaration = live.declaration(name)
                val now = declaration.services[type]?.takeIf { it.level == declared.level && it.hostOnly == declared.hostOnly }
                    ?: error("module '$moduleId' no longer declares the service $name at ${declared.level}")
                contained(moduleId, name, declaration.loader) { now.make(context) }
            }
        }
    }

    companion object {
        /**
         * [contribution]'s declaration, run with the module's class loader as the thread's
         * context class loader. Throws what the module's code threw.
         */
        fun of(contribution: KeyContribution): Declaration {
            val loader = contribution.javaClass.classLoader
            return Declaration(loader).also { inModule(loader) { contribution.declare(it) } }
        }
    }
}

/**
 * [this] as the module [moduleId] may see it. One that is not [trusted] sees the level
 * without its host view, so `hostServices` answers only what every module sees. Every
 * module finds, in place of the host's [SettingsStores], one that answers only the
 * domains it declared.
 */
private fun LevelContext.forModule(moduleId: String, trusted: Boolean, services: ContributedServices): LevelContext {
    val stores = OwnedStores.of(this as? HostLevelContext, moduleId, services)
    return if (trusted && this is HostLevelContext) TrustedModuleContext(this, stores) else ModuleContext(this, stores)
}

private class ModuleContext(private val inner: LevelContext, stores: SettingsStores?) : LevelContext by inner {
    override val services: LevelServices = WithStores(inner.services, stores)
}

private class TrustedModuleContext(private val inner: HostLevelContext, stores: SettingsStores?) : HostLevelContext by inner {
    override val services: LevelServices = WithStores(inner.services, stores)
    override val hostServices: LevelServices = WithStores(inner.hostServices, stores)
}

/** [inner] answering [stores] for [SettingsStores], or nothing where the host keeps no stores. */
private class WithStores(private val inner: LevelServices, private val stores: SettingsStores?) : LevelServices {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> find(type: KClass<T>): T? = if (type == SettingsStores::class) stores as T? else inner.find(type)

    override fun <T : Any> get(type: KClass<T>): T =
        find(type) ?: if (type == SettingsStores::class) throw NoSuchElementException("the host keeps no settings") else inner.get(type)
}

/** The host's [stores] as the module [owner] may use them: the domains it declared, and no other. */
private class OwnedStores(
    private val stores: SettingsStores,
    private val owner: String,
    private val services: ContributedServices,
) : SettingsStores {
    override fun <T : Any> of(domain: SettingsDomain<T>): SettingsStore<T> {
        val holder = services.ownerOfDomain(domain.id)
        check(holder == owner) {
            if (holder == null) "module '$owner' did not declare the settings domain '${domain.id}'"
            else "the settings domain '${domain.id}' is '$holder's, not '$owner's"
        }
        return stores.of(domain)
    }

    companion object {
        /** The stores the host keeps at [level], held to [owner]'s domains, or null where it keeps none. */
        fun of(level: HostLevelContext?, owner: String, services: ContributedServices): SettingsStores? =
            level?.hostServices?.find(SettingsStores::class)?.let { OwnedStores(it, owner, services) }

        /** The same over a level's [host] view. */
        fun of(host: LevelServices, owner: String, services: ContributedServices): SettingsStores? =
            host.find(SettingsStores::class)?.let { OwnedStores(it, owner, services) }
    }
}

/**
 * The services modules declared, by type, the levels they are made for, and the host
 * types every module may resolve.
 *
 * The host lays these over its own services with [host], which gives every level two
 * views. Its `services`, which modules and their widgets read, answers the services
 * modules declared for everyone and the host types [expose]d and nothing else, so the
 * host's own graph (a session store with its tokens, say) stays out of a module's
 * reach. Its `hostServices` answers the host's own services first and then every
 * declared one, those kept for the host among them, for the host and for the modules
 * it trusts. A module's service is made lazily, once per level of its kind, and
 * closed with that level's services.
 */
@HostApi
class ContributedServices {

    private class Declared(
        val level: ScopeLevel,
        val owner: String,
        val trusted: Boolean,
        val hostOnly: Boolean,
        val make: (ServiceContext) -> Any,
    )

    private val declared = HashMap<KClass<*>, Declared>()

    private val exposed = HashSet<KClass<*>>()

    /**
     * Declares [type] as made by [make] once per level of kind [level], on behalf of
     * module [owner]. With [hostOnly] it is answered only through a level's host view.
     */
    fun <T : Any> declare(
        level: ScopeLevel,
        type: KClass<T>,
        owner: String,
        trusted: Boolean = false,
        hostOnly: Boolean = false,
        make: (ServiceContext) -> T,
    ) {
        synchronized(declared) {
            require(type !in declared) { "the service ${type.qualifiedName} is already declared by '${declared.getValue(type).owner}'" }
            declared[type] = Declared(level, owner, trusted, hostOnly, make)
        }
    }

    /**
     * Lets every module resolve the host's own service of [type] through `services`.
     * The host's allow-list: a type left off it is the host's alone, whatever a module
     * asks.
     */
    fun expose(type: KClass<*>) {
        synchronized(declared) { exposed += type }
    }

    private fun isExposed(type: KClass<*>): Boolean = synchronized(declared) { type in exposed }

    /** The module that declared [type], or null when none did. */
    fun ownerOf(type: KClass<*>): String? = synchronized(declared) { declared[type]?.owner }

    // Settings domain ids to the module that declared each. Kept beside the services
    // because a domain is one of the things a module declares that no other may.
    private val domains = HashMap<String, String>()

    /** The module that declared the settings domain [id], or null when none did. */
    fun ownerOfDomain(id: String): String? = synchronized(declared) { domains[id] }

    /** Records that [owner] declared the settings domain [id]. */
    fun claimDomain(id: String, owner: String) {
        synchronized(declared) {
            require(id !in domains) { "the settings domain '$id' is already declared by '${domains.getValue(id)}'" }
            domains[id] = owner
        }
    }

    private fun declaredFor(type: KClass<*>): Declared? = synchronized(declared) { declared[type] }

    /**
     * The app level's handle: [inner], the host's own services, with the declared ones
     * laid over it. [context] is what the services' coroutines run under. The handle is
     * the caller's to close, which closes the app's services and then [inner].
     */
    fun host(inner: LevelServicesHost, context: CoroutineContext = Dispatchers.Default): LevelServicesHost =
        Shelf(inner, ScopeLevel.App, null, context)

    /** One level's handle: the host's services for it and the declared services made for it. */
    private inner class Shelf(
        private val inner: LevelServicesHost,
        private val level: ScopeLevel,
        private val parent: Shelf?,
        context: CoroutineContext,
    ) : LevelServicesHost {

        private val job = SupervisorJob(context[Job])
        private val scope = CoroutineScope(context.minusKey(Job) + job)
        private val made = LinkedHashMap<KClass<*>, Any>()
        private val lock = Any()
        private var closed = false

        override val hostServices: ContributedLevelServices =
            ContributedLevelServices(inner.hostServices, { contributed(it, hostView = true) }) { true }

        override val services: ContributedLevelServices =
            ContributedLevelServices(inner.hostServices, { contributed(it, hostView = false) }, ::isExposed)

        private fun contextFor(declaration: Declared) = object : ServiceContext {
            private val stores = OwnedStores.of(this@Shelf.hostServices, declaration.owner, this@ContributedServices)
            override val level: ScopeLevel get() = this@Shelf.level
            override val coroutineScope: CoroutineScope get() = scope
            override val services: LevelServices = WithStores(this@Shelf.services, stores)
            override val hostServices: LevelServices =
                if (declaration.trusted) WithStores(this@Shelf.hostServices, stores) else services
        }

        // One gate per service type, held while its maker runs.
        private val gates = HashMap<KClass<*>, Gate>()

        // What a module declared for this level's kind, made here, or else the nearest
        // level above's. One kept for the host is only for [hostView].
        //
        // Exactly one maker runs per level and type: a service may start work as it is
        // made, a sign-in say, and a second one made beside it would do it twice. The
        // maker runs under the type's own gate and not the level's lock, so it may ask
        // for a service of another type. One that asks for its own type fails.
        fun contributed(type: KClass<*>, hostView: Boolean): Any? {
            val declaration = declaredFor(type) ?: return null
            if (declaration.hostOnly && !hostView) return null
            if (declaration.level != level) return parent?.contributed(type, hostView)
            val gate = synchronized(lock) {
                if (closed) return null
                made[type]?.let { return it }
                gates.getOrPut(type) { Gate() }
            }
            synchronized(gate) {
                synchronized(lock) {
                    if (closed) return null
                    made[type]?.let { return it }
                }
                check(!gate.making) { "the maker of ${type.simpleName} asks for its own service" }
                gate.making = true
                val fresh = try {
                    declaration.make(contextFor(declaration))
                } finally {
                    gate.making = false
                }
                val kept = synchronized(lock) {
                    if (closed) null else fresh.also { made[type] = it }
                }
                if (kept == null && fresh is AutoCloseable) fresh.closeLogged("a ${type.simpleName} made as its level closed")
                return kept
            }
        }

        private inner class Gate {
            var making = false
        }

        override fun child(level: ScopeLevel): LevelServicesHost {
            val own = inner.child(level)
            return Shelf(own, level, this, scope.coroutineContext)
        }

        override fun close() {
            val toClose = synchronized(lock) {
                if (closed) return
                closed = true
                made.values.toList().also { made.clear() }
            }
            // Newest first, since a later service may have been made from an earlier one.
            toClose.asReversed().forEach { if (it is AutoCloseable) it.closeLogged("a module service") }
            scope.cancel()
            inner.close()
        }
    }
}

/**
 * A level's services as the host lays modules' services over its own. A type
 * [reachable] lets through is answered by [inner], the host's own, first. Any other
 * type is looked up among the services modules declared and nowhere else.
 */
@HostApi
class ContributedLevelServices internal constructor(
    /** The host's own services for this level. The host reaches them through [HostAccess.ownServicesOf]. */
    internal val inner: LevelServices,
    private val contributed: (KClass<*>) -> Any?,
    private val reachable: (KClass<*>) -> Boolean,
) : LevelServices {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> find(type: KClass<T>): T? =
        (if (reachable(type)) inner.find(type) else null) ?: contributed(type) as T?

    // A miss on the host's side is the host's own miss, which throws what the contract
    // names. A type the host keeps to itself is missing the way the contract says too.
    override fun <T : Any> get(type: KClass<T>): T =
        find(type) ?: if (reachable(type)) inner.get(type) else throw NoSuchElementException("no ${type.qualifiedName} a module may reach")
}
