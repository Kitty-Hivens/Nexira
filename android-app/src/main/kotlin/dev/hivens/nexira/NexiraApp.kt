package dev.hivens.nexira

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import dev.hivens.nexira.host.androidHostModule
import hivens.launcher.di.launcherModules
import hivens.ui.androidUiModule
import hivens.ui.audio.AudioPlayer
import hivens.ui.platform.AndroidAppContext
import hivens.ui.prepareLook
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import kotlin.concurrent.thread

/** Owns the one player for the process, so playback outlives any screen that shows it. */
class NexiraApp : Application() {

    /** Built on first use: the process the game runs in has no player to build. */
    val playback: PlaybackHost by lazy { PlaybackHost(this) }

    /**
     * The launcher's graph, in the launcher's own process only. Held here and handed
     * to the activity's composition, never registered in Koin's global context.
     */
    lateinit var koin: KoinApplication
        private set

    override fun onCreate() {
        super.onCreate()
        // Every process of the application starts this class, and the game's has no use
        // for the launcher's graph: it would only pay for the databases it opens.
        if (Application.getProcessName() == packageName) {
            AndroidAppContext.install(this)
            koin = koinApplication { modules(launcherModules() + androidHostModule(this@NexiraApp) + androidUiModule(playback.service)) }
            // What the shell is drawn and worded in is made by the modules on its first read, the
            // language module reading its tables cold for it. Made here, off the main thread, so the
            // first frame finds it made, or waits on it rather than making it there.
            val graph = koin
            thread(name = "nexira-look", isDaemon = true) {
                val _ = runCatching { prepareLook(graph) }
            }
        }
    }
}

/**
 * The player and the thread its engine lives on.
 *
 * One thread for every engine call, and the same thread's looper is what the player's
 * dispatcher is built from, which is how a Media3 player's single thread rule and the
 * player's own confinement to one lane stay the same rule.
 */
class PlaybackHost(context: Context) {
    private val thread = HandlerThread("nexira-media").also { it.start() }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val audio = AudioPlayer(
        scope = scope,
        engines = Media3Engines(context.applicationContext, thread.looper),
        artworkDecoder = { decodeArtwork(it) },
        engine = Handler(thread.looper).asCoroutineDispatcher("nexira-media"),
    )

    val service: MusicPlayerService = MusicPlayerServiceImpl(audio)
}
