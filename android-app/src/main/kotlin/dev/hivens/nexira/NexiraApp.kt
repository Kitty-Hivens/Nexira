package dev.hivens.nexira

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import dev.hivens.nexira.host.androidHostModule
import hivens.launcher.di.launcherModules
import hivens.ui.androidUiModule
import hivens.ui.platform.AndroidAppContext
import org.koin.core.context.startKoin
import hivens.ui.audio.AudioPlayer
import hivens.ui.widgets.services.MusicPlayerService
import hivens.ui.widgets.services.MusicPlayerServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher

/** Owns the one player for the process, so playback outlives any screen that shows it. */
class NexiraApp : Application() {

    /** Built on first use: the process the game runs in has no player to build. */
    val playback: PlaybackHost by lazy { PlaybackHost(this) }

    override fun onCreate() {
        super.onCreate()
        // Every process of the application starts this class, and the game's has no use
        // for the launcher's graph: it would only pay for the databases it opens.
        if (Application.getProcessName() == packageName) {
            AndroidAppContext.install(this)
            startKoin { modules(launcherModules() + androidHostModule(this@NexiraApp) + androidUiModule(playback.service)) }
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
