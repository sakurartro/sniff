package app.tgkiku

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

object Shared {
    private var exo: ExoPlayer? = null
    // shortcut: queue lives in Vm; notification next/prev work only while the Vm is alive, move the queue into the service to fix
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null

    fun player(c: Context): ExoPlayer = exo ?: ExoPlayer.Builder(c.applicationContext).build().also { exo = it }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = object : ForwardingPlayer(Shared.player(this)) {
            override fun getAvailableCommands() = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build()
            override fun isCommandAvailable(command: Int) =
                command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command)
            override fun seekToNext() { Shared.onNext?.invoke() }
            override fun seekToPrevious() { Shared.onPrev?.invoke() }
        }
        session = MediaSession.Builder(this, p).build()
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        Shared.player(this).release()
        session = null
        super.onDestroy()
    }
}
