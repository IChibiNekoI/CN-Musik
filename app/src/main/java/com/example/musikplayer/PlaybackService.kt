package com.example.musikplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private companion object {
        const val FADE_STEPS = 10
        const val FADE_STEP_MS = 3L
    }

    private var session: MediaSession? = null
    private val gainProcessor = GainAudioProcessor()
    private var autoGain: AutoGainController? = null
    private var widgets: WidgetUpdater? = null

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Auto-Gain sitzt als Audio-Prozessor in der Ausgabe des Players
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .build()
                // Der Player meldet hier, dass das Audio eines neuen Titels beginnt: Die Auto-Gain-Verstärkung
                // wechselt dann genau an dieser Stelle, schon für das erste Sample des neuen Titels
                return object : ForwardingAudioSink(sink) {
                    // Lautstärke, die der Player eingestellt hat (Nutzer-Lautstärke mal Audiofokus), und Wiedergabezustand
                    private var userVolume = 1f
                    private var playing = false

                    override fun handleDiscontinuity() {
                        gainProcessor.onStreamBoundary()
                        super.handleDiscontinuity()
                    }

                    override fun setVolume(volume: Float) {
                        userVolume = volume
                        super.setVolume(volume)
                    }

                    override fun play() {
                        playing = true
                        super.play()
                    }

                    override fun pause() {
                        playing = false
                        super.pause()
                    }

                    /**
                     * Sprung oder Titelwechsel per Taste: Der Player verwirft den Rest im AudioTrack sofort, die Welle
                     * wird mitten in der Schwingung abgeschnitten, und das knackt. Deshalb wird der Ton hier, auf dem
                     * Wiedergabe-Thread, in etwa 30 ms auf null gezogen, bevor geleert wird. Das hängt weder von der
                     * Oberfläche noch vom Puffer ab. Beim Pausieren oder ohne Daten im Puffer passiert nichts.
                     */
                    override fun flush() {
                        val faded = playing && hasPendingData()
                        if (faded) {
                            try {
                                for (i in 1..FADE_STEPS) {
                                    super.setVolume(userVolume * (1f - i / FADE_STEPS.toFloat()))
                                    Thread.sleep(FADE_STEP_MS)
                                }
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                        super.flush()
                        if (faded) super.setVolume(userVolume)
                    }
                }
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        autoGain = AutoGainController(this, gainProcessor).also { it.attach(player) }
        // Widgets auf dem Startbildschirm: bekommen Zustand und Tasten vom Player
        WidgetHub.player = player
        widgets = WidgetUpdater(this, player).also { WidgetHub.updater = it }
        // Tipp auf die Benachrichtigung / das Sperrbildschirm-Widget öffnet den Player
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Die Mediensitzung bekommt den Player mit dem Rand-Verhalten für "einen Titel wiederholen";
        // Auto-Gain, Widgets und die Wiedergabe selbst nutzen weiter den eigentlichen Player
        session = MediaSession.Builder(this, RepeatOneWrapPlayer(player))
            .setSessionActivity(openPending)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        autoGain?.release()
        autoGain = null
        WidgetHub.player = null
        WidgetHub.updater = null
        widgets?.release()
        widgets = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
