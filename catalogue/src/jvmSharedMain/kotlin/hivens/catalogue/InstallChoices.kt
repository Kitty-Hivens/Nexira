package hivens.catalogue

import hivens.catalogue.api.CandidateState
import hivens.catalogue.api.CandidateVerdict
import hivens.catalogue.api.InstallCandidate
import hivens.catalogue.api.InstallChoiceView
import hivens.catalogue.api.InstallProblem
import hivens.catalogue.api.InstallSubject
import hivens.catalogue.api.NewPackRequest
import hivens.catalogue.api.NewPackState
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackInstance
import hivens.launcher.InstallPhase
import hivens.launcher.InstallSnapshot
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.InstallTargets
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.chooseBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

private val log = LoggerFactory.getLogger("hivens.catalogue.InstallChoice")

/** What the install that asks where needs of the launcher: the project's builds, every pack's answer, the installer and a new pack. */
interface InstallWork {
    suspend fun listVersions(projectId: String): List<ModrinthVersion>
    suspend fun candidates(projectId: String, listing: List<ModrinthVersion>): List<InstallTargets.Candidate>
    suspend fun install(dir: Path, version: ModrinthVersion, mc: String, loader: String): ModInstaller.Outcome

    /** Every pack install there is, the new pack's among them, as the install service keeps them. */
    val installs: StateFlow<Map<String, InstallSnapshot>>

    /** Starts making a pack under [key], the way the install service runs any pack job. */
    fun createPack(key: String, name: String, mcVersion: String, loader: String?)

    fun dismiss(key: String)

    suspend fun packOf(id: String): PackInstance?

    val instancesDir: Path

    /** Whether the launcher installs a project the project source [source] found, null for a project page's. */
    fun installsFrom(source: String?): Boolean = true
}

