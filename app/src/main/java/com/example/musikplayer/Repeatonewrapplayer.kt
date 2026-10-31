package com.example.musikplayer

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

    override fun seekToNextMediaItem() {
        if (!inner.hasNextMediaItem() && wraps()) wrapToFirst() else inner.seekToNextMediaItem()
    }

    override fun seekToNext() {
        if (!inner.hasNextMediaItem() && wraps()) wrapToFirst() else inner.seekToNext()
    }

    override fun seekToPreviousMediaItem() {
        if (!inner.hasPreviousMediaItem() && wraps()) wrapToLast() else inner.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() {
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
