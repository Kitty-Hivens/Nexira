package hivens.look.manager

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookAxis
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookPack
import hivens.look.manager.api.LookProvider
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/** What a test axis answers: one name, enough to tell which provider answered. */
data class Shade(val name: String)

/** An axis a test brings, with a local of its own so two axes are told apart in a composition. */
class TestAxis(id: String, contract: ApiVersion = ApiVersion(1, 0)) : LookAxis<Shade>(id, contract, Shade::class, Shade("base")) {
    override val label: String = id
}

/**
 * A provider a test brings, answering [axis] in [family] with `axis/family`, that counts how often it is
 * opened and closed. [failing] is thrown from [open] where set.
 */
class TestProvider(
    override val axis: String,
    override val family: String,
    private val failing: Throwable? = null,
    override val contract: ApiVersion = ApiVersion(1, 0),
) : LookProvider<Shade> {
    override val label: String = id
    val answers = MutableStateFlow(Shade("$axis/$family"))
    var opened = 0
        private set
    var closed = 0
        private set

    /** Whether it is open now. */
    val isOpen: Boolean get() = opened > closed

    /** What it was handed when it was last opened. */
    var context: LookContext? = null
        private set

    override fun open(context: LookContext): LookAnswers<Shade> {
        opened++
        this.context = context
        failing?.let { throw it }
        return object : LookAnswers<Shade> {
            override val answers: StateFlow<Shade> = this@TestProvider.answers
            override fun close() {
                closed++
            }
        }
    }
}

/** What a test hands a provider of the module [moduleId] as that module's services: no service at all, and which module they are for. */
class ModuleServices(val moduleId: String) : LevelServices {
    override fun <T : Any> find(type: KClass<T>): T? = null

    override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException("no ${type.qualifiedName} for $moduleId")
}

/** A pack a test brings. */
class TestPack(override val id: String, override val providers: Map<String, String>) : LookPack {
    override val label: String = id
}

/** [items] as one module [module] brings them. */
fun taken(vararg items: ModuleContribution, module: String = "test.look"): List<TakenContribution<ModuleContribution>> =
    items.map { TakenContribution(module, it) }

/** Whether [condition] holds within [millis], checked every few milliseconds. */
fun waitUntil(millis: Long = 5000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(5)
    }
    return condition()
}

/** A scope whose work runs at once, on the thread that starts it. */
fun eagerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

/** [pack] and [overrides] as a choice. */
fun choice(pack: String? = null, overrides: Map<String, String> = emptyMap()): LookChoice = LookChoice.build {
    this.pack = pack
    overrides.forEach { (axis, family) -> override(axis, family) }
}

/**
 * A manager over [items] with [choice], both of which the test can change through what it returns. Its
 * passes run on the thread that asks for them, so a test reads their result as soon as it asked. Every
 * module bringing [items] counts as compiled in unless [compiled] says otherwise, and a provider is handed
 * what [servicesOf] answers for its module.
 */
internal class Managed(
    choice: LookChoice,
    items: List<TakenContribution<ModuleContribution>>,
    compiled: Set<String> = items.mapTo(HashSet()) { it.moduleId },
    servicesOf: (moduleId: String) -> LevelServices = ::ModuleServices,
) {
    val choice = MutableStateFlow(choice)
    val taken = MutableStateFlow(items)
    val scope = eagerScope()
    val manager = LookManager(this.choice, taken, servicesOf, scope, compiled, worker = Dispatchers.Unconfined, providerDispatcher = Dispatchers.Unconfined)

    /** Each axis's answers now, by axis id. */
    fun answers(): Map<String, String> = manager.answers.value.associate { it.axis.id to (it.value as Shade).name }
}

/** Composes [content] once, with nothing drawn, and disposes it, which forgets everything it remembered. */
fun composeOnce(content: @Composable () -> Unit) {
    val recomposer = Recomposer(Dispatchers.Unconfined)
    val composition = Composition(NothingApplier, recomposer)
    try {
        composition.setContent(content)
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
}

internal object NothingApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
