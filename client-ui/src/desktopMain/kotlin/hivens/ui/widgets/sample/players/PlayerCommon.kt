package hivens.ui.widgets.sample.players

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import hivens.ui.audio.AudioError
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.RepeatMode
import hivens.ui.audio.TrackInfo
import hivens.ui.audio.fileTitle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the player kinds share: the file dialog and the two strings every one of
 * them puts on screen.
 *
 * Written fresh rather than lifted out of the player these kinds replaced. Its
 * own copies were private to it and tangled with a layout that was on its way
 * out, and that widget is now gone.
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
                picked?.map { file -> Paths.get(file.path) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(onPicked)
            }
        }
    }
}

/**
 * The track's own title, the file's name until the tags land, and the invitation
 * to load something when nothing is loaded at all. An empty player that says
 * nothing reads as broken rather than as empty.
 *
 * The same name in every state, the failed one included. The fallback drops the
 * extension because that is what [TrackInfo] itself falls back to, and a file
 * named one way before its tags are read and another way after has renamed itself
 * under the reader. On a track that failed, that reads as a different file rather
 * than as the same one in trouble.
 */
internal fun playerTitle(state: PlaybackState, track: TrackInfo?, s: AppStrings): String = when (state) {
    is PlaybackState.Idle    -> s.audioPickTrack
    is PlaybackState.Ready   -> track?.title ?: fileTitle(state.file)
    is PlaybackState.Playing -> track?.title ?: fileTitle(state.file)
    is PlaybackState.Paused  -> track?.title ?: fileTitle(state.file)
    is PlaybackState.Error   -> track?.title ?: fileTitle(state.file)
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

/**
 * While nothing is loaded, the whole plane opens a file.
 *
 * Every kind here offers this, because an empty player that answers nowhere is a
 * dead end: several of them drop their artwork square as they narrow, and that
 * square was the only way in.
 *
 * A press and a published action rather than `clickable`, and the difference is
 * accessibility rather than taste. `clickable` merges the semantics of everything
 * beneath it, so an empty card collapsed into one node and its overflow button and
 * its artwork square stopped being reachable on their own. Declaring the action
 * without the merge leaves both standing, and a reader still finds the one thing
 * the empty card is for. It also draws no indication, which a whole card rippling
 * under the pointer was never asking for.
 *
 * Unconsumed only. A press one of those children took is not also an open, and
 * with the weaker test a click on the overflow of an empty card put its panel and
 * a file dialog on screen together.
 */
internal fun Modifier.openWhenEmpty(
    idle: Boolean,
    label: String,
    onPick: () -> Unit,
): Modifier {
    if (!idle) return this
    return this
        .pointerInput(onPick) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = true)
                    onPick()
                }
            }
        }
        .semantics { onClick(label = label) { onPick(); true } }
}

/**
 * Fills the slot, and puts an object of at most [maxSide] in the middle of it.
 *
 * Four of the kinds are drawn as one grid cell: a tile, a disc, a token, a
 * column for a rail. They are sized in the concept sheet and they mean nothing
 * stretched, so a slot's width is a ceiling rather than an instruction. Handed a
 * home-screen slot, `fillMaxWidth` plus a square aspect made a token seventeen
 * hundred points across and swallowed the page.
 *
 * A chain rather than a wrapper, deliberately: `wrapContentWidth` is what lets
 * the content measure smaller than the slot and sit centred in the rest, so the
 * cap costs no extra layout node and nothing inside the card has to know. What
 * a `BoxWithConstraints` inside it reads is already the capped width.
 */
internal fun Modifier.playerObject(maxSide: Dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxSide)
