package dev.hivens.nexira

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import hivens.ui.audio.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Plays files through the shared player, to see the shared code run on a phone.
 *
 * Not the launcher. It exists so each shared module can be exercised on the device
 * before anything is built on top of it, and it logs what the player reports under
 * the tag NexiraProbe so a run can be read from logcat without touching the screen.
 * The player belongs to the application, so leaving this screen does not stop it.
 *
 * Intent extras: `files` is a comma separated list of paths to queue, `autoplay`
 * starts playing once they are queued.
 */
class ProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = (application as NexiraApp).playback
        val player = host.service

        val status = TextView(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("play") { player.play() })
            addView(button("pause") { player.pause() })
            addView(button("next") { player.skipToNext() })
            addView(button("+10s") { player.seek(positionOf(player.state.value) + 10_000L) })
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 96, 32, 32)
                addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(bar)
            },
        )

        scope.launch(Dispatchers.Main) {
            var last = ""
            player.state.collect { state ->
                val line = describe(state)
                status.text = line
                // Once a change of kind or a new second, so a run reads as a story.
                val key = state::class.simpleName + ":" + (positionOf(state) / 1000L)
                if (key != last) {
                    last = key
                    Log.i(TAG, line)
                }
            }
        }
        scope.launch(Dispatchers.Main) {
            player.track.collect {
                Log.i(TAG, "track: ${it?.title} / ${it?.artist} / ${it?.album} art=${it?.artwork?.width}x${it?.artwork?.height}")
            }
        }

        // Connecting is what binds the service and publishes the session; the screen
        // itself drives the player directly.
        val connecting = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
        connecting.addListener({ controller = connecting.get() }, mainExecutor)

        // Only for the start of this screen: it is made again when the system took it back, and a scenario that ran
        // once must not run a second time on the intent it was started with.
        val fresh = savedInstanceState == null
        if (fresh && intent.getBooleanExtra("vault", false)) Thread { VaultProbe.run(applicationContext) }.start()
        if (fresh && intent.getBooleanExtra("graph", false)) Thread { runCatching { GraphProbe.run() }.onFailure { Log.e(TAG, "graph probe failed", it) } }.start()
        intent.getStringExtra("host")?.takeIf { fresh }?.let { scenario -> Thread { runCatching { HostProbe.run(applicationContext, scenario, intent.getStringExtra("jdk")) }.onFailure { Log.e(TAG, "host probe failed", it) } }.start() }
        if (fresh && intent.getBooleanExtra("xodus", false)) Thread { runCatching { XodusProbe.run(applicationContext) }.onFailure { Log.e(TAG, "xodus probe failed", it) } }.start()

        val files = intent.getStringExtra("files")?.split(',')?.map { Path.of(it.trim()) }
        if (files != null) {
            player.open(files)
            if (intent.getBooleanExtra("autoplay", false)) player.play()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        controller?.release()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun positionOf(state: PlaybackState): Long = when (state) {
        is PlaybackState.Playing -> state.positionMs
        is PlaybackState.Paused -> state.positionMs
        is PlaybackState.Ready -> state.positionMs
        else -> 0L
    }

    private fun describe(state: PlaybackState): String = when (state) {
        PlaybackState.Idle -> "idle"
        is PlaybackState.Ready -> "ready ${state.file.fileName} ${state.positionMs}/${state.durationMs}"
        is PlaybackState.Playing -> "playing ${state.file.fileName} ${state.positionMs}/${state.durationMs}"
        is PlaybackState.Paused -> "paused ${state.file.fileName} ${state.positionMs}/${state.durationMs}"
        is PlaybackState.Error -> "error ${state.file.fileName} ${state.reason}"
    }

    private companion object {
        const val TAG = "NexiraProbe"
    }
}
