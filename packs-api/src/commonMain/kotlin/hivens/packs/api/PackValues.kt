package hivens.packs.api

/**
 * Where a pack comes from: the id of its source and the pack's id there, with the
 * build it points at where it points at one. [origin] is the source's id exactly as the
 * pack's record carries it, `Local` for a pack of the user's own and otherwise the id a
 * source module declares, and a reader treats one it does not know as a source whose
 * module is not brought in.
 */
data class PackSource(
    val origin: String,
    val id: String,
    val version: String? = null,
)

/**
 * One installed pack as a widget reads it. Plain data, a copy of the record as it
 * was when the library last changed.
 */
data class PackView(
    /** The pack's own id, which every pack command takes. */
    val id: String,
    val name: String,
    /** The source it was installed from. */
    val source: PackSource,
    /** The source it was detached or duplicated from, when it was. Its art is that source's. */
    val forkedFrom: PackSource? = null,
    /** The build installed, as its source labels it, or null when nothing has said. */
    val packVersion: String? = null,
    /** The Minecraft version the build runs, or null before the pack was first prepared. */
    val gameVersion: String? = null,
    /** The mod loader the build runs, `vanilla` included, or null before the pack was first prepared. */
    val loader: String? = null,
    val loaderVersion: String? = null,
    /** The icon and the wide banner captured from the catalogue at install, null where it had none. */
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    /** The name of the pack's own folder under the launcher's instances folder. */
    val folder: String,
    /** When it was installed, in seconds since the epoch. */
    val createdAtEpochSecond: Long,
    /** When it was last played, in seconds since the epoch, or 0 when it never was. */
    val lastPlayedEpochSecond: Long = 0,
    /** Time spent in the game across every session. */
    val playtimeSeconds: Long = 0,
    /** Whether the pack follows its source's latest build, rather than staying on the one installed. */
    val followLatest: Boolean = true,
)

/** Where the launch of one pack stands. Absent from [PackKeys.Launches] when nothing is in flight. */
sealed interface PackLaunch {
    data object Preparing : PackLaunch

    /** Fetching files, [progress] the share done, or null when the size is not known yet. */
    data class Downloading(val progress: Float?) : PackLaunch

    data object Running : PackLaunch

    /** Asked to end, and the game has not gone yet. */
    data object Stopping : PackLaunch

    data object Failed : PackLaunch
}

/** What the one launch the launcher runs at a time is doing. */
sealed interface LaunchView {
    data object Idle : LaunchView

    /**
     * Getting ready, before any file is fetched. [stage] names the step in lower
     * case: `init`, `auth`, `sync`, `jvm`, `launch`. [progress] is a coarse share, 0 to 1.
     */
    data class Preparing(val stage: String, val progress: Float) : LaunchView

    data class Downloading(
        val file: Int,
        val files: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : LaunchView

    data object Running : LaunchView

    /** The game was asked to end and has not gone yet. Nothing else may launch until it has. */
    data object Stopping : LaunchView

    /**
     * The launch failed. [code] says why in a word a widget can choose its own text
     * by: `exit-code`, `internal`, `content-changed`, `offline-no-client`,
     * `two-factor-expired`, `authlib-unavailable`, `missing-auth-provider`, `busy`, or
     * another a newer build adds. [detail] is what goes with it, the exit code or the
     * message, where there is one.
     */
    data class Failed(val code: String, val detail: String? = null) : LaunchView
}

/** One build of a catalogue pack to install, by the source's id (see [PackSource.origin]) and its ids there. */
data class InstallRequest(
    /** The source's id exactly as a record carries it. Another spelling, `mirror` for `Mirror` say, names no source. */
    val origin: String,
    val packId: String,
    val versionId: String,
)

/** One pack install, running or finished and not yet dismissed. */
data class InstallView(
    /** What [PackKeys.DismissInstall] and [PackKeys.CancelInstall] take. */
    val key: String,
    /** The id of the source it is installed from, as [PackSource.origin] gives it. */
    val origin: String,
    val packId: String,
    val versionId: String,
    val title: String,
    val iconUrl: String? = null,
    val status: InstallStatus,
)

/** Where one install stands. */
sealed interface InstallStatus {
    data class Running(val done: Int, val total: Int, val file: String) : InstallStatus

    /** Installed, as the pack with [packId] in the library. */
    data class Succeeded(val packId: String) : InstallStatus

