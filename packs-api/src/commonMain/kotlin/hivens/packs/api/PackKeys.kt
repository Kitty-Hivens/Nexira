package hivens.packs.api

import hivens.widget.model.CommandKey
import hivens.widget.model.SourceKey

/**
 * Every key the packs module answers: the id as a constant, for `needs` and for the
 * document, and the typed key a widget reads or fires.
 *
 * All of them live at the app level, because the packs are the app's: two windows
 * show one library. They exist only where a document names the packs module, and a
 * widget that needs one stands misplaced anywhere else.
 *
 * Commands are fire-and-forget. What came of one is read from a source: a launch
 * from [Launches] and [Launch], an install from [Installs], an update, a repair, a
 * reattach or a delete from [Operations].
 */
object PackKeys {
    const val LIBRARY_ID: String = "packs.library"
    const val LAUNCHES_ID: String = "packs.launches"
    const val LAUNCH_ID: String = "launch.state"
    const val INSTALLS_ID: String = "packs.installs"
    const val UPDATES_ID: String = "packs.updates"
    const val OPERATIONS_ID: String = "packs.operations"
    const val REATTACH_PREVIEWS_ID: String = "packs.reattach.previews"

    const val LAUNCH_PACK_ID: String = "pack.launch"
    const val STOP_PACK_ID: String = "pack.stop"
    const val INSTALL_PACK_ID: String = "pack.install"
    const val DISMISS_INSTALL_ID: String = "pack.install.dismiss"
    const val CANCEL_INSTALL_ID: String = "pack.install.cancel"
    const val UPDATE_PACK_ID: String = "pack.update"
    const val DELETE_PACK_ID: String = "pack.delete"
    const val PREVIEW_REATTACH_ID: String = "pack.reattach.preview"
    const val FORGET_REATTACH_PREVIEW_ID: String = "pack.reattach.preview.forget"
    const val REATTACH_PACK_ID: String = "pack.reattach"

    /** Every pack installed, in the order the store keeps them. */
    val Library: SourceKey<List<PackView>> = SourceKey(LIBRARY_ID)

    /**
     * Every pack with a launch in flight and where it stands, by pack id. A launch
     * that failed stays as [PackLaunch.Failed] until that pack is launched again.
     * Moved by [LaunchPack] and [StopPack].
     */
    val Launches: SourceKey<Map<String, PackLaunch>> = SourceKey(LAUNCHES_ID)

    /** What the one launch the launcher runs at a time is doing, from preparing to the game's exit. */
    val Launch: SourceKey<LaunchView> = SourceKey(LAUNCH_ID)

    /** Every pack install there is, running or finished and not yet dismissed, by its key. Started by [InstallPack]. */
    val Installs: SourceKey<Map<String, InstallView>> = SourceKey(INSTALLS_ID)

    /**
     * What the launcher last learned about a newer build of each pack, by pack id. A
     * pack it has not looked at is absent. [PackUpdateView.Pending] is an update that
     * is waiting for the player.
     */
    val Updates: SourceKey<Map<String, PackUpdateView>> = SourceKey(UPDATES_ID)

    /**
     * The last long operation on each pack, by pack id: an update, a repair, a
     * reattach or a delete, while it runs and how it ended. A pack nothing was done
     * to this run is absent. The outcome of [UpdatePack], [DeletePack] and
     * [ReattachPack], which give none themselves.
     */
    val Operations: SourceKey<Map<String, PackOperationView>> = SourceKey(OPERATIONS_ID)

    /**
     * The latest reattach preview of each detached pack that asked for one, by pack
     * id, asked for with [PreviewReattach]. When an operation on the pack starts or
     * ends its preview is measured again, and reads as comparing while that
     * operation runs. A preview goes a while after it answered if nobody asks again.
     * A reattach is refused unless the pack still measures as the confirmed preview
     * said, so a preview older than the files can at worst be refused, never applied.
     */
    val ReattachPreviews: SourceKey<Map<String, ReattachPreviewView>> = SourceKey(REATTACH_PREVIEWS_ID)

    /**
     * Starts the game of the pack with this id as the launcher's session, or as the
     * offline name when nobody is signed in. Refused while the startup sign-in is
     * still finding out who the launcher is. A pack that is not installed fails out
     * loud. Paired with [Launches].
     */
    val LaunchPack: CommandKey<String> = CommandKey(LAUNCH_PACK_ID)

    /** Asks the game of the pack with this id to end. */
    val StopPack: CommandKey<String> = CommandKey(STOP_PACK_ID)

    /**
     * Installs one build of a catalogue pack, looked up in its source by
     * [InstallRequest]. Its progress is in [Installs]. A request the source cannot
     * answer installs nothing and is logged.
     */
    val InstallPack: CommandKey<InstallRequest> = CommandKey(INSTALL_PACK_ID)

    /** Forgets the finished install with this key. */
    val DismissInstall: CommandKey<String> = CommandKey(DISMISS_INSTALL_ID)

    /** Stops the running install with this key and removes what it wrote. */
    val CancelInstall: CommandKey<String> = CommandKey(CANCEL_INSTALL_ID)

    /** Moves the pack with this id to its latest build. The outcome is in [Operations]. */
    val UpdatePack: CommandKey<String> = CommandKey(UPDATE_PACK_ID)

    /**
     * Deletes the pack with this id and its files. Refused while its game runs or
     * other work holds it. The outcome is in [Operations].
     */
    val DeletePack: CommandKey<String> = CommandKey(DELETE_PACK_ID)

    /**
     * Measures the detached pack with this id against its source's build: what a
     * reattach would remove and bring back. The answer lands in [ReattachPreviews].
     */
    val PreviewReattach: CommandKey<String> = CommandKey(PREVIEW_REATTACH_ID)

    /**
     * Drops the reattach preview of the pack with this id, stopping one still being
     * measured and letting go of what it kept for the reattach. For whoever asked for
     * it with [PreviewReattach] and no longer shows it.
     */
    val ForgetReattachPreview: CommandKey<String> = CommandKey(FORGET_REATTACH_PREVIEW_ID)

    /**
     * Puts a detached pack back on the source it was detached from and moves it to
     * the source's build the way an update does. Carries the confirmation of the
     * preview the player was shown, from [ReattachPreviews], and is refused,
     * touching nothing, when the pack no longer measures as that preview said.
     * Does nothing for a pack that was never detached, and is refused while its
     * game runs or other work holds it. The outcome is in [Operations].
     *
     * What the confirmation proves is that a preview was measured and that the pack
     * still measures the same, not that a person read it. A widget could hand on a
     * preview it never showed. That is accepted: the launcher's own surfaces show
     * the list before they ask, and a widget is the player's own choice to install.
     */
    val ReattachPack: CommandKey<ReattachRequest> = CommandKey(REATTACH_PACK_ID)
}
