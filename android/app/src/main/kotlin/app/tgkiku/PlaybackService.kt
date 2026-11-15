package app.tgkiku

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import uniffi.tgkiku_ffi.App

object Shared {
    private var exo: ExoPlayer? = null
    lateinit var core: App // read by TgDataSource, set by Vm
    // shortcut: queue lives in Vm; notification next/prev work only while the Vm is alive, move the queue into the service to fix
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null

    @OptIn(UnstableApi::class)
    fun player(c: Context): ExoPlayer = exo ?: ExoPlayer.Builder(c.applicationContext)
        // tgkiku:// goes to TgDataSource, file:// to the default file source; CBR seeking lets MP3 without a seek table be scrubbed
        .setMediaSourceFactory(DefaultMediaSourceFactory(
            DefaultDataSource.Factory(c.applicationContext, DataSource.Factory { TgDataSource() }),
            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
        ))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build().also { exo = it }
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
        session = MediaSession.Builder(this, p).build().also { addSession(it) }
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
