package hivens.core.launch

/**
 * Why a launch control cannot start a game right now.
 *
 * The control used to go grey and say nothing, so a player looking at a dead Play
 * had no way to tell an update in progress from a missing sign-in from another game
 * holding the launcher. Each case here is one the control can name, and some of them
 * one it can act on.
 *
 * Only for a control whose own pack is idle. A launch in flight or a game running is
 * [LaunchControlMode.Wait] or [LaunchControlMode.Stop], and a running game can always
 * be stopped, whatever else is true.
 */
sealed interface LaunchBlock {
    /** The files are being rewritten. Transient: the control comes back when [work] ends. */
    data class Busy(val work: InstanceWork) : LaunchBlock

    /** The instance directory is gone. Nothing will come back by waiting. */
    data object Missing : LaunchBlock

    /** Another pack's launch or game holds the launcher, which runs one at a time. */
    data object OtherGameRunning : LaunchBlock

    /**
     * Nobody to play as: no account signed in and no offline name. Unlike the others
     * the control can do something about this, by taking the player to sign in.
     */
    data object NoIdentity : LaunchBlock
}

/**
 * The block on launching one pack, or null when nothing stands in the way.
 *
 * Ordered by what the player should hear first. Work in progress is named before
 * anything else because it is the reason that will resolve by itself, and a missing
 * sign-in comes last because signing in would not help while any of the others holds.
 */
fun launchBlockFor(
    hasIdentity: Boolean,
    work: InstanceWork?,
    instancePresent: Boolean,
    otherLaunchActive: Boolean,
): LaunchBlock? = when {
    work != null -> LaunchBlock.Busy(work)
    !instancePresent -> LaunchBlock.Missing
    otherLaunchActive -> LaunchBlock.OtherGameRunning
    !hasIdentity -> LaunchBlock.NoIdentity
    else -> null
}
