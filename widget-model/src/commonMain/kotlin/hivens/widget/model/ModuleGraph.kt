package hivens.widget.model

/**
 * One module as [ModuleGraph] reads it, whatever it is made of: what others can name it by, what it
 * cannot run without and what it only uses where it is there.
 */
class ModuleNode(
    val id: String,
    /** Every name another module can ask for it by, at the version it is: its APIs, and its own id where it states a version. */
    val offers: Map<String, ApiVersion> = emptyMap(),
    /** The names it does not run without, at the version it was built against. */
    val requires: Map<String, ApiVersion> = emptyMap(),
    /** The names it makes use of where they are there, at the version it was built against, and runs without. */
    val uses: Map<String, ApiVersion> = emptyMap(),
    /** Whether a person switched it off. It is there to be named as the reason another waits, and never runs. */
    val off: Boolean = false,
)

/** One reason a module waits instead of running. */
sealed interface ModuleWait {
    /** Nothing offers [name]. */
    data class Missing(val name: String, val needed: ApiVersion) : ModuleWait

    /** [module] offers [name] at [here], which does not serve a module built against [needed]. */
    data class OtherVersion(val name: String, val needed: ApiVersion, val here: ApiVersion, val module: String) : ModuleWait

    /** [module], which offers [name], is switched off. */
    data class Off(val name: String, val module: String) : ModuleWait

    /** [module], which offers [name], does not run itself. */
    data class Stopped(val name: String, val module: String) : ModuleWait

    /** It requires itself through [modules], each requiring the next and the last the first. */
    data class Cycle(val modules: List<String>) : ModuleWait
}

/**
 * The modules there are and what they require of each other, resolved: which run and in what order, and
 * why each of the others waits.
 *
 * A module runs where everything it requires is offered by a module that runs, in a version that serves
 * the one it was built against. Any other waits, for every reason there is, and so does every module of a
 * circle. Of two modules offering one name the first is the one that offers it. What a module only uses
 * never makes it wait: it comes after what it uses where the order allows, and in its place where not.
 */
