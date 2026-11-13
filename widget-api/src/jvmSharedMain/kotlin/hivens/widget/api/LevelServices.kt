package hivens.widget.api

import hivens.widget.model.ScopeLevel
import kotlin.reflect.KClass

/**
 * The services one level of the scope tree carries, looked up by type. Read only.
 *
 * What a level shows the factories that make its sources and commands and the owner
 * that draws it, through [LevelContext.services]. The host implements it over
 * whatever keeps its services, so a module compiled against this API sees a service
 * by its type and nothing of how the host made it. Making and disposing them is the
 * host's side, [LevelServicesHost], which no module is handed.
 *
 * The host decides what lives where. A service it declares for a kind of level is
 * one instance per level of that kind and goes with the level, a level below sees
 * the instance of the nearest level above that has one, and the app level has none
 * of its own. A service the host keeps for the whole app is seen from every level.
 *
 * Unlike [WidgetServiceRegistry], which holds what mounted widgets provide and
 * changes as they come and go, this is the host's and is fixed for the level's life.
 */
interface LevelServices {

    /**
     * The service of [type] as this level sees it, or null when the level has none
     * to give. Null does not only mean that nothing here or above declares one: it
     * is also the answer once the level's services are closed, and the host may
     * answer null for other states in which it has nothing it can hand out here. A
     * reader that has to tell these apart uses [get]. A service that is declared
     * but fails while the host makes it is not null: that failure comes through as
     * the host's own exception, the same as from [get].
     */
    fun <T : Any> find(type: KClass<T>): T?

    /**
     * The service of [type] as this level sees it.
     *
     * Throws [NoSuchElementException] when nothing here or above provides it,
     * whatever the host keeps its services in, so a module catches that one type.
     * Any other failure comes through as the host's own exception: the level's
     * services being closed, or the service failing while the host makes it, which
     * is also where a missing service it is made from shows up.
     */
    fun <T : Any> get(type: KClass<T>): T
}

/**
 * The host's side of a level's services: makes them for the levels below and
 * disposes them with the level.
 *
 * Handed to [WidgetScope.app] for the root of a tree. The level keeps it to itself
 * and shows the factories and owners below it only the read only [services], so
 * nothing outside the host can close a level's services while the level lives or
 * make a child it would never close.
 *
 * [WidgetScope.child] asks the parent's handle for the child's with [child] and
 * closes it with the child level. The handle given to [WidgetScope.app] belongs to
 * whoever gave it, and the app level never closes it.
 */
interface LevelServicesHost : AutoCloseable {

    /** What the level this handle serves shows of its services. */
    val services: LevelServices

    /** The handle for a level of kind [level] made inside the one this handle serves. That level closes it. */
    fun child(level: ScopeLevel): LevelServicesHost

    /** Disposes what this handle made for its level. Called by the level as it closes. */
    override fun close()
}

/** The service of type [T] as this level sees it, or null. See [LevelServices.find] for what null can mean. */
inline fun <reified T : Any> LevelServices.find(): T? = find(T::class)

/** The service of type [T] as this level sees it. See [LevelServices.get] for what it throws. */
inline fun <reified T : Any> LevelServices.get(): T = get(T::class)
