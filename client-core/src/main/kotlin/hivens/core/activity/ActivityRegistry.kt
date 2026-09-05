package hivens.core.activity

import hivens.core.logging.Redactor
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single account of what the launcher is currently doing. Installs, updates,
 * syncs, repairs, launches and running games all report here, and one surface
 * renders the result -- so a long operation is narrated in one place instead of
 * being announced by whichever subsystem happened to own it.
 *
 * Three properties are contract, not implementation detail, because the surface
 * reading this registry is permanent chrome rather than a toast that ages out in
 * six seconds.
 *
 * **Machine text is redacted on the way in.** [ActivityPhase.Failed.reason] is an
 * exception message and [ActivityPhase.Running.detail] is a filename or a path;
 * both reach the screen verbatim otherwise. The launcher's HTTP layer folds the
 * response body into the exception text, so a failing endpoint can put whatever
 * it returned onto a surface that sits above everything and, by design, does not
 * dismiss itself. Redacting in [report] rather than at each render site is what
 * makes that unforgettable. [Activity.title] is deliberately NOT redacted: it is
 * a display name a person chose, and mangling it would be a visible defect with
 * nothing to gain.
 *
 * **Output is rate-limited.** A download ticks roughly ten times a second and the
 * transfer engine can go faster on a block-fetched file. Compose Desktop composes
 * on one thread, so an unthrottled feed would spend it redrawing a progress
 * measure. A change that alters the SHAPE of the state -- an activity appearing,
 * finishing, failing, being dismissed -- publishes at once; a change that only
 * advances an existing measure waits for the next tick. Terminal states are never
 * delayed behind a throttle.
 *
 * **Terminal entries are capped.** The three upstream stores each keep their own
 * finished work around: the install service holds terminal snapshots until they
 * are dismissed, the indication centre never shrinks, and the notification centre
 * caps at 32 groups for exactly this reason. Aggregating them without a cap of
 * our own inherits the worst of the three. Running entries are NOT capped -- they
 * are bounded by how many operations can genuinely be in flight, and evicting one
 * would take its cancel control away from the user, which is worse than the
 * growth it would prevent.
 *
 * A [ActivityPhase.Failed] entry is never evicted by age. It is the only record
 * the user gets that something went wrong, and a failure that vanishes on a timer
 * is a silent failure with extra steps. Failures still obey [maxFailed] so a
 * pathological retry loop cannot grow the list without bound.
 */
