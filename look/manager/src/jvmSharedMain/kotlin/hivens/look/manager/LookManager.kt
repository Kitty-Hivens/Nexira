package hivens.look.manager

import androidx.compose.runtime.RememberObserver
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookAxisState
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookProvision
import hivens.look.manager.api.LookSnapshot
import hivens.look.manager.api.LookSource
import hivens.look.manager.api.LookState
import hivens.look.manager.api.LookView
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Picks one provider per axis from the person's [choice], opens only those, and answers the look as
 * they answer it: [provision] for the composition, [view] for code outside it, and [state] for whoever
 * shows or changes the choice.
 *
 * The axes, providers and packs are what [taken] holds, as it changes: an axis a module brings is
 * answered as soon as it arrives, and one whose module leaves is gone with it. Each axis is answered by
 * the first provider that is there and opens of the family the choice names for it, in the order
 * [LookRegistry.candidates] tries them, and by its base answers where none does. Two providers never
 * answer one axis, and a provider nothing needs is closed, so switching the choice closes the provider
 * it switched away from. Only modules in [compiled] may bring the default pack.
 *
 * A provider is opened with what its own module may use: the services [servicesOf] answers for that
 * module, and before the boot the boot context [bootOf] answers for it. One whose module's services
 * cannot be had fails to open like any other.
 *
 * Providers are opened, read and closed by one worker on [worker], one pass after another, and never
 * under the lock, so no provider runs on the thread that changed the choice or drew a subtree, and a
 * slow one holds up only the look. Until a pass is done the answers before it stand, base answers at
 * first. Their own work runs on [providerDispatcher].
 *
 * A provider whose opening or answers fail, in any way short of the virtual machine failing, is passed
 * over for as long as the same contribution is brought, and the axis goes on down the choice. A
 * cancellation a provider throws is such a failure too. Only the cancellation of [scope] ends the work.
 *
 * A provider may stand on another of its axis ([LookProvider.basedOn]): that one is opened before it and
 * closed after it, whether it answers anything itself or not, and the one on top is opened again where what
 * it stands on is another now.
 *
 * A subtree may ask for another look ([lease]): every axis is answered there as the person's choice with
 * the subtree's laid over it resolves, by providers opened while the subtree is drawn.
 */
