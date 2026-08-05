package hivens.ui.widgets.sample

import hivens.ui.audio.PlaybackState

/**
 * How far the loaded track has got, 0..1. Shared by the player card and the mini
 * control so both draw the same measure from the same state -- a surface showing
 * two transports must not disagree about where the track is.
 *
 * A state with nothing loaded, or a container that has not reported a duration
 * yet, reads as zero rather than as an unknown job: the bar belongs to a track
 * that exists and is at its start, not to work of unknown size.
 */
internal fun progressFraction(state: PlaybackState): Float = when (state) {
    is PlaybackState.Playing -> safeFraction(state.positionMs, state.durationMs)
    is PlaybackState.Paused  -> safeFraction(state.positionMs, state.durationMs)
    is PlaybackState.Ready   -> safeFraction(state.positionMs, state.durationMs)
    else                     -> 0f
}

private fun safeFraction(position: Long, duration: Long): Float =
    if (duration <= 0L) 0f else (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

/** "1:04 / 3:58", or empty while the duration is unknown. */
internal fun timelineLabel(state: PlaybackState): String {
    val (pos, dur) = when (state) {
        is PlaybackState.Playing -> state.positionMs to state.durationMs
        is PlaybackState.Paused  -> state.positionMs to state.durationMs
        is PlaybackState.Ready   -> 0L to state.durationMs
        else                     -> return ""
    }
    if (dur <= 0L) return ""
    return "${formatMs(pos)} / ${formatMs(dur)}"
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
