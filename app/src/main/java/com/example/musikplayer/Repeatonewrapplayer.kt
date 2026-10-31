package com.example.musikplayer

import android.os.Handler
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Player für die Mediensitzung, also für Benachrichtigung, Sperrbildschirm und Kopfhörer-Tasten.
 *
 * Im Modus "einen Titel wiederholen" rechnet der Player beim Springen so, als wäre die Wiederholung aus: Am Rand der
 * Liste gibt es dann kein Weiter bzw. Zurück, die Tasten verschwinden. Hier bleiben sie in diesem Modus verfügbar und
 * springen wie bei "alle wiederholen" zum Anfang bzw. Ende der Liste (bei Zufall: der gemischten Reihenfolge).
 * Außerhalb dieses Modus verhält sich alles unverändert.
 */
class RepeatOneWrapPlayer(private val inner: Player) : ForwardingPlayer(inner) {

    // Kurzes Ausblenden vor dem Titelwechsel per Taste: Sonst schneidet der Player den Ton mitten in der Welle ab (Knacken)
    private val handler = Handler(inner.applicationLooper)
    private var muted = false
    private var savedVolume = 1f
    private val restoreVolume = Runnable {
        inner.volume = savedVolume
        muted = false
    }

    /** Zieht den Ton in etwa 25 ms auf null, führt dann [action] aus und blendet kurz danach wieder ein. */
    private fun fadeThen(action: () -> Unit) {
        if (muted) {
            // Ton ist schon (fast) aus, z. B. bei schnellem Weiterdrücken: gleich springen
            action()
            handler.removeCallbacks(restoreVolume)
            handler.postDelayed(restoreVolume, RESTORE_MS)
            return
        }
        if (!inner.isPlaying) { action(); return }   // Pause oder Puffern: nichts zu hören
        muted = true
        savedVolume = inner.volume
        val base = savedVolume
        FADE_STEPS.forEachIndexed { i, f -> handler.postDelayed({ inner.volume = base * f }, i * STEP_MS) }
        handler.postDelayed({
            action()
            handler.removeCallbacks(restoreVolume)
            handler.postDelayed(restoreVolume, RESTORE_MS)
        }, FADE_STEPS.size * STEP_MS)
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        if (muted) inner.volume = savedVolume
        muted = false
        super.release()
    }

    private companion object {
        val FADE_STEPS = floatArrayOf(0.5f, 0.15f, 0f)
        const val STEP_MS = 8L
        const val RESTORE_MS = 60L
    }

    private fun wraps(): Boolean =
        inner.repeatMode == Player.REPEAT_MODE_ONE && !inner.currentTimeline.isEmpty

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (!wraps()) return base
        return base.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun hasNextMediaItem(): Boolean = inner.hasNextMediaItem() || wraps()

    override fun hasPreviousMediaItem(): Boolean = inner.hasPreviousMediaItem() || wraps()

    override fun seekToNextMediaItem() = fadeThen {
        if (!inner.hasNextMediaItem() && wraps()) wrapToFirst() else inner.seekToNextMediaItem()
    }

    override fun seekToNext() = fadeThen {
        if (!inner.hasNextMediaItem() && wraps()) wrapToFirst() else inner.seekToNext()
    }

    override fun seekToPreviousMediaItem() = fadeThen {
        if (!inner.hasPreviousMediaItem() && wraps()) wrapToLast() else inner.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() = fadeThen {
        // Wie beim Player selbst: Nach den ersten Sekunden springt "Zurück" erst an den Anfang des Titels
        if (!inner.hasPreviousMediaItem() && wraps() && inner.currentPosition <= inner.maxSeekToPreviousPosition) {
            wrapToLast()
        } else {
            inner.seekToPrevious()
        }
    }

    private fun wrapToFirst() {
        SkipDirection.dir = 1
        inner.seekToDefaultPosition(inner.currentTimeline.getFirstWindowIndex(inner.shuffleModeEnabled))
    }

    private fun wrapToLast() {
        SkipDirection.dir = -1
        inner.seekToDefaultPosition(inner.currentTimeline.getLastWindowIndex(inner.shuffleModeEnabled))
    }
}
