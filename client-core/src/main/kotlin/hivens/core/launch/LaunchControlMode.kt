package hivens.core.launch

/**
 * What a launch control can do right now.
 *
 * Every surface that offers a launch reads the same [LaunchState] and used to
 * decide this for itself -- the dashboard panel, the pack hero, the home pill,
 * the launch tile, the CLI, each with its own `when`. They drifted, as parallel
 * copies do: the tile never learned that a running game can be stopped, so it
 * simply went grey for as long as the game was up.
 *
 * The mode is the decision, not the presentation. It says a stop is available,
 * never what the button says, which icon it wears, or whether the surface draws
 * a button at all -- a widget answers those for itself.
 */
enum class LaunchControlMode {
    /** Nothing is running: the control starts a launch. */
    Play,

    /** A launch is in flight. Abortable, but not a game to stop yet. */
    Wait,

    /** The game is up: the control stops it. */
    Stop,
}

/**
 * The mode this state offers.
 *
 * [LaunchState.Error] maps to [Play] rather than to a mode of its own: the next
 * attempt overwrites the error, and the failure is reported through the
 * notification path instead of through the control.
 */
fun LaunchState.controlMode(): LaunchControlMode = when (this) {
    is LaunchState.Idle        -> LaunchControlMode.Play
    is LaunchState.Error       -> LaunchControlMode.Play
    is LaunchState.Prepare     -> LaunchControlMode.Wait
    is LaunchState.Downloading -> LaunchControlMode.Wait
    is LaunchState.GameRunning -> LaunchControlMode.Stop
}