/**
 * The window's install that asks where, as [InstallChoiceView]: a project found from the
 * launcher's own search rather than from inside a pack, every pack with its answer, and
 * a pack that does not exist yet.
 *
 * One at a time per window. Every install runs on [work], the app's: closing the dialog,
 * or leaving the screen, leaves an install that has started to finish, though what it
 * came to is no longer shown once the dialog is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallChoices(
    private val launcher: InstallWork,
    scope: CoroutineScope,
    work: CoroutineScope,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(scope.coroutineContext + serial)
    private val work = CoroutineScope(work.coroutineContext + serial)

    private val _view = MutableStateFlow<InstallChoiceView?>(null)
    val view: StateFlow<InstallChoiceView?> = _view.asStateFlow()

    /** The open choice, so an install that ends after it closed writes nothing over the next. */
    private var session: Any? = null
    private var candidates: Map<String, InstallTargets.Candidate> = emptyMap()
    private var listing: List<ModrinthVersion> = emptyList()
    private var reading: Job? = null

    /** Opens the choice for [subject], and reads every pack's answer. */
    fun choose(subject: InstallSubject) {
        scope.launch {
            val opened = Any()
            session = opened
            candidates = emptyMap()
            listing = emptyList()
            _view.value = InstallChoiceView(subject)
            read(opened, subject)
        }
    }

    /** Reads every pack's answer again after a read that failed. */
    fun retry() {
        scope.launch {
            val opened = session ?: return@launch
            val subject = _view.value?.subject ?: return@launch
            read(opened, subject)
        }
    }

    /** Closes the open choice. */
    fun dismiss() {
        scope.launch {
            reading?.cancel()
            session = null
            _view.value = null
        }
    }

    private fun edit(opened: Any, change: (InstallChoiceView) -> InstallChoiceView) {
        if (session !== opened) return
        _view.update { it?.let(change) }
    }

    private fun read(opened: Any, subject: InstallSubject) {
        reading?.cancel()
        if (!launcher.installsFrom(subject.source)) {
            // Asked of the launcher's own installer, the project's id would be looked up
            // at a source that did not list it.
            log.info("not offering an install of {}: the source '{}' cannot install it yet", subject.projectId, subject.source)
            edit(opened) { it.copy(candidates = emptyList(), failed = false, unsupported = true) }
            return
        }
        edit(opened) { it.copy(candidates = null, failed = false, unsupported = false) }
        reading = scope.launch {
            try {
                val builds = withContext(Dispatchers.IO) { launcher.listVersions(subject.projectId) }
                listing = builds
                val found = launcher.candidates(subject.projectId, builds)
                candidates = found.associateBy { it.pack.id }
                edit(opened) { it.copy(listing = builds.map { b -> b.toBuild() }, candidates = found.map { c -> c.toView() }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("reading where {} could go failed", subject.projectId, e)
                edit(opened) { it.copy(failed = true, candidates = emptyList()) }
            }
        }
    }

    /** Installs the open choice's project into the pack [packId], where it fits. */
    fun installInto(packId: String) {
        scope.launch {
            val opened = session ?: return@launch
            val candidate = candidates[packId] ?: return@launch
            val build = (candidate.verdict as? InstallTargets.Verdict.Fits)?.build ?: return@launch
            edit(opened) { it.copy(rows = it.rows + (packId to CandidateState.Working)) }
            work.launch {
                val target = candidate.destination
                val state = try {
                    val outcome = withContext(Dispatchers.IO) { launcher.install(target.dir, build, target.mcVersion, target.loader) }
                    problemOf(outcome)?.let { CandidateState.Failed(it) } ?: CandidateState.Done(outcome.missing.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("installing {} into {} failed", build.projectId, target.dir, e)
                    CandidateState.Failed(InstallProblem.NotLanded)
                }
                edit(opened) { it.copy(rows = it.rows + (packId to state)) }
            }
        }
    }

    /** Makes a pack for the open choice's project as [request] says, and installs the project there. */
    fun installNewPack(request: NewPackRequest) {
        scope.launch {
            val opened = session ?: return@launch
            val key = "create:${UUID.randomUUID()}"
            val builds = listing
            edit(opened) { it.copy(newPack = NewPackState.Creating()) }
            work.launch {
                try {
                    launcher.createPack(key, request.name, request.mcVersion, request.loader)
                    // The pack job's progress, while it runs, for the dialog to say how far it is.
                    val progress = work.launch {
                        launcher.installs.collect { all ->
                            val phase = all[key]?.phase as? InstallPhase.Running ?: return@collect
                            edit(opened) { it.copy(newPack = NewPackState.Creating(phase.current, phase.total)) }
                        }
                    }
                    val phase = launcher.installs.first { all -> all[key]?.phase.let { it != null && it !is InstallPhase.Running } }[key]?.phase
                    progress.cancel()
                    if (phase !is InstallPhase.Succeeded) {
                        edit(opened) { it.copy(newPack = NewPackState.Failed((phase as? InstallPhase.Failed)?.message.orEmpty())) }
                        return@launch
                    }
                    launcher.dismiss(key)
                    edit(opened) { it.copy(newPack = NewPackState.Installing) }
                    val pack = withContext(Dispatchers.IO) { launcher.packOf(phase.instanceId) }
                        ?: return@launch edit(opened) { it.copy(newPack = NewPackState.Failed()) }
                    val target = ContentDestination.Pack.of(pack, launcher.instancesDir)
                    val build = chooseBuild(builds, target.mcVersion, target.loader)
                        ?: return@launch edit(opened) { it.copy(newPack = NewPackState.Failed()) }
                    val outcome = withContext(Dispatchers.IO) { launcher.install(target.dir, build, target.mcVersion, target.loader) }
                    edit(opened) {
                        it.copy(newPack = if (outcome.ok) NewPackState.Done(pack.id, pack.displayName, outcome.missing.size) else NewPackState.Failed())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("making a pack for {} failed", request.name, e)
                    edit(opened) { it.copy(newPack = NewPackState.Failed(e.message.orEmpty())) }
                }
            }
        }
    }
}

private fun InstallTargets.Candidate.toView(): InstallCandidate = InstallCandidate(
    packId = pack.id,
    name = pack.displayName,
    mcVersion = destination.mcVersion,
    loader = destination.loader,
    verdict = when (val v = verdict) {
        is InstallTargets.Verdict.Fits -> CandidateVerdict.Fits
        is InstallTargets.Verdict.Present -> CandidateVerdict.Present
        is InstallTargets.Verdict.NotFit -> when (v.reason) {
            InstallTargets.Unfit.NoBuild -> CandidateVerdict.NoBuild
            InstallTargets.Unfit.NotTaken -> CandidateVerdict.NotTaken
        }
        InstallTargets.Verdict.Unknown -> CandidateVerdict.Unknown
    },
)