    data class Failed(val message: String) : InstallStatus

    data object Cancelled : InstallStatus
}

/** What the launcher last learned about a newer build of one pack. */
sealed interface PackUpdateView {
    data object Checking : PackUpdateView

    data object UpToDate : PackUpdateView

    /** Moved on its own to [toVersion]. */
    data class Updated(val toVersion: String) : PackUpdateView

    /**
     * Another build is there and waits for the player: an update is waiting.
     * [direction] is `newer`, `older` (the source moved its latest back) or
     * `unknown`. [compat] grades how far it moves the game: `same`, `loader-bump`,
     * `mc-bump`, `loader-swap` or `unknown`. [held] says the player asked to stay on
     * the build they have, so nothing needs to ask for their attention.
     */
    data class Pending(
        val toVersion: String,
        val direction: String,
        val compat: String,
        val held: Boolean = false,
    ) : PackUpdateView {
        companion object {
            /** A newer build than the one installed. */
            const val NEWER: String = "newer"

            /** An older build: the source moved its latest back. */
            const val OLDER: String = "older"

            /** No baseline to compare with, or a build the source's listing does not carry. */
            const val UNKNOWN: String = "unknown"
        }
    }

    data class Failed(val reason: String) : PackUpdateView
}

/**
 * The last long operation on one pack. [kind] is `update`, `repair`, `reattach` or
 * `delete`, or another a newer build adds.
 */
data class PackOperationView(
    val kind: String,
    val status: OperationStatus,
)

/** Where an operation stands: under way, done, or ended without doing what it was asked. */
sealed interface OperationStatus {
    /** Under way, [done] of [total] steps, [total] 0 while the size is not known. */
    data class Running(val done: Int, val total: Int) : OperationStatus

    /** Done. [version] is the build the pack is now on, for an operation that moves it. */
    data class Done(val version: String? = null) : OperationStatus

    /**
     * Not done. [code] says why in a word: `failed` when something went wrong,
     * `busy` when other work held the pack, `game-running` when its game was running,
     * `preview-stale` when a reattach no longer matched its preview, `incomplete`
     * when a delete left files behind or a repair could not put every file right,
     * `cancelled` when the launcher stopped it on the way out. [message] is the
     * detail where there is one. Nothing was touched unless the code is `failed`,
     * `incomplete` or `cancelled`.
     */
    data class Failed(val code: String, val message: String? = null) : OperationStatus
}

/** The confirmation a reattach preview carries, which [ReattachRequest] hands back unchanged. */
data class ReattachConfirmation(val build: String, val fingerprint: String)

/** A detached pack to put back on its source, and the confirmation of the preview the player agreed to. */
data class ReattachRequest(val packId: String, val confirmation: ReattachConfirmation)

/** Where the comparison of a detached pack against its source's build stands. */
sealed interface ReattachPreviewView {
    data object Comparing : ReattachPreviewView

    /** The comparison is in. Its [ReattachPreview.confirmation] is what a reattach takes. */
    data class Ready(val preview: ReattachPreview) : ReattachPreviewView

    data class Failed(val message: String) : ReattachPreviewView
}

/** What putting a detached pack back on its source would do to its files, as names relative to the pack's folder. */
data class ReattachPreview(
    /** The source build the pack lands on, as a player reads it. */
    val toVersion: String,
    /** How far it moves the game, graded as [PackUpdateView.Pending.compat] is. */
    val compat: String,
    /** Files that go. */
    val removed: List<String> = emptyList(),
    /** Files the build ships that are missing, which come back. */
    val restored: List<String> = emptyList(),
    /** Files on disk with other bytes, which the source's copy replaces. */
    val replaced: List<String> = emptyList(),
    /** Mods switched back on. */
    val turnedOn: List<String> = emptyList(),
    /** Optional mods switched off. */
    val turnedOff: List<String> = emptyList(),
    /** Files the player edited that the build changed too: theirs stays, the build's lands beside it. */
    val kept: List<String> = emptyList(),
    /** What the comparison could tell the player's edits from the build's by: `full`, `names-only`, `no-baseline`, or another word. */
    val basis: String,
    /** Mods that would not open to be judged. While any are left the reattach is refused. */
    val unreadable: List<String> = emptyList(),
    val confirmation: ReattachConfirmation,
)
