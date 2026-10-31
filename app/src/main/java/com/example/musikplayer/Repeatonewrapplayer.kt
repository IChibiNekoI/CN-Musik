package com.example.musikplayer

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Blendet beim Springen den Ton kurz aus, bevor der Player den Titel wechselt, und danach wieder ein.
 *
 * Ohne das verwirft der Player beim Sprung sofort den Rest des alten Titels: Die Welle wird mitten in der Schwingung
 * abgeschnitten, und das hört man als Knacken (nur dann, wenn der Schnitt zufällig bei einem hohen Wert liegt,
 * deshalb "hin und wieder"). Die Ausblendung läuft über die Lautstärke des Players, wirkt also direkt am Ausgang und
 * nicht erst am Ende des vorberechneten Puffers. Beim Pausieren, Puffern oder ohne Wiedergabe wird nichts verzögert.
 */
class SkipFader(private val player: Player) {
    private val handler = Handler(player.applicationLooper)
    private var base = 1f
    private var busy = false          // Aus- oder Einblendung (oder Wartezeit dazwischen) läuft
    private var fadingOut = false
    private val pending = ArrayList<() -> Unit>()
    private var step: Runnable? = null

    /** Führt [action] (den eigentlichen Sprung) aus, wenn der Ton ausgeblendet ist. Mehrere Sprünge hintereinander bleiben in Reihenfolge. */
    fun skip(action: () -> Unit) {
        if (fadingOut) { pending.add(action); return }
        if (!busy && !player.isPlaying) { action(); return }   // nichts zu hören, nichts zu verbergen
        if (!busy) base = player.volume
        busy = true
        fadingOut = true
        pending.add(action)
        ramp(player.volume, 0f, OUT_MS) {
            fadingOut = false
            val todo = ArrayList(pending)
            pending.clear()
            todo.forEach { it() }
            // Kurz warten, bis der Player den alten Rest verworfen hat, dann einblenden
            val r = Runnable { ramp(0f, base, IN_MS) { busy = false } }
            step = r
            handler.postDelayed(r, IN_DELAY_MS)
        }
    }

    /** Bricht alles ab und stellt die Lautstärke wieder her (beim Freigeben des Players). */
    fun cancel() {
        cancelStep()
        if (busy) player.volume = base
        busy = false
        fadingOut = false
        pending.clear()
    }

    private fun cancelStep() {
        step?.let { handler.removeCallbacks(it) }
        step = null
    }

    private fun ramp(from: Float, to: Float, ms: Long, done: () -> Unit) {
        cancelStep()
        val start = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                player.volume = from + (to - from) * t
                if (t < 1f) handler.postDelayed(this, 8L) else { step = null; done() }
            }
        }
        step = r
        r.run()
    }

    private companion object {
        const val OUT_MS = 40L
        const val IN_DELAY_MS = 30L
        const val IN_MS = 30L
    }
}

/**
 * Player für die Mediensitzung, also für Benachrichtigung, Sperrbildschirm und Kopfhörer-Tasten (und die Widgets).
 *
 * Im Modus "einen Titel wiederholen" rechnet der Player beim Springen so, als wäre die Wiederholung aus: Am Rand der
 * Liste gibt es dann kein Weiter bzw. Zurück, die Tasten verschwinden. Hier bleiben sie in diesem Modus verfügbar und
 * springen wie bei "alle wiederholen" zum Anfang bzw. Ende der Liste (bei Zufall: der gemischten Reihenfolge).
 * Außerhalb dieses Modus verhält sich alles unverändert.
 *
 * Alle Sprünge zu einem anderen Titel (oder an den Anfang) laufen über [SkipFader], damit es nicht knackt.
 */
class RepeatOneWrapPlayer(private val inner: Player) : ForwardingPlayer(inner) {

    private val fader = SkipFader(inner)

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

    override fun seekToNextMediaItem() = fader.skip {
        if (!inner.hasNextMediaItem() && wraps()) wrapToFirst() else inner.seekToNextMediaItem()
    }

    override fun seekToNext() = fader.skip {
        if (!inner.hasNextMediaItem() && wraps()) wrapToFirst() else inner.seekToNext()
    }

    override fun seekToPreviousMediaItem() = fader.skip {
        if (!inner.hasPreviousMediaItem() && wraps()) wrapToLast() else inner.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() = fader.skip {
        // Wie beim Player selbst: Nach den ersten Sekunden springt "Zurück" erst an den Anfang des Titels
        if (!inner.hasPreviousMediaItem() && wraps() && inner.currentPosition <= inner.maxSeekToPreviousPosition) {
            wrapToLast()
        } else {
            inner.seekToPrevious()
        }
    }

    // Tipp auf einen Titel in der Warteschlange
    override fun seekToDefaultPosition(mediaItemIndex: Int) =
        fader.skip { inner.seekToDefaultPosition(mediaItemIndex) }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        // Nur ein Wechsel zu einem anderen Titel wird ausgeblendet, Spulen im Titel nicht
        if (mediaItemIndex != inner.currentMediaItemIndex) fader.skip { inner.seekTo(mediaItemIndex, positionMs) }
        else inner.seekTo(mediaItemIndex, positionMs)
    }

    override fun release() {
        fader.cancel()
        super.release()
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