internal class LookManager(
    choice: StateFlow<LookChoice>,
    taken: StateFlow<List<TakenContribution<ModuleContribution>>>,
    private val servicesOf: (moduleId: String) -> LevelServices,
    private val scope: CoroutineScope,
    private val compiled: Set<String>,
    worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val providerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val bootOf: (moduleId: String) -> BootContext? = { null },
) : AutoCloseable {
    private val lock = Any()
    private val alive: () -> Boolean = { scope.isActive }

    // Everything below is read and written under the lock. The worker alone opens and closes providers.
    private var registry = LookRegistry.of(taken.value, compiled, alive)
    private var seenTaken = taken.value
    private var chosen = choice.value
    private val leases = LinkedHashSet<Lease>()
    private var open = IdentityHashMap<LookProvider<Any>, Opened>()
    private val failed: MutableSet<LookProvider<Any>> = Collections.newSetFromMap(IdentityHashMap())
    private var watched: Set<Opened> = emptySet()
    private var watch: Job? = null
    private var closed = false

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(LookState.EMPTY)

    /** Every axis as the choice resolves now, and what is on offer. */
    val state: StateFlow<LookState> = _state.asStateFlow()

    private val _answers = MutableStateFlow(registry.axes.map { AxisAnswer(it, it.base) })

    /** Each axis's answers now. */
    val answers: StateFlow<List<AxisAnswer>> = _answers.asStateFlow()

    private val _snapshot = MutableStateFlow(snapshotOf(_answers.value))

    /** The value of `look.provide`, the same for the manager's whole life. */
    val provision: StateFlow<LookProvision> = MutableStateFlow(LiveProvision(this)).asStateFlow()

    /** The look for code outside composition: the same answers [provision] provides, and [state], published by the same pass. */
    val view: LookView = object : LookView {
        override val answers: StateFlow<LookSnapshot> = _snapshot.asStateFlow()
        override val state: StateFlow<LookState> = this@LookManager.state
    }

    init {
        scope.launch(worker) {
            for (signal in wake) {
                try {
                    pass()
                } catch (e: Throwable) {
                    if (e is CancellationException && !alive()) throw e
                    if (e is VirtualMachineError && e !is StackOverflowError) throw e
                    log.error("A pass of the look manager failed, and the look stays as it was", e)
                }
            }
        }
        scope.launch(worker) {
            choice.collect { now ->
                synchronized(lock) { chosen = now }
                wake.trySend(Unit)
            }
        }
        scope.launch(worker) {
            taken.collect { now ->
                val fresh = synchronized(lock) { now !== seenTaken }
                if (fresh) {
                    val next = LookRegistry.of(now, compiled, alive)
                    synchronized(lock) {
                        seenTaken = now
                        registry = next
                    }
                }
                wake.trySend(Unit)
            }
        }
    }

    /**
     * The look as it stands, for a window drawn before the manager is up, with no manager behind it: the
     * state, the provision and the snapshot of one pass, read together.
     */
    fun detached(): Detached = synchronized(lock) { Detached(_state.value, FixedProvision(_answers.value), _snapshot.value) }

    /** What [detached] reads, all of one pass. */
    class Detached(val state: LookState, val provision: LookProvision, val snapshot: LookSnapshot)

    /**
     * A subtree's look under [choice], open until it is closed, or forgotten or abandoned by the
     * composition that remembered it. Returns at once, and its answers come once a pass has opened them.
     */
    fun lease(choice: LookChoice): Lease {
        val lease = Lease(choice)
        val added = synchronized(lock) { !closed && leases.add(lease) }
        if (added) wake.trySend(Unit)
        return lease
    }

    /** Closes every provider it opened. It answers nothing new after. */
    override fun close() {
        val all = synchronized(lock) {
            closed = true
            watch?.cancel()
            leases.clear()
            open.values.toList().also { open = IdentityHashMap() }
        }
        wake.close()
        closeAll(all)
    }

    private fun release(lease: Lease) {
        val removed = synchronized(lock) { leases.remove(lease) }
        if (removed) wake.trySend(Unit)
    }

    /** What a pass starts from, taken under the lock. */
    private class Input(
        val registry: LookRegistry,
        val chosen: LookChoice,
        val leases: List<Lease>,
        val open: Map<LookProvider<Any>, Opened>,
        val failed: Set<LookProvider<Any>>,
    )

    // Resolves the look and every subtree's anew from what stands, opens and reads the providers that
    // needs outside the lock, then puts the result in place, closes what nothing needs any more and
    // publishes. A provider that fails while it is opened or read is passed over and the pass resolves
    // again without it. Should the pass not finish, what it opened is closed.
    private fun pass() {
        val input = synchronized(lock) {
            if (closed) return
            Input(registry, chosen, leases.toList(), IdentityHashMap(open), Collections.newSetFromMap<LookProvider<Any>>(IdentityHashMap()).also { it += failed })
        }
        val opening = IdentityHashMap<LookProvider<Any>, Opened>()
        // Opened in this pass over a provider that then failed, so opened again over what is there instead.
        val superseded = ArrayList<Opened>()
        val failedNow: MutableSet<LookProvider<Any>> = Collections.newSetFromMap(IdentityHashMap())
        var installed = false
        try {
            fun fail(entry: ProviderEntry) {
                failedNow += entry.provider
            }
            fun obtain(entry: ProviderEntry, axis: AxisEntry): Opened? {
                val provider = entry.provider
                if (provider in input.failed || provider in failedNow) return null
                val on = entry.basedOn?.let { input.registry.provider(entry.axis, it) }?.let { below ->
                    obtain(below, axis)?.also { if (it.current(axis) == null) fail(below) }?.takeIf { below.provider !in failedNow }
                }
                input.open[provider]?.takeIf { it.on === on }?.let { return it }
                opening[provider]?.let { if (it.on === on) return it else superseded += it }
                return openOf(entry, axis, on)?.also { opening[provider] = it } ?: null.also { opening.remove(provider); failedNow += provider }
            }
            var top: List<Resolution>
            var nested: Map<Lease, List<Resolution>>
            do {
                val before = failedNow.size
                top = input.registry.axes.map { axis -> resolve(axis, input.registry, input.chosen, ::obtain, ::fail) }
                nested = input.leases.associateWith { lease ->
                    val merged = input.chosen.overlaidWith(lease.choice)
                    input.registry.axes.map { axis -> resolve(axis, input.registry, merged, ::obtain, ::fail) }
                }
            } while (failedNow.size > before)
            val unused = ArrayList<Opened>()
            val rewatching = synchronized(lock) {
                if (closed) return
                failed += failedNow
                failed.retainAll(input.registry.providers.mapTo(Collections.newSetFromMap(IdentityHashMap())) { it.provider })
                // What answers something, and what that stands on.
                val used: MutableSet<Opened> = Collections.newSetFromMap(IdentityHashMap())
                (top + nested.values.flatten()).forEach { resolution -> generateSequence(resolution.opened) { it.on }.forEach { used += it } }
                val next = IdentityHashMap<LookProvider<Any>, Opened>()
                (input.open.values + opening.values + superseded).forEach { opened -> if (opened in used) next[opened.entry.provider] = opened else unused += opened }
                open = next
                val answering: Set<Opened> = (top + nested.values.flatten()).mapNotNullTo(Collections.newSetFromMap(IdentityHashMap())) { it.opened }
                next.values.forEach { it.answering = it in answering }
                installed = true
                val answered = top.map { AxisAnswer(it.axis, it.value) }
                _answers.value = answered
                _snapshot.value = snapshotOf(answered)
                _state.value = LookState(
                    choice    = input.chosen,
                    axes      = top.map { LookAxisState(it.axis.id, it.axis.contract, it.opened?.entry?.family, it.source) },
                    providers = input.registry.providers.map(ProviderEntry::view),
                    packs     = input.registry.packs,
                )
                nested.forEach { (lease, list) -> if (lease in leases) lease.publish(list.map { AxisAnswer(it.axis, it.value) }) }
                val changed = used != watched
                watched = used
                changed
            }
            closeAll(unused)
            if (rewatching) rewatch()
        } finally {
            if (!installed) closeAll(opening.values + superseded)
        }
    }

    /** Closes [opened], each before what it stands on. */
    private fun closeAll(opened: Collection<Opened>) = opened.sortedByDescending { it.depth }.forEach(Opened::close)

    /** [answers] as one value, by axis id. */
    private fun snapshotOf(answers: List<AxisAnswer>): LookSnapshot = LookSnapshot(answers.associate { it.axis.id to it.value })

    /** An axis, the provider answering it, null for its base answers, its answers, and where in the choice that came from. */
    private class Resolution(val axis: AxisEntry, val opened: Opened?, val value: Any, val source: LookSource)

    // The first candidate that opens and answers in the axis's type, or the base answers. One that opens
    // and answers in another type is [fail]ed.
    private fun resolve(
        axis: AxisEntry,
        registry: LookRegistry,
        choice: LookChoice,
        obtain: (ProviderEntry, AxisEntry) -> Opened?,
        fail: (ProviderEntry) -> Unit,
    ): Resolution {
        registry.candidates(axis.id, choice).forEach { candidate ->
            val opened = obtain(candidate.provider, axis) ?: return@forEach
            val value = opened.current(axis) ?: return@forEach fail(candidate.provider)
            return Resolution(axis, opened, value, candidate.source)
        }
        return Resolution(axis, null, axis.base, LookSource.Base)
    }

    // The provider opened over [on], or null where opening it failed. Whether it answers in its axis's type
    // is [resolve]'s to find, which reads the answers.
    private fun openOf(entry: ProviderEntry, axis: AxisEntry, on: Opened?): Opened? {
        if (entry.basedOn != null && on == null) {
            log.info("Look provider '{}' of module '{}' stands on '{}', which is not there for axis '{}', and stands on its base answers", entry.family, entry.moduleId, entry.basedOn, entry.axis)
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        val answers = guarded(alive) {
            val context = object : LookContext {
                override val coroutineScope = CoroutineScope(scope.coroutineContext + providerDispatcher + job)
                override val services = servicesOf(entry.moduleId)
                override val boot = bootOf(entry.moduleId)
                override val under: StateFlow<Any>? = if (entry.basedOn == null) null else on?.answers?.answers ?: MutableStateFlow(axis.base)
            }
            entry.provider.open(context)
        }.getOrElse {
            job.cancel()
            log.warn("Look provider '{}' of module '{}' could not open, and axis '{}' goes on without it", entry.family, entry.moduleId, entry.axis, it)
            return null
        }
        return Opened(entry, axis, answers, job, on)
    }

    // Starts a pass whenever an open provider's answers change from what was last published. One that only
    // has another standing on it answers through that one, so it asks for a pass only where its answers are
    // not its axis's type any more.
    private fun rewatch() {
        val flows = synchronized(lock) {
            watch?.cancel()
            if (closed) return
            open.values.map { opened -> opened.answers.answers.map { opened to it } }
        }
        if (flows.isEmpty()) return
        val job = scope.launch {
            flows.merge().collect { (opened, value) ->
                val published = synchronized(lock) {
                    _answers.value.any { it.value === value } || leases.any { lease -> lease.answers.value.any { it.value === value } } ||
                        opened.axis.accepts(value) && !opened.answering
                }
                if (!published && opened.entry.provider !in synchronized(lock) { failed }) wake.trySend(Unit)
            }
        }
        synchronized(lock) { watch = job }
    }

    /** A provider while it is open, the job its work runs under, and what it stands on. */
    private inner class Opened(
        val entry: ProviderEntry,
        val axis: AxisEntry,
        val answers: LookAnswers<Any>,
        private val job: Job,
        val on: Opened?,
    ) {
        /** How many it stands on, one on another. */
        val depth: Int = if (on == null) 0 else on.depth + 1

        /** Whether it answers its axis, here or in a subtree, and is not only stood on. Written and read under the lock. */
        var answering: Boolean = false

        // Its answers now, or null where they cannot be read or are not of [axis]'s type, which fails it.
        fun current(axis: AxisEntry): Any? {
            val value = guarded(alive) { answers.answers.value }.getOrNull()
            if (axis.accepts(value)) return value
            log.warn("Look provider '{}' of module '{}' does not answer axis '{}' in its own type, and is passed over", entry.family, entry.moduleId, entry.axis)
            return null
        }

        fun close() {
            guarded(alive) { answers.close() }.onFailure {
                log.warn("Look provider '{}' of module '{}' failed to close", entry.family, entry.moduleId, it)
            }
            job.cancel()
        }
    }

    /** A subtree's look: the answers of every axis under its [choice], while the composition remembers it. */
    inner class Lease(val choice: LookChoice) : RememberObserver {
        private val _answers = MutableStateFlow<List<AxisAnswer>>(emptyList())

        /** The answers of every axis in the subtree, empty until a pass has worked them out. */
        val answers: StateFlow<List<AxisAnswer>> = _answers.asStateFlow()

        internal fun publish(now: List<AxisAnswer>) {
            _answers.value = now
        }

        /** Lets go of the providers only this subtree needed. */
        fun close() = release(this)

        override fun onRemembered() = Unit

        override fun onForgotten() = close()

        override fun onAbandoned() = close()
    }
}
