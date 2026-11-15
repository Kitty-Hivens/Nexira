package hivens.catalogue.api

/** Why an install did not run at all. */
enum class InstallRefusal {
    /** The pack's game is running. */
    GameRunning,

    /** Other work holds the pack. */
    Busy,
}

/** Why an install left a project out. */
enum class SkipReason {
    /** A dependency the pack already carries. */
    Present,

    /** The very build asked for is already in the pack. */
    AlreadyInstalled,

    /** No build of the project runs on the pack. */
    NoBuild,

    /** The catalogue could not be asked about it. */
    LookupFailed,

    /** The build has no place in a pack. */
    NotPlaceable,

    /** The pack does not take this kind of file from the player. */
    NotAllowed,

    /** Another file already holds the name. */
    NameTaken,

    /** The download or the move did not go through. */
    Failed,

    /** Not tried, because the build that was asked for did not land. */
    NotAttempted,

    /** A required dependency deeper than an install walks. */
    TooDeep,

    /** The file it would replace is one the pack put there. */
    PackOwned,

    /** A required dependency the pack carries only switched off. */
    SwitchedOff,
}

/** Why an install from a result or into a chosen pack did not land. */
sealed interface InstallProblem {
    /** No build of the project runs on the pack. */
    data object NoBuild : InstallProblem

    /** The install did not run. */
    data class Refused(val reason: InstallRefusal) : InstallProblem

    /** It ran and the build that was asked for did not land. */
    data object NotLanded : InstallProblem

    /** It ran and left the build that was asked for out, for [reason]. */
    data class LeftOut(val reason: SkipReason) : InstallProblem

    /** The source the project was found in cannot install it yet, so nothing was asked of any other. */
    data object SourceCannotInstall : InstallProblem
}

/** What an install that asks where is for, as its dialog names it. */
data class InstallSubject(
    val projectId: String,
    val title: String,
    val iconUrl: String? = null,
    val author: String = "",
    /**
     * The id of the project source it was found in ([SearchResults.projectSource]), null
     * for a project page, whose project is the one source the launcher installs from.
     */
    val source: String? = null,
)

/** How one pack answers whether the project can go into it. */
enum class CandidateVerdict {
    /** A build of it runs there, and an install would put it in. */
    Fits,

    /** The pack already carries it. */
    Present,

    /** No build of it runs on the pack's game version and loader. */
    NoBuild,

    /** The pack does not take this kind of file from the player. */
    NotTaken,

    /** The pack's folder could not be read, so nothing is known about it. */
    Unknown,
}

/** One pack the project could go into, or could not, and why. */
data class InstallCandidate(
    val packId: String,
    val name: String,
    /** What the pack runs, blank where it has not said. */
    val mcVersion: String,
    val loader: String,
    val verdict: CandidateVerdict,
)

/** Where one pack's install stands inside the dialog. */
sealed interface CandidateState {
    data object Working : CandidateState

    /** Landed, with [missing] required dependencies it went without. */
    data class Done(val missing: Int = 0) : CandidateState

    data class Failed(val problem: InstallProblem) : CandidateState
}

/** Where making a new pack for the project stands. */
sealed interface NewPackState {
    data object Idle : NewPackState

    /** Making the pack, [done] of [total] steps, [total] 0 while it is not known. */
    data class Creating(val done: Int = 0, val total: Int = 0) : NewPackState

    /** The pack is there, and the project is going into it. */
    data object Installing : NewPackState

    data class Done(val packId: String, val packName: String, val missing: Int = 0) : NewPackState

    /** It did not work. [message] is the failure's own words, blank where there were none. */
    data class Failed(val message: String = "") : NewPackState
}

/** A pack to make for the project and install it into: its name, game version and loader, null for none. */
data class NewPackRequest(val name: String, val mcVersion: String, val loader: String?)

/**
 * The window's install that asks where: the project, every pack with its answer, and
 * what came of the installs started from it. One at a time per window, opened with
 * [CatalogueKeys.ChooseInstall] and closed with [CatalogueKeys.DismissInstall].
 */
data class InstallChoiceView(
    val subject: InstallSubject,
    /** Every pack with its answer, those that fit first, or null while they are being read. */
    val candidates: List<InstallCandidate>? = null,
    /** The project's build list could not be read, so no pack has an answer. */
    val failed: Boolean = false,
    /** The source the project was found in cannot install it yet, so no pack was asked. */
    val unsupported: Boolean = false,
    /** The project's builds, for what a pack made for it could run. */
    val listing: List<ProjectBuild> = emptyList(),
    /** Installs started into a pack, by pack id. A pack nothing was started for is absent. */
    val rows: Map<String, CandidateState> = emptyMap(),
    val newPack: NewPackState = NewPackState.Idle,
)
