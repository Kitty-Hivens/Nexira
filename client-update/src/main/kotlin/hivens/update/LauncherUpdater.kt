package hivens.update

import hivens.core.data.FileManifest
import hivens.core.update.LauncherPatch
import hivens.core.update.LauncherUpdatePlanner
import org.slf4j.LoggerFactory

/** Result of a delta self-update attempt. */
sealed interface UpdateOutcome {
    /** Local layout already matches the target -- nothing fetched. */
    data object UpToDate : UpdateOutcome
    /** [changed] files were staged, verified and committed to version [version]. */
    data class Applied(val version: String, val changed: Int) : UpdateOutcome
}

/**
 * Ties the delta pieces into one flow over the managed [layout]: diff the recorded
 * local manifest against the target, stage + verify, then commit. The layout stays
 * untouched unless every staged file verifies, and a crash mid-commit is finished by
 * [recoverIfInterrupted] on the next start -- so an update is all-or-nothing from the
 * user's point of view.
 */
class LauncherUpdater(private val layout: InstallLayout) {
    private val log = LoggerFactory.getLogger(LauncherUpdater::class.java)

    fun update(
        remote: FileManifest,
        patches: Map<String, LauncherPatch>,
        source: AssetSource,
        version: String,
    ): UpdateOutcome {
        // An apply a crash interrupted has moved some files and recorded none of
        // it. Planning from the recorded manifest would patch files that are
        // already new, and staging would clear what the resume needs.
        check(!LayoutApplier(layout).hasPendingApply) {
            "an interrupted update has not been finished yet; run recoverIfInterrupted first"
        }
        // The recorded manifest is the baseline; fall back to a live scan the first time
        // the managed layout runs without one.
        val local = LayoutManifest.read(layout.manifestFile)
            ?: LayoutManifest.scan(layout.root, excludes = layout.bookkeeping)

        val plan = LauncherUpdatePlanner.plan(local, remote, patches)
        if (plan.isEmpty) {
            log.info("launcher up to date")
            return UpdateOutcome.UpToDate
        }
        log.info("updating to {}: {} patch, {} download, {} delete",
            version, plan.patches.size, plan.downloads.size, plan.deletes.size)

        val staged = UpdateStager(layout, source).stage(plan, remote)
        LayoutApplier(layout).apply(staged, remote, version)
        return UpdateOutcome.Applied(version, plan.changeCount)
    }

    /** Finish an apply interrupted by a crash, using [remoteFor] to resolve the target
     *  manifest for the pending version. Call once at startup, before launching. */
    fun recoverIfInterrupted(remoteFor: (String) -> FileManifest?) {
        LayoutApplier(layout).recover(remoteFor)
    }
}
