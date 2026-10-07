package hivens.ui.widgets.players

import hivens.ui.audio.AudioError
import hivens.ui.audio.PlaybackState
import hivens.ui.audio.TrackInfo
import hivens.ui.audio.trackInfoFrom
import hivens.ui.i18n.EnglishStrings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name a player puts on screen for one file.
 *
 * What is pinned is not what the name is, it is that there is only one of it. A
 * track is named from three places at three different moments: the file while the
 * tags are still being read, the tags once they land, and the file again if the
 * playback fails. Two of those kept the extension and one dropped it, so one file
 * changed its name twice on the way through without anything about the file
 * changing, and a track that failed read as a different track rather than as the
 * same one in trouble.
 */
class PlayerTitleTest {

    private val s = EnglishStrings
    private val file: Path = Path.of("/music/03 - bus-stop.flac")

    /** Every state that has a file behind it, which is every state but Idle. */
    private val loadedStates = listOf(
        PlaybackState.Ready(file, positionMs = 0L, durationMs = 0L),
        PlaybackState.Playing(file, positionMs = 1_000L, durationMs = 200_000L),
        PlaybackState.Paused(file, positionMs = 1_000L, durationMs = 200_000L),
        PlaybackState.Error(file, AudioError.OpenFailed),
    )

    @Test
    fun `one file has one name, whatever the transport is doing`() {
        val names = loadedStates.map { playerTitle(it, track = null, s) }.toSet()
        assertEquals(setOf("03 - bus-stop"), names, "the name must not depend on the state")
    }

    @Test
    fun `tags that name nothing do not rename the file`() {
        // The two halves of the same moment: what is drawn while the decode thread
        // is still reading the container, and what is drawn once it has read it and
        // found no title. A file with no tags is most of a game music folder, so
        // this is the ordinary path rather than an edge of it.
        val ready = loadedStates.first()
        val beforeTags = playerTitle(ready, track = null, s)
        val afterTags = playerTitle(ready, track = trackInfoFrom(tags = emptyMap(), file = file), s)
        assertEquals(beforeTags, afterTags, "reading tags that name nothing must not rename the file")
    }

    @Test
    fun `a real title wins in every state, the failed one included`() {
        val track = TrackInfo(title = "Bus Stop")
        for (state in loadedStates) {
            assertEquals("Bus Stop", playerTitle(state, track, s), "in $state")
        }
    }

    @Test
    fun `nothing loaded asks for a file rather than naming one`() {
        assertEquals(s.audioPickTrack, playerTitle(PlaybackState.Idle, track = null, s))
    }

    @Test
    fun `a file with no extension keeps its whole name`() {
        val bare = Path.of("/music/untitled")
        assertEquals(
            "untitled",
            playerTitle(PlaybackState.Ready(bare, positionMs = 0L, durationMs = 0L), track = null, s),
        )
    }
}
