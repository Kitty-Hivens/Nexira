package hivens.ui.widgets

import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.WidgetScope
import hivens.widget.model.ScopeLevel
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.ScopeDSL
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * A level's services as the launcher keeps them, read only: the level's Koin scope.
 *
 * What [LevelServices.find] and [LevelServices.get] answer is what the scope
 * resolves, its own declarations first and then, through the links, the levels
 * above and the root. Koin's own failure for a missing definition is turned into
 * the [NoSuchElementException] the contract names, so a module catches the same
 * thing whatever the host.
 *
 * The launcher reaches [scope] where it has to, to draw a level so that whatever is
 * injected below resolves from it (see [LevelHost]). Making and closing scopes is
 * [KoinLevelServicesHost]'s.
 */
class KoinLevelServices internal constructor(val scope: Scope) : LevelServices {

    // getOrNull answers null for a missing definition, a closed scope and a missing
    // scope value. A definition that fails while it is made, a missing dependency of
    // its own included, reaches it wrapped in InstanceCreationException and is thrown.
    override fun <T : Any> find(type: KClass<T>): T? = scope.getOrNull(type)

    override fun <T : Any> get(type: KClass<T>): T =
        try {
            scope.get(type)
        } catch (e: NoDefinitionFoundException) {
            throw NoSuchElementException("no ${type.qualifiedName} at this level: ${e.message}").apply { initCause(e) }
        }
}

/**
 * The launcher's side of a level's services: a Koin scope per level, linked to the
 * scope of the level above it.
 *
 * A level below the app gets a scope of its own under the qualifier of its kind, so
 * what [levelServices] declared for that kind is one instance per level, and what it
 * did not declare resolves through the link from the levels above and finally from
 * the root. The app level borrows Koin's root scope and declares nothing of its own.
 * A level's scope is closed with the level, and the root is never closed here, since
 * it lives as long as Koin does.
 *
 * Koin stays the host's business: widget-api sees only [LevelServicesHost], and the
 * factories and owners below a level see only its read only [services].
 */
class KoinLevelServicesHost private constructor(scope: Scope) : LevelServicesHost {

    override val services: KoinLevelServices = KoinLevelServices(scope)

    private val scope: Scope get() = services.scope

    override fun child(level: ScopeLevel): LevelServicesHost {
        val own = scope.getKoin().createScope(nextScopeId(level), level.servicesQualifier)
        // Koin links every new scope to its root already, so only a parent below the app needs it.
        if (!scope.isRoot) own.linkTo(scope)
        return KoinLevelServicesHost(own)
    }

    override fun close() {
        if (!scope.isRoot) scope.close()
    }

    companion object {
        private val ids = AtomicLong()

        private fun nextScopeId(level: ScopeLevel): String = "nexira.${level.name.lowercase()}.${ids.incrementAndGet()}"

        /** The app level's handle on Koin's [root] scope, for [WidgetScope.app]. */
        fun app(root: Scope): KoinLevelServicesHost {
            require(root.isRoot) { "the app level's services are Koin's root scope" }
            return KoinLevelServicesHost(root)
        }
    }
}

/**
 * The Koin scope behind this level. Every level the launcher makes carries one,
 * because its tree starts at [KoinLevelServicesHost.app], so a level that does not
 * is a tree made somewhere else and is refused.
 */
val WidgetScope.koinScope: Scope
    get() = (services as? KoinLevelServices)?.scope
        ?: error("a $level level whose services are not Koin's cannot be drawn by the launcher")

/** The Koin scope qualifier every level of this kind creates its own scope under. */
val ScopeLevel.servicesQualifier: Qualifier get() = LEVEL_QUALIFIERS.getValue(this)

private val LEVEL_QUALIFIERS: Map<ScopeLevel, Qualifier> =
    ScopeLevel.entries.associateWith { named("nexira.level.${it.name}") }

/**
 * Declares services made once per level of kind [level], each closed with the
 * level that made it. Everything injected under such a level resolves from it.
 */
fun Module.levelServices(level: ScopeLevel, declarations: ScopeDSL.() -> Unit) {
    require(level != ScopeLevel.App) { "app-wide services are plain singles" }
    scope(level.servicesQualifier, declarations)
}
