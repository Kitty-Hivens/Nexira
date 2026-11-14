package hivens.ui.packs

import hivens.core.data.PackInstance
import hivens.launcher.PackOperation
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A detached pack to put back on its source, and the preview the player agreed to. */
data class ReattachRequest(val packId: String, val confirmation: ReattachConfirmation)

/** Where the comparison of a detached pack against its source's build stands. */
sealed interface ReattachPreviewState {
    data object Comparing : ReattachPreviewState

    /** The comparison is in. Its [ReattachPreview.confirmation] is what a reattach takes. */
    data class Ready(val preview: ReattachPreview) : ReattachPreviewState

    data class Failed(val message: String) : ReattachPreviewState
}

/**
 * The latest reattach preview of each pack that asked for one, for the app.
 *
 * A reattach runs only on a preview the player confirmed, and a widget gets one
 * here the way the settings screen does: it asks, reads the answer, and hands
 * [ReattachPreview.confirmation] to the reattach. A preview describes the files as
 * they were measured, so when an operation on the pack (an update, a repair, a
 * reattach) starts or ends it is measured again, and shows
 * [ReattachPreviewState.Comparing] for as long as that operation runs. Other
 * changes to the files, an edit on the Content tab among them, do not refresh it.
 * It stays until whoever asked forgets it, or until [keepFor] has passed since it
 * answered, and either stops one still being measured and lets go of what it kept
 * for the reattach. The launcher measures the pack once more before any reattach in
 * any case, and refuses one whose preview no longer holds.
 *
 * Every change to what is asked, measured and shown happens under one lock, so a
 * measurement that finishes as its preview is forgotten cannot bring it back.
 */
class ReattachPreviews(
    private val measure: suspend (PackInstance) -> ReattachPreview,
    operations: StateFlow<Map<String, PackOperation>>,
    private val scope: CoroutineScope,
    /** Lets go of what a preview kept for its reattach, run once nobody wants the preview. */
    private val discard: suspend (PackInstance) -> Unit = {},
    /** How long an answered preview is kept for a caller that never forgets it. */
    private val keepFor: Duration = 15.minutes,
) {
    private val _previews = MutableStateFlow<Map<String, ReattachPreviewState>>(emptyMap())

    /** The latest preview per pack id, for each pack whose preview is wanted. */
    val previews: StateFlow<Map<String, ReattachPreviewState>> = _previews.asStateFlow()

    private val lock = Any()
    private val asked = HashMap<String, PackInstance>()
    private val jobs = HashMap<String, Job>()
    private val expiries = HashMap<String, Job>()

    init {
        // Taken now, not when the collector first runs: work that ended before this
        // store existed says nothing about a preview asked for after it. A start, an
        // end or a refusal is news, progress within one piece of work is not, and a
        // state flow can fold a start and an end into one value, so the kind of each
        // phase is compared with what was last seen rather than waited on.
        var seen = operations.value
        scope.launch {
            operations.collect { all ->
                all.forEach { (id, operation) ->
                    val before = seen[id]
                    if (before == null || before.kind != operation.kind || before.phase::class != operation.phase::class) {
                        renew(id, operation.isRunning)
                    }
                }
                // An entry dismissed before its end was seen ended all the same.
                // Waited on instead, a preview read as comparing for good.
                (seen.keys - all.keys).forEach { id -> renew(id, working = false) }
                seen = all
            }
        }
    }

    /** Measures [pack] against its source's build again, replacing any preview it had. */
    fun request(pack: PackInstance) {
        synchronized(lock) {
            asked[pack.id] = pack
            startLocked(pack)
        }
    }

    /** Drops the preview of the pack [packId], stopping it if it is still being measured. */
    fun forget(packId: String) {
        val pack = synchronized(lock) {
            jobs.remove(packId)?.cancel()
            expiries.remove(packId)?.cancel()
            _previews.update { it - packId }
            asked.remove(packId)
        } ?: return
        scope.launch { discard(pack) }
    }

    /** Work on the pack [packId] started or ended: its preview, if one is wanted, is measured again once none runs. */
    private fun renew(packId: String, working: Boolean) {
        synchronized(lock) {
            val pack = asked[packId] ?: return
            jobs.remove(packId)?.cancel()
            expiries.remove(packId)?.cancel()
            if (working) {
                _previews.update { it + (packId to ReattachPreviewState.Comparing) }
            } else {
                startLocked(pack)
            }
        }
    }

    private fun startLocked(pack: PackInstance) {
        expiries.remove(pack.id)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val state = try {
                ReattachPreviewState.Ready(measure(pack))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReattachPreviewState.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
            val self = coroutineContext.job
            // Only the measurement still current for a pack still wanted may answer for it.
            synchronized(lock) {
                if (jobs[pack.id] === self) {
                    jobs.remove(pack.id)
                    _previews.update { it + (pack.id to state) }
                    expiries.put(pack.id, scope.launch { delay(keepFor); forget(pack.id) })?.cancel()
                }
            }
        }
        jobs.put(pack.id, job)?.cancel()
        _previews.update { it + (pack.id to ReattachPreviewState.Comparing) }
        job.start()
    }
}