class ActivityRegistry(
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    /** Floor between two progress-only publications. 250ms = 4Hz. */
    private val minPublishIntervalMs: Long = 250,
    /** How long a succeeded or cancelled entry lingers before it is evicted. */
    private val terminalHoldMs: Long = 4_000,
    /** Cap on settled entries. Oldest goes first, failures last. */
    private val maxTerminal: Int = 12,
    /** Cap on retained failures, so a retry loop cannot grow the list. */
    private val maxFailed: Int = 8,
) {
    private val log = LoggerFactory.getLogger(ActivityRegistry::class.java)

    private val lock = Any()

    /** Insertion-ordered so "oldest" is a position rather than a timestamp scan. */
    private val entries = LinkedHashMap<String, Activity>()

    private val _activities = MutableStateFlow<List<Activity>>(emptyList())

    /** Rate-limited view. See the class doc for what "rate-limited" excludes. */
    val activities: StateFlow<List<Activity>> = _activities.asStateFlow()

    private var pendingPublish: Job? = null
    private var dirty = false

    // Eviction timers for settled entries, keyed like [entries]. Replacing an
    // entry cancels its old timer, so a key that finishes, is re-reported as
    // running, and finishes again does not get evicted by the first timer.
    private val evictions = HashMap<String, Job>()

    /**
     * Publish the current state of one activity. Safe to call from a non-suspend
     * callback -- the install and transfer paths hand out plain progress lambdas.
     *
     * Re-reporting an existing [key] replaces it in place and keeps its original
     * [Activity.startedAtMillis], so elapsed time survives a progress tick.
     */
    fun report(
        key: String,
        kind: ActivityKind,
        title: String,
        phase: ActivityPhase,
        iconUrl: String? = null,
        actions: Set<ActivityAction> = emptySet(),
    ) {
        val now = clock.nowMillis()
        val clean = redact(phase)
        synchronized(lock) {
            val previous = entries[key]
            val entry = Activity(
                key             = key,
                kind            = kind,
                title           = title,
                iconUrl         = iconUrl,
                phase           = clean,
                startedAtMillis = previous?.startedAtMillis ?: now,
                updatedAtMillis = now,
                actions         = actions,
            )
            entries[key] = entry

            // Shape change = anything a reader would treat as news. Two Running
            // phases in a row are the only case that may wait for the throttle.
            val shapeChanged = previous == null ||
                previous.phase::class != clean::class ||
                previous.kind != kind ||
                previous.actions != actions

            evictions.remove(key)?.cancel()
            if (clean is ActivityPhase.Succeeded || clean is ActivityPhase.Cancelled) {
                evictions[key] = scope.launch {
                    delay(terminalHoldMs.milliseconds)
                    synchronized(lock) {
                        // Only evict if it is still the settled entry we scheduled for.
                        val current = entries[key]
                        if (current != null && current.updatedAtMillis == entry.updatedAtMillis) {
                            entries.remove(key)
                            evictions.remove(key)
                            publish(immediate = true)
                        }
                    }
                }
            }

            enforceCaps()
            publish(immediate = shapeChanged)
        }
    }

    /** Drop one entry. This is how a failure leaves, since age never removes it. */
    fun dismiss(key: String) {
        synchronized(lock) {
            evictions.remove(key)?.cancel()
            if (entries.remove(key) != null) publish(immediate = true)
        }
    }

    /** Drop every settled entry, leaving what is still in flight. */
    fun dismissSettled() {
        synchronized(lock) {
            val settled = entries.filterValues { it.phase.isTerminal }.keys.toList()
            if (settled.isEmpty()) return
            settled.forEach { evictions.remove(it)?.cancel(); entries.remove(it) }
            publish(immediate = true)
        }
    }

    fun clear() {
        synchronized(lock) {
            evictions.values.forEach { it.cancel() }
            evictions.clear()
            entries.clear()
            publish(immediate = true)
        }
    }

    private fun redact(phase: ActivityPhase): ActivityPhase = when (phase) {
        is ActivityPhase.Running ->
            phase.detail?.let { phase.copy(detail = Redactor.redact(it)) } ?: phase
        is ActivityPhase.Failed ->
            phase.reason?.let { phase.copy(reason = Redactor.redact(it)) } ?: phase
        ActivityPhase.Succeeded, ActivityPhase.Cancelled -> phase
    }

    /** Caller holds [lock]. */
    private fun enforceCaps() {
        val failedKeys = entries.entries.filter { it.value.phase is ActivityPhase.Failed }.map { it.key }
        dropOldest(failedKeys, failedKeys.size - maxFailed)

        val terminalKeys = entries.entries.filter { it.value.phase.isTerminal }.map { it.key }
        val overflow = terminalKeys.size - maxTerminal
        if (overflow <= 0) return
        // Settled-but-not-failed first: a failure is the only record of a problem,
        // so it outlives a success that nobody needs to read.
        val ordered = terminalKeys.sortedBy { entries[it]?.phase is ActivityPhase.Failed }
        dropOldest(ordered, overflow)
    }

    /** Caller holds [lock]. [keys] is already in eviction preference order. */
    private fun dropOldest(keys: List<String>, count: Int) {
        if (count <= 0) return
        keys.take(count).forEach { key ->
            evictions.remove(key)?.cancel()
            entries.remove(key)
        }
    }

    /** Caller holds [lock]. */
    private fun publish(immediate: Boolean) {
        if (immediate) {
            pendingPublish?.cancel()
            pendingPublish = null
            dirty = false
            _activities.value = entries.values.toList()
            return
        }
        dirty = true
        if (pendingPublish != null) return
        pendingPublish = scope.launch {
            delay(minPublishIntervalMs.milliseconds)
            synchronized(lock) {
                pendingPublish = null
                if (dirty) {
                    dirty = false
                    _activities.value = entries.values.toList()
                }
            }
        }
    }
}