class ModuleGraph private constructor(
    /** The modules that run, each after everything it requires, otherwise in the order given. */
    val order: List<String>,
    /** The modules that wait, with every reason. */
    val waiting: Map<String, List<ModuleWait>>,
    private val providers: Map<String, ModuleNode>,
    private val edges: Map<String, Set<String>>,
) {
    /** The id of the module that offers [name], running or not, or null where none does. */
    fun providerOf(name: String): String? = providers[name]?.id

    /** The version [name] is offered at, or null where nothing offers it. */
    fun versionOf(name: String): ApiVersion? = providers[name]?.offers?.get(name)

    /** Whether [name] is offered by a module that runs, in a version that serves one built against [builtAgainst]. */
    fun serves(name: String, builtAgainst: ApiVersion): Boolean {
        val provider = providers[name] ?: return false
        return provider.id in running && provider.offers.getValue(name).serves(builtAgainst)
    }

    private val running: Set<String> = order.toSet()

    /** The modules [ids] require, and those require, and so on, in the order they run. Without [ids] themselves. */
    fun requiredBy(ids: Collection<String>): List<String> = reach(ids, edges).let { found -> order.filter { it in found && it !in ids } }

    /** The modules that require [id], and that require those, and so on, in the order they run: what stops with it. */
    fun dependantsOf(id: String): List<String> {
        val back = HashMap<String, MutableSet<String>>()
        edges.forEach { (from, to) -> to.forEach { back.getOrPut(it) { HashSet() } += from } }
        val found = reach(listOf(id), back)
        return order.filter { it in found && it != id }
    }

    private fun reach(from: Collection<String>, along: Map<String, Set<String>>): Set<String> {
        val found = LinkedHashSet<String>()
        val queue = ArrayDeque(from)
        while (queue.isNotEmpty()) along[queue.removeFirst()].orEmpty().forEach { if (found.add(it)) queue += it }
        return found
    }

    companion object {
        /** No modules. */
        val EMPTY: ModuleGraph = of(emptyList())

        /** [nodes] resolved. Of two nodes with one id the first is read. */
        fun of(nodes: List<ModuleNode>): ModuleGraph {
            val byId = LinkedHashMap<String, ModuleNode>()
            nodes.forEach { if (it.id !in byId) byId[it.id] = it }
            val providers = HashMap<String, ModuleNode>()
            byId.values.forEach { node -> node.offers.keys.forEach { name -> if (name !in providers) providers[name] = node } }

            val waiting = LinkedHashMap<String, MutableList<ModuleWait>>()
            fun wait(id: String, why: ModuleWait) {
                waiting.getOrPut(id) { ArrayList() } += why
            }
            // What each module requires, by the module that offers it, whether or not that one runs.
            val edges = LinkedHashMap<String, MutableSet<String>>()
            val on = byId.values.filter { !it.off }
            on.forEach { node ->
                node.requires.forEach { (name, needed) ->
                    val provider = providers[name]
                    when {
                        provider == null -> wait(node.id, ModuleWait.Missing(name, needed))
                        !provider.offers.getValue(name).serves(needed) ->
                            wait(node.id, ModuleWait.OtherVersion(name, needed, provider.offers.getValue(name), provider.id))
                        provider.off -> wait(node.id, ModuleWait.Off(name, provider.id))
                        provider.id != node.id -> edges.getOrPut(node.id) { LinkedHashSet() } += provider.id
                    }
                }
            }
            circlesIn(on.map { it.id }, edges).forEach { circle -> circle.forEach { wait(it, ModuleWait.Cycle(circle)) } }
            // A module of a circle has its reason already, and what requires one that waits waits too.
            do {
                val before = waiting.size
                on.filter { it.id !in waiting }.forEach { node ->
                    node.requires.keys.forEach { name ->
                        val provider = providers.getValue(name)
                        if (provider.id != node.id && provider.id in waiting) wait(node.id, ModuleWait.Stopped(name, provider.id))
                    }
                }
            } while (waiting.size > before)

            val running = on.filter { it.id !in waiting }
            return ModuleGraph(ordered(running, edges, providers), waiting, providers, edges)
        }

        private fun ordered(running: List<ModuleNode>, edges: Map<String, Set<String>>, providers: Map<String, ModuleNode>): List<String> {
            val ids = running.mapTo(HashSet()) { it.id }
            val soft = running.associate { node ->
                node.id to node.uses.mapNotNull { (name, needed) ->
                    providers[name]?.takeIf { it.id != node.id && it.id in ids && it.offers.getValue(name).serves(needed) }?.id
                }
            }
            val order = LinkedHashSet<String>()
            val left = running.toMutableList()
            while (left.isNotEmpty()) {
                val ready = left.filter { node -> edges[node.id].orEmpty().all { it in order } }
                // There is always one: what is left has no circle of requirements.
                val next = ready.firstOrNull { node -> soft.getValue(node.id).all { it in order } } ?: ready.first()
                order += next.id
                left -= next
            }
            return order.toList()
        }

        /** The circles among [ids], each once, starting at its first member and following what it requires. */
        private fun circlesIn(ids: List<String>, edges: Map<String, Set<String>>): List<List<String>> {
            fun from(start: String): List<String>? {
                val cameFrom = HashMap<String, String>()
                val queue = ArrayDeque(listOf(start))
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    for (next in edges[at].orEmpty()) {
                        if (next == start) {
                            val path = ArrayDeque(listOf(at))
                            while (path.first() != start) path.addFirst(cameFrom.getValue(path.first()))
                            return path.toList()
                        }
                        if (next !in cameFrom) {
                            cameFrom[next] = at
                            queue += next
                        }
                    }
                }
                return null
            }
            val found = ArrayList<List<String>>()
            val seen = HashSet<String>()
            ids.forEach { id ->
                if (id in seen) return@forEach
                from(id)?.let { circle ->
                    found += circle
                    seen += circle
                }
            }
            return found
        }
    }
}
