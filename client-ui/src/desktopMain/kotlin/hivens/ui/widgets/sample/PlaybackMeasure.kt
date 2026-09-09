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

/** Where the track is now, or empty when nothing is loaded. */
internal fun elapsedLabel(state: PlaybackState): String = when (state) {
    is PlaybackState.Playing -> formatMs(state.positionMs)
    is PlaybackState.Paused  -> formatMs(state.positionMs)
    is PlaybackState.Ready   -> formatMs(0L)
    else                     -> ""
}

/** How long the track is, or empty while the container has not said. */
internal fun totalLabel(state: PlaybackState): String {
    val dur = when (state) {
        is PlaybackState.Playing -> state.durationMs
        is PlaybackState.Paused  -> state.durationMs
        is PlaybackState.Ready   -> state.durationMs
        else                     -> 0L
    }
    return if (dur <= 0L) "" else formatMs(dur)
}

/** The loaded track's length in milliseconds, 0 when it is not known yet. */
internal fun durationMsOf(state: PlaybackState): Long = when (state) {
    is PlaybackState.Playing -> state.durationMs
    is PlaybackState.Paused  -> state.durationMs
    is PlaybackState.Ready   -> state.durationMs
    else                     -> 0L
}

/**
 * A timecode, with an hours field only once there are hours.
 *
 * It used to be minutes and seconds alone, so an hour printed as `60:00` and a
 * ninety-minute file as `90:00`: readable as a number, wrong as a clock, and the
 * kind of thing that never shows up until somebody loads a set or a podcast. Hours
 * are not padded and are omitted entirely below one, so the ordinary four-minute
 * track still reads `4:55` rather than `0:04:55`.
 *
 * Negative input is clamped rather than formatted: a position below zero is a bug
 * upstream, and `-1:-5` on a card is a worse way to learn about it than a clock
 * parked at the start.
 */
internal fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
