package hivens.packs

import hivens.core.data.PackInstance
import hivens.core.data.PackReference
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchState
import hivens.core.update.PackUpdateStatus
import hivens.launcher.InstallPhase
import hivens.launcher.InstallSnapshot
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationPhase
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import hivens.packs.api.InstallStatus
import hivens.packs.api.InstallView
import hivens.packs.api.LaunchView
import hivens.packs.api.OperationStatus
import hivens.packs.api.PackOperationView
import hivens.packs.api.PackSource
import hivens.packs.api.PackUpdateView
import hivens.packs.api.PackView
import hivens.packs.api.ReattachPreviewView
import hivens.packs.api.ReattachConfirmation as ConfirmationView
import hivens.packs.api.ReattachPreview as PreviewView

// How the engine's records read as the plain data the packs API carries. One place,
// so a field a widget needs is added here and nowhere else.

/** The pack as a widget reads it. */
fun PackInstance.toPackView(): PackView = PackView(
    id = id,
    name = displayName,
    source = packRef.toSource(),
    forkedFrom = forkedFrom?.toSource(),
    packVersion = pinnedPackVersion ?: packRef.version,
    gameVersion = cachedManifest?.minecraftVersion?.takeIf { it.isNotBlank() },
    loader = cachedManifest?.loaderName?.takeIf { it.isNotBlank() },
    loaderVersion = cachedManifest?.loaderVersion?.takeIf { it.isNotBlank() },
    iconUrl = iconUrl,
    bannerUrl = bannerUrl,
    folder = instanceDirName,
    createdAtEpochSecond = createdAtEpoch,
    lastPlayedEpochSecond = lastPlayedEpochOrZero,
    playtimeSeconds = playtimeSeconds,
    followLatest = followLatest,
)

private fun PackReference.toSource(): PackSource = PackSource(origin.id, id, version)

/** The launcher's one launch as a widget reads it. The running game's handle stays in the engine. */
fun LaunchState.toView(): LaunchView = when (this) {
    LaunchState.Idle -> LaunchView.Idle
    is LaunchState.Prepare -> LaunchView.Preparing(stage.name.lowercase(), progress)
    is LaunchState.Downloading -> LaunchView.Downloading(currentFileIdx, totalFiles, downloadedBytes, totalBytes, speedBytesPerSec)
    is LaunchState.GameRunning -> LaunchView.Running
    is LaunchState.Stopping -> LaunchView.Stopping
    is LaunchState.Error -> reason.toFailed()
}

private fun LaunchError.toFailed(): LaunchView.Failed = when (this) {
    is LaunchError.ExitCode -> LaunchView.Failed("exit-code", code.toString())
    is LaunchError.Internal -> LaunchView.Failed("internal", message)
    is LaunchError.AuthlibUnavailable -> LaunchView.Failed("authlib-unavailable", mcVersion)
    is LaunchError.MissingAuthProvider -> LaunchView.Failed("missing-auth-provider", providerKey)
    is LaunchError.InstanceBusy -> LaunchView.Failed("busy", work.name.kebab())
    LaunchError.ContentChangedDuringLaunch -> LaunchView.Failed("content-changed")
    LaunchError.OfflineNoClient -> LaunchView.Failed("offline-no-client")
    LaunchError.TwoFactorExpired -> LaunchView.Failed("two-factor-expired")
}

/** One install as a widget reads it. */
fun InstallSnapshot.toView(): InstallView = InstallView(
    key = key,
    origin = origin.id,
    packId = packId,
    versionId = versionId,
    title = title,
    iconUrl = iconUrl,
    status = when (val p = phase) {
        is InstallPhase.Running -> InstallStatus.Running(p.current, p.total, p.filename)
        is InstallPhase.Succeeded -> InstallStatus.Succeeded(p.instanceId)
        is InstallPhase.Failed -> InstallStatus.Failed(p.message)
        InstallPhase.Cancelled -> InstallStatus.Cancelled
    },
)

/** What the launcher last learned about a newer build, as a widget reads it. */
fun PackUpdateStatus.toView(): PackUpdateView = when (this) {
    PackUpdateStatus.Checking -> PackUpdateView.Checking
    PackUpdateStatus.UpToDate -> PackUpdateView.UpToDate
    is PackUpdateStatus.Updated -> PackUpdateView.Updated(toVersion)
    is PackUpdateStatus.Pending -> PackUpdateView.Pending(toVersion, direction.name.kebab(), compat.name.kebab(), held)
    is PackUpdateStatus.Failed -> PackUpdateView.Failed(reason)
}

/** One operation of the operation service as a widget reads it. */
fun PackOperation.toView(): PackOperationView = PackOperationView(
    kind = kind.name.kebab(),
    status = when (val p = phase) {
        is PackOperationPhase.Running -> OperationStatus.Running(p.current, p.total)
        is PackOperationPhase.Updated -> OperationStatus.Done(p.version)
        is PackOperationPhase.Repaired ->
            if (p.failed.isEmpty()) OperationStatus.Done() else OperationStatus.Failed("incomplete", p.failed.joinToString(", "))
        is PackOperationPhase.Failed -> OperationStatus.Failed("failed", p.message)
        is PackOperationPhase.Refused -> OperationStatus.Failed("busy", p.work.name.kebab())
        PackOperationPhase.PreviewStale -> OperationStatus.Failed("preview-stale")
    },
)

/** How a delete ended, as the operation outcome says it. */
internal fun DeleteOutcome.toStatus(): OperationStatus = when (this) {
    DeleteOutcome.Deleted -> OperationStatus.Done()
    DeleteOutcome.GameRunning -> OperationStatus.Failed("game-running")
    is DeleteOutcome.Busy -> OperationStatus.Failed("busy", work.name.kebab())
    DeleteOutcome.Incomplete -> OperationStatus.Failed("incomplete")
}

/** A reattach preview's state as a widget reads it. */
fun ReattachPreviewState.toView(): ReattachPreviewView = when (this) {
    ReattachPreviewState.Comparing -> ReattachPreviewView.Comparing
    is ReattachPreviewState.Ready -> ReattachPreviewView.Ready(preview.toView())
    is ReattachPreviewState.Failed -> ReattachPreviewView.Failed(message)
}

private fun ReattachPreview.toView(): PreviewView = PreviewView(
    toVersion = toVersion,
    compat = compat.name.kebab(),
    removed = removed,
    restored = restored,
    replaced = replaced,
    turnedOn = turnedOn,
    turnedOff = turnedOff,
    kept = kept,
    basis = basis.name.kebab(),
    unreadable = unreadable,
    confirmation = ConfirmationView(confirmation.build, confirmation.fingerprint),
)

/** The engine's confirmation from the one a widget handed back. */
internal fun ConfirmationView.toEngine(): ReattachConfirmation = ReattachConfirmation(build, fingerprint)

/** `LoaderBump` as `loader-bump`: an enum's name the way the API spells a word. */
internal fun String.kebab(): String =
    fold(StringBuilder()) { out, c ->
        if (c.isUpperCase() && out.isNotEmpty()) out.append('-')
        out.append(c.lowercaseChar())
    }.toString()
