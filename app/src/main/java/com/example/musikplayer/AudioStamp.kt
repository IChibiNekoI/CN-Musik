package com.example.musikplayer

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stempel einer Audiodatei: Größe und Änderungszeit. Messwerte (Lautheit, Klangmerkmale) werden zusammen mit dem
 * Stempel gespeichert. Hat sich die Datei seitdem verändert (anderer Stempel), gilt der alte Messwert nicht mehr und
 * der Titel wird neu gemessen. Ist ein Stempel unbekannt (ältere Einträge, MediaStore liefert nichts), gilt der Wert weiter.
 */
data class AudioStamp(val size: Long, val modified: Long) {
    val known: Boolean get() = size > 0L && modified > 0L
}

object AudioStamps {
    private val current = ConcurrentHashMap<Long, AudioStamp>()

    /** Stempel der Datei, wie die Bibliothek sie gerade sieht (null = unbekannt). */
    fun of(id: Long): AudioStamp? = current[id]

    /** Merkt die Stempel der aktuellen Bibliothek. Liefert einen Wert, der sich ändert, sobald sich irgendein Stempel ändert. */
    fun update(songs: List<Song>): Int {
        current.clear()
        var h = songs.size
        for (s in songs) {
            val st = AudioStamp(s.size, s.dateModified)
            if (st.known) {
                current[s.id] = st
                h = 31 * h + s.id.hashCode()
                h = 31 * h + st.hashCode()
            }
        }
        return h
    }

    /** Passt ein gespeicherter Stempel zur Datei von heute? Unbekannt auf einer Seite zählt als passend. */
    fun fits(stored: AudioStamp?, id: Long): Boolean {
        val now = current[id] ?: return true
        return stored == null || stored == now
    }

    /** Liest zwei Felder (Größe, Änderungszeit) als Stempel; null, wenn sie fehlen oder nicht lesbar sind. */
    fun parse(size: String?, modified: String?): AudioStamp? {
        val a = size?.toLongOrNull() ?: return null
        val b = modified?.toLongOrNull() ?: return null
        return AudioStamp(a, b).takeIf { it.known }
    }
}

/** Schreibt eine Datei in einem Zug neu (erst temporär, dann umbenennen), damit sie nie halb geschrieben liegen bleibt. */
internal fun rewriteAtomically(target: File, lines: Sequence<String>) {
    val tmp = File(target.path + ".tmp")
    tmp.bufferedWriter().use { w ->
        for (l in lines) { w.write(l); w.write("\n") }
    }
    if (!tmp.renameTo(target)) {
        target.delete()
        tmp.renameTo(target)
    }
}
