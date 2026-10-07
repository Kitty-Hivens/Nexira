package hivens.ui.audio

import hivens.ui.widgets.services.MusicPlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.nio.file.Path

/**
 * Who owns playback, behind one contract.
 *
 * Normally the music player. While the wallpaper is linked, the clip on the wall,
 * so every player widget and the desktop's media session address that instead
 * without any of them being told. That indirection is the whole feature: the
 * widgets were pointed at the engine until the contract left the widget registry,
 * and pointing them at an interface is what made a second owner expressible at all.
 *
 * ## The music does not lose its queue, it stops being asked
 *
 * Handing the session over does not touch what the music player holds. Its queue
 * and its position are persisted and its engine is its own to release, so
 * unlinking comes back to the track that was there. What changes is which object
 * answers, which is why this routes rather than copies: two copies of "what is
 * playing" is the state the whole media stack was built to avoid.
 *
 * ## Why flows are flattened rather than mirrored
 *
 * A mirror would need this class to subscribe, remember and re-emit, and to be
 * correct about the order of a handover. Flattening says the same thing once: the
 * value is whatever the owner says, and a change of owner is a change of source.
 * Eagerly, because the media session reads these with no composition behind it and
 * a lazily-started flow would publish nothing until a widget happened to mount.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRouter(
    private val scope: CoroutineScope,
    private val music: MusicPlayerService,
    private val wallpaper: WallpaperSession,
) : MusicPlayerService {

    private val _wallpaperOwns = MutableStateFlow(false)

    /** True while the wallpaper is the session. Read by a control that says so. */
    val wallpaperOwns: StateFlow<Boolean> = _wallpaperOwns.asStateFlow()

    /**
     * Hands the session over, or takes it back.
     *
     * One caller, the background, and it says yes only when the wallpaper both
     * sounds and is linked. Idempotent, because the background re-renders for
     * reasons that have nothing to do with this.
     */
    fun setWallpaperOwns(owns: Boolean) {
        _wallpaperOwns.value = owns
    }

    private val owner: StateFlow<MusicPlayerService> =
        _wallpaperOwns
            .map { if (it) wallpaper else music }
            .stateIn(scope, SharingStarted.Eagerly, music)

    private fun <T> route(pick: (MusicPlayerService) -> StateFlow<T>): StateFlow<T> =
        owner.flatMapLatest(pick).stateIn(scope, SharingStarted.Eagerly, pick(owner.value).value)

    override val state: StateFlow<PlaybackState> = route { it.state }
    override val volume: StateFlow<Float> = route { it.volume }
    override val track: StateFlow<TrackInfo?> = route { it.track }
    override val queue: StateFlow<List<Path>> = route { it.queue }
    override val queueIndex: StateFlow<Int> = route { it.queueIndex }
    override val repeat: StateFlow<RepeatMode> = route { it.repeat }

    // Every verb goes to whoever owns the session at the moment it is pressed. Read
    // per call rather than captured, because a handover between a widget composing
    // and a person clicking is exactly the window a captured reference gets wrong.
    private val current: MusicPlayerService get() = owner.value

    override fun play() = current.play()

    override fun pause() = current.pause()

    override fun stop() = current.stop()

    override fun seek(positionMs: Long) = current.seek(positionMs)

    override fun setVolume(level: Float) = current.setVolume(level)

    override fun setRepeat(mode: RepeatMode) = current.setRepeat(mode)

    /**
     * The queue verbs, which the wallpaper answers by doing nothing.
     *
     * Routed rather than redirected to the music player. Opening a file while the
     * wall owns the session would start a track nobody could see the transport of,
     * because the transport on screen is describing the wall, and the surprise is
     * worse than a button that does nothing.
     */
    override fun open(files: List<Path>) = current.open(files)

    override fun enqueue(files: List<Path>) = current.enqueue(files)

    override fun playAt(index: Int) = current.playAt(index)

    override fun skipToNext() = current.skipToNext()

    override fun skipToPrevious() = current.skipToPrevious()

    override fun removeAt(index: Int) = current.removeAt(index)
}
