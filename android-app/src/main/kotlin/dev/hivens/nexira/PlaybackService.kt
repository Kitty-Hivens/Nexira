package dev.hivens.nexira

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps the player's session published, so the system can show a notification with
 * transport controls and playback can go on with the screen off.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: MusicPlayerPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val host = (application as NexiraApp).playback
        val adapter = MusicPlayerPlayer(host.service, host.scope)
        player = adapter
        session = MediaSession.Builder(this, adapter)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    // What the home screen icon starts, so a tap brings the launcher's task forward instead of a second copy.
                    packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, NexiraActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
