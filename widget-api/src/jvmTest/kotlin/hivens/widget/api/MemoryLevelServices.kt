package hivens.widget.api

import hivens.widget.model.ScopeLevel
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * A plain host with no container behind it, for testing the tree on its own.
 *
 * A level of a kind listed in [perLevel] makes those services for itself when it is
 * made, and every level answers from its own first and then from the levels above.
 * It remembers the kind it was made for, the handles made under it and whether it
 * was closed, which is what the tests ask of it. Each close is written to [events]
 * as "services <level>" and then handed to [onClose], and a handle of a kind in
 * [failClose] throws from its close after that.
 */
internal class MemoryLevelServices private constructor(
    val level: ScopeLevel,
    private val parent: MemoryLevelServices?,
    private val perLevel: Map<ScopeLevel, List<() -> Any>>,
    val events: MutableList<String>,
    private val failClose: Set<ScopeLevel>,
    private val onClose: (MemoryLevelServices) -> Unit,
) : LevelServicesHost {

    private val own: List<Any> = perLevel[level].orEmpty().map { it() }

    val children = ArrayList<MemoryLevelServices>()

    var closed = false
        private set

    override val services: LevelServices = View(this)

    override fun child(level: ScopeLevel): LevelServicesHost =
        MemoryLevelServices(level, this, perLevel, events, failClose, onClose).also { children += it }

    override fun close() {
        closed = true
        events += "services $level"
        onClose(this)
        if (level in failClose) error("the $level services cannot close")
    }

    private fun <T : Any> lookup(type: KClass<T>): T? = own.firstNotNullOfOrNull { type.safeCast(it) } ?: parent?.lookup(type)

    /** The read only side, which knows its host only so the tests can reach it. */
    class View(val host: MemoryLevelServices) : LevelServices {
        override fun <T : Any> find(type: KClass<T>): T? = host.lookup(type)

        override fun <T : Any> get(type: KClass<T>): T =
            host.lookup(type) ?: throw NoSuchElementException("no ${type.qualifiedName} at this ${host.level} level")
    }

    companion object {
        fun app(
            perLevel: Map<ScopeLevel, List<() -> Any>> = emptyMap(),
            events: MutableList<String> = ArrayList(),
            failClose: Set<ScopeLevel> = emptySet(),
            onClose: (MemoryLevelServices) -> Unit = {},
        ) = MemoryLevelServices(ScopeLevel.App, null, perLevel, events, failClose, onClose)
    }
}

/** The tests' own host behind this level. */
internal val WidgetScope.memory: MemoryLevelServices get() = (services as MemoryLevelServices.View).host
