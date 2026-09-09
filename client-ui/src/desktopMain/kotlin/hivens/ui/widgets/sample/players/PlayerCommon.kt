package hivens.ui.widgets.sample.players

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.ui.audio.AudioError
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxCycleState
import hivens.ui.utils.pickFiles
import hivens.ui.utils.rememberFileDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.path
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the player kinds share: the file dialog and the two strings every one of
 * them puts on screen.
 *
 * Deliberately not shared with the older `MusicPlayerWidget`. Its own copies are
 * private to it and tangled with a layout that is on its way out, so reaching
 * into them would couple the new shapes to the one they are meant to replace.
 */

/**
 * Containers Skinema decodes as audio. Video containers are absent on purpose:
 * a file the picker accepts and the player then cannot voice is worse than a
 * file the picker never offered.
 */
internal val AUDIO_EXTENSIONS = listOf(
    "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "aiff", "aif", "au",
)

/**
 * A ready-to-call opener that accepts several files at once.
 *
 * Multiple selection rather than one, and no second "add to queue" verb beside it:
 * picking one file is a queue of one, picking five is a queue of five, and the
 * user does not have to decide which kind of opening they meant before they have
 * seen the folder. The dialog settings are remembered so the picker comes back to
 * the folder it was last used in rather than to the home directory every time,
 * which for a music folder several levels deep is the difference between one click
 * and six.
 *
 * A cancelled dialog reports nothing rather than an empty list: replacing a queue
 * with nothing because somebody pressed Escape is not what the press meant.
 */
@Composable
internal fun rememberAudioFilesPicker(
    scope: CoroutineScope,
    onPicked: (List<Path>) -> Unit,
): () -> Unit {
    val s = LocalStrings.current
    val settings = rememberFileDialogSettings(s.audioPickTrack)
    return remember(settings, onPicked) {
        {
            scope.launch {
                val picked = pickFiles(
                    type     = FileKitType.File(extensions = AUDIO_EXTENSIONS),
                    settings = settings,
                )
                picked?.mapNotNull { file -> file.path?.let(Paths::get) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(onPicked)
            }
        }
    }
}

/**
 * The track's own title, the file name until the tags land, and the invitation
 * to load something when nothing is loaded at all. An empty player that says
 * nothing reads as broken rather than as empty.
 */
internal fun playerTitle(state: PlaybackState, track: TrackInfo?, s: AppStrings): String = when (state) {
    is PlaybackState.Idle    -> s.audioPickTrack
    is PlaybackState.Ready   -> track?.title ?: state.file.name
    is PlaybackState.Playing -> track?.title ?: state.file.name
    is PlaybackState.Paused  -> track?.title ?: state.file.name
    is PlaybackState.Error   -> state.file.name
}

/**
 * What the transport is doing, for the line under the title when the file names
 * no artist. With a real artist to show, the state is already on screen as the
 * shape of the play control, so the credit wins.
 */
internal fun playerStatus(state: PlaybackState, s: AppStrings): String = when (state) {
    is PlaybackState.Idle    -> s.audioFormatHint
    is PlaybackState.Ready   -> s.audioStatusReady
    is PlaybackState.Playing -> s.audioStatusPlaying
    is PlaybackState.Paused  -> s.audioStatusPaused
    is PlaybackState.Error   -> playerErrorText(state.reason, s)
}

internal fun playerErrorText(reason: AudioError, s: AppStrings): String = when (reason) {
    AudioError.UnsupportedFormat -> s.audioErrorUnsupported
    AudioError.OpenFailed        -> s.audioErrorOpenFailed
    AudioError.DeviceBusy        -> s.audioErrorDeviceBusy
    AudioError.PlaybackFailed    -> s.audioErrorPlaybackFailed
}

/**
 * The ring the repeat control steps through, in the order it steps: off, then the
 * narrower loop, then the wider one. Off leads because it is where the ring starts
 * and the state the control reads as inactive in.
 */
internal val REPEAT_ORDER = listOf(RepeatMode.Off, RepeatMode.One, RepeatMode.Queue)

internal fun repeatIndex(mode: RepeatMode): Int = REPEAT_ORDER.indexOf(mode).coerceAtLeast(0)

/** The answer alone, for a place that has already named the question. */
internal fun repeatAnswer(mode: RepeatMode, s: AppStrings): String = when (mode) {
    RepeatMode.Off   -> s.audioRepeatOff
    RepeatMode.One   -> s.audioRepeatOne
    RepeatMode.Queue -> s.audioRepeatQueue
}

/**
 * The ring as the cycle control wants it. The labels are qualified -- "Repeat: one
 * track" -- because they land on a tooltip over a glyph, where the answer on its
 * own leaves the reader to guess what it answers.
 */
internal fun repeatStates(s: AppStrings): List<NxCycleState> = REPEAT_ORDER.map { mode ->
    NxCycleState(icon = repeatIcon(mode), label = "${s.audioRepeat}: ${repeatAnswer(mode, s)}")
}

private fun repeatIcon(mode: RepeatMode): IconKey = when (mode) {
    RepeatMode.Off   -> NxIcon.Repeat
    RepeatMode.One   -> NxIcon.RepeatOne
    // The queue's own glyph rather than a third arrow: what changes between One and
    // Queue is the size of the thing being looped, not the looping.
    RepeatMode.Queue -> NxIcon.QueueMusic
}
