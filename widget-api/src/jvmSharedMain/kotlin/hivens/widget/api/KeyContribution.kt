package hivens.widget.api

import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

/**
 * What a module answers: the source and command keys it brings, the level each lives
 * at, the factories that make them, and the services those factories are built on.
 *
 * The host asks every key contribution of every module the document names to
 * [declare] its keys once, before anything is drawn, and registers them beside its
 * own. A module the document does not name brings none, so its keys are absent and a
 * widget that needs one stands misplaced. The keys themselves and the value types they
 * carry live in the module's own API artifact, which other modules compile against.
 *
 * Carried like any other contribution: a jar lists it as a service under this
 * interface's name, and a module compiled into the application hands it over in its
 * [CompiledModule.contributions].
 */
interface KeyContribution : ModuleContribution {
    /** Declares this module's keys and services into [keys]. Called once, and nothing is registered when it throws. */
    fun declare(keys: ModuleKeys)
}

/**
 * Where a [KeyContribution] declares what it answers. Nothing is registered until
 * [KeyContribution.declare] returns, and then all of it is, or none of it when one id
 * is already taken.
 */
interface ModuleKeys {
    /**
     * A source of [key] that each level of [key]'s kind makes with [factory] the first
     * time it is read there and disposes with the level. [sample] is display data for
     * the palette and the editor, never handed to a widget drawn for use.
     */
    fun <T> source(key: SourceKey<T>, sample: (() -> T)? = null, factory: SourceFactory<T>)

    /** A command of [key] that each level of [key]'s kind makes with [factory] the first time it is fired or asked for there. */
    fun <P> command(key: CommandKey<P>, factory: CommandFactory<P>)

    /**
     * A source of [key] that the owner of each level of its kind provides, a widget of
     * this module with [ProvideScoped] for instance. Declared so the editor knows where
     * it lives. Not for the app level, whose sources are made by a factory.
     */
    fun <T> owned(key: SourceKey<T>, sample: (() -> T)? = null)

    /** A command of [key] that the owner of each level of its kind provides. Not for the app level. */
    fun owned(key: CommandKey<*>)

    /**
     * The module's own settings, [domain], kept by the host in a file of its own: read through
     * [source] and changed through [update], both app keys of the module's own API. Every other
     * part of the module reaches the same value through the [SettingsStores] service.
     *
     * A domain id another module already declared refuses this module's keys, all of them, like
     * a key id already taken does.
     */
    fun <T : Any> settings(domain: SettingsDomain<T>, source: SourceKey<T>, update: CommandKey<SettingsChange<T>>)

    /**
     * A service of [type] made once per level of kind [level] by [make], the first time
     * something at or below such a level asks for it, and closed with that level when
     * it is [AutoCloseable]. At [ScopeLevel.App] it is one for the whole app. Factories
     * read it through [LevelContext.services].
     */
    fun <T : Any> service(level: ScopeLevel, type: KClass<T>, make: (ServiceContext) -> T)

    /**
     * A service of [type] like [service] makes, which only the host and the modules it
     * trusts reach: through the host view a level hands their factories
     * ([HostLevelContext.hostServices]) and their service makers
     * ([ServiceContext.hostServices]), and through the [HostAccess] the host keeps.
     * Every other module and every widget is answered as if nothing declared it, since
     * the API hands them no host view at all. For what carries secrets the rest must
     * never hold, a signed-in session with its tokens say.
     *
     * That is the API's promise and no more: in-process code can always reach past it
     * by reflection, and isolating modules is not this API's business.
     *
     * Only a module the host trusts may declare one. A module that is not trusted and
     * declares one registers nothing at all.
     */
    @HostApi
    fun <T : Any> hostService(level: ScopeLevel, type: KClass<T>, make: (ServiceContext) -> T)
}

/** Declares a service of type [T]. See [ModuleKeys.service]. */
inline fun <reified T : Any> ModuleKeys.service(level: ScopeLevel, noinline make: (ServiceContext) -> T) =
    service(level, T::class, make)

/** Declares a host service of type [T]. See [ModuleKeys.hostService]. */
@HostApi
inline fun <reified T : Any> ModuleKeys.hostService(level: ScopeLevel, noinline make: (ServiceContext) -> T) =
    hostService(level, T::class, make)

/** What a level hands a module's service while making it. */
interface ServiceContext {
    /** The kind of level the service is made for. */
    val level: ScopeLevel

    /** Cancelled when that level's services close, so the work the service started stops with it. */
    val coroutineScope: CoroutineScope

    /**
     * The other services as a module may see them at that level: what modules declared
     * and the host types the host exposed to every module.
     */
    val services: LevelServices

    /**
     * The host's own services as well, for a module the host trusts with them, the ones
     * compiled into it. For any other module the same as [services].
     */
    @HostApi
    val hostServices: LevelServices get() = services
}

/**
 * A module compiled into the application rather than loaded from a jar. The host is
 * handed the ones its build carries and brings each in only when the document names
 * its [id]. The host trusts such a module with its own services (see
 * [ServiceContext.hostServices]), which it trusts no jar with.
 */
interface CompiledModule {
    /** Stable across versions, since the document names the module by it. */
    val id: String

    /** What a person is shown for it: a key into [strings], shown as written where they have no entry. */
    val name: String

    /** The module's words, per language, for what it shows a person, its [name] among them. */
    val strings: ModuleStrings get() = ModuleStrings.EMPTY

    /** The APIs it publishes for other modules to compile against, by API id. */
    val provides: Map<String, ApiVersion> get() = emptyMap()

    /**
     * The packages each API in [provides] lives in, by the same API id: what of the
     * application a jar that names the API is shown. An API in [provides] with no
     * packages here is one no jar could use, and the loader refuses a jar that names it.
     */
    val apiPackages: Map<String, Set<String>> get() = emptyMap()

    /**
     * The kinds of contribution this module takes from the jars, each an interface in one of
     * its APIs, the themes module's theme say. The host has the loader read them beside the
     * kinds the kernel declares ([ModuleContribution.kinds]) and hands what it finds to the
     * module. A jar that brings one names the API in its manifest, without which it cannot
     * see the interface.
     */
    val contributionKinds: List<Class<out ModuleContribution>> get() = emptyList()

    /**
     * What it adds. The host reads its [KeyContribution]s, and of a compiled module
     * nothing else yet: effects, themes and particle fields come from jars only.
     */
    val contributions: List<ModuleContribution>
}
