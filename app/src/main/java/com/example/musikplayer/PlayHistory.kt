package com.example.musikplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Dauerhafter Hörverlauf: jede gezählte Wiedergabe (nach 3 Sekunden) mit ihrem Zeitpunkt, ohne Ablaufdatum.
 * Grundlage für "Meistens gespielt" mit frei wählbarem Zeitraum (Tag bis Jahr, auch rückwärts durch die Zeit).
 *
 * Er liegt in einer eigenen Datei (eine Zeile "id:sekunden" je Wiedergabe) und nicht in den Einstellungen: Anhängen kostet
 * eine Zeile, und die Einstellungen werden nicht größer (sie werden beim Abspielen regelmäßig neu geschrieben).
 * Beim ersten Mal ohne Datei wird sie aus dem 30-Tage-Protokoll aufgebaut. Älteres kennt nur die Langzeit-Statistik
 * ([readPlayTotals]), die für "Gesamte Zeit" mitzählt.
 */
object PlayHistory {
    private const val FILE = "play_history.txt"

    /** Mehr Einträge werden nicht behalten (bei 100 Wiedergaben am Tag etwa acht Jahre); die ältesten fallen zuerst weg. */
    private const val MAX_EVENTS = 300_000

    /** Eine Zeile der Rangliste: Titel, Anzahl im Zeitraum und Zeitpunkt der letzten Wiedergabe (Sekunden). */
    class Ranked(val id: Long, val count: Int, val lastSec: Long)

    /** Der Titel, der gerade läuft, aber erst nach 3 Sekunden gezählt wird; [startSec] = Beginn in Sekunden. */
    class Pending(val id: Long, val startSec: Long)

    /** Steigt mit jeder gespeicherten Wiedergabe: Anzeigen, die dies lesen, laden dann neu. */
    var version by mutableIntStateOf(0)
        private set

    /** Laufender, noch nicht gezählter Titel. "Meistens gespielt" rechnet ihn schon mit, damit der Zähler sofort steigt. */
    var pending by mutableStateOf<Pending?>(null)
        private set

    /** Merkt den Titel, der jetzt zu zählen beginnt; null, wenn keiner mehr aussteht (schon gezählt oder nichts läuft). */
    fun markPending(id: Long?) {
        pending = if (id == null) null else Pending(id, System.currentTimeMillis() / 1000L)
    }

    /**
     * Die Rangliste mit dem laufenden Titel als vorläufigem +1 (er erscheint auch, wenn er noch nicht darin war).
     * Ist er schon gezählt (seine letzte Wiedergabe liegt nach dem Beginn), bleibt die Liste, wie sie ist. Läuft er
     * weniger als 3 Sekunden und wird übersprungen, verschwindet das +1 wieder, genau wie er auch nie gezählt wird.
     */
    fun withPending(list: List<Ranked>, p: Pending?, nowSec: Long): List<Ranked> {
        if (p == null) return list
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0 && list[i].lastSec >= p.startSec) return list
        val out = list.toMutableList()
        if (i >= 0) out[i] = Ranked(p.id, list[i].count + 1, nowSec) else out.add(Ranked(p.id, 1, nowSec))
        return out.sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.lastSec })
    }

    private val lock = Any()
    private var ids = LongArray(1024)
    private var times = LongArray(1024)   // Sekunden seit 1970
    private var size = 0
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun add(id: Long, sec: Long) {
        if (size == ids.size) {
            ids = ids.copyOf(size * 2)
            times = times.copyOf(size * 2)
        }
        ids[size] = id
        times[size] = sec
        size++
    }

    /** Lädt die Datei einmal in den Speicher; gehört auf einen Hintergrund-Thread. Ohne Datei: aus dem 30-Tage-Protokoll aufbauen. */
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (f.exists()) {
            try {
                f.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val i = line.indexOf(':')
                        if (i <= 0) continue
                        val id = line.substring(0, i).toLongOrNull() ?: continue
                        val sec = line.substring(i + 1).toLongOrNull() ?: continue
                        add(id, sec)
                    }
                }
            } catch (e: Exception) {
                // beschädigte Datei: was bis hierhin gelesen wurde, zählt
            }
            if (size > MAX_EVENTS) {
                dropOldest()
                rewrite(ctx)
            }
        } else {
            for (e in readPlayLog(ctx)) add(e.id, e.time / 1000L)
            rewrite(ctx)
        }
    }

    /** Kürzt auf [MAX_EVENTS]: die ältesten (vorderen) Einträge fallen weg. */
    private fun dropOldest() {
        if (size <= MAX_EVENTS) return
        val drop = size - MAX_EVENTS
        ids = ids.copyOfRange(drop, size)
        times = times.copyOfRange(drop, size)
        size -= drop
    }

    /** Schreibt den ganzen Verlauf neu (über eine Zwischendatei, damit bei einem Abbruch nichts verloren geht). */
    private fun rewrite(ctx: Context) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                for (i in 0 until size) {
                    w.write("${ids[i]}:${times[i]}")
                    w.newLine()
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // dann bleibt die alte Datei; der Verlauf im Speicher gilt für diesen Lauf
        }
    }

    /** Kopie des ganzen Verlaufs (Titel-IDs und Zeitpunkte in Sekunden) für die Statistik; [firstSec] = ältester Eintrag, 0 ohne Verlauf. */
    class Snapshot(val ids: LongArray, val secs: LongArray, val firstSec: Long)

    /** Gehört auf einen Hintergrund-Thread. */
    fun snapshot(ctx: Context): Snapshot = synchronized(lock) {
        ensureLoaded(ctx)
        var first = 0L
        for (i in 0 until size) if (first == 0L || times[i] < first) first = times[i]
        Snapshot(ids.copyOf(size), times.copyOf(size), first)
    }

    /** Der ganze Verlauf als "id:sekunden,id:sekunden,..." für das Backup (gleiches Format wie das 30-Tage-Protokoll). */
    fun exportRaw(ctx: Context): String = synchronized(lock) {
        ensureLoaded(ctx)
        val sb = StringBuilder(size * 18)
        for (i in 0 until size) {
            if (i > 0) sb.append(',')
            sb.append(ids[i]).append(':').append(times[i])
        }
        sb.toString()
    }

    /** Ersetzt den Verlauf durch den aus einem Backup; Unlesbares und Unsinniges (Zeitpunkt in der Zukunft) wird übersprungen. */
    fun replaceFromRaw(ctx: Context, raw: String) {
        synchronized(lock) {
            loaded = true
            size = 0
            val limit = System.currentTimeMillis() / 1000L + 86_400L
            for (part in raw.split(',')) {
                val i = part.indexOf(':')
                if (i <= 0) continue
                val id = part.substring(0, i).trim().toLongOrNull() ?: continue
                val sec = part.substring(i + 1).trim().toLongOrNull() ?: continue
                if (sec <= 0L || sec > limit) continue
                add(id, sec)
            }
            dropOldest()
            rewrite(ctx)
            version += 1   // Anzeigen laden neu
        }
    }

    /** Hängt eine Wiedergabe an. Gehört auf einen Hintergrund-Thread (beim ersten Aufruf wird die Datei geladen). */
    fun record(ctx: Context, id: Long, timeMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            ensureLoaded(ctx)
            val sec = timeMs / 1000L
            // Schon enthalten (beim ersten Aufbau aus dem 30-Tage-Protokoll, das diese Wiedergabe schon kennt)
            val known = size > 0 && ids[size - 1] == id && Math.abs(times[size - 1] - sec) <= 5L
            if (!known) {
                add(id, sec)
                try {
                    file(ctx).appendText("$id:$sec\n")
                } catch (e: Exception) {
                    // nur im Speicher für diesen Lauf
                }
            }
            version += 1   // Anzeigen laden neu
        }
    }

    /** Zeitpunkt der ältesten Wiedergabe in Sekunden, 0 ohne Verlauf. */
    fun firstSec(ctx: Context): Long = synchronized(lock) {
        ensureLoaded(ctx)
        var first = 0L
        for (i in 0 until size) if (first == 0L || times[i] < first) first = times[i]
        first
    }

    /** Meistgehörte Titel im Zeitraum [fromSec, toSec) in Sekunden: nach Anzahl absteigend, bei Gleichstand der zuletzt gehörte zuerst. */
    fun ranking(ctx: Context, fromSec: Long, toSec: Long): List<Ranked> = synchronized(lock) {
        ensureLoaded(ctx)
        val count = HashMap<Long, Int>()
        val last = HashMap<Long, Long>()
        for (i in 0 until size) {
            val t = times[i]
            if (t < fromSec || t >= toSec) continue
            val id = ids[i]
            count[id] = (count[id] ?: 0) + 1
            if ((last[id] ?: 0L) < t) last[id] = t
        }
        sorted(count, last)
    }

    /** Gesamte Zeit: der Verlauf und die Langzeit-Statistik (die auch Wiedergaben vor dem Verlauf kennt); je Titel zählt der größere Wert. */
    fun allTime(ctx: Context): List<Ranked> {
        val totals = readPlayTotals(ctx)
        synchronized(lock) {
            ensureLoaded(ctx)
            val count = HashMap<Long, Int>()
            val last = HashMap<Long, Long>()
            for (i in 0 until size) {
                val id = ids[i]
                count[id] = (count[id] ?: 0) + 1
                if ((last[id] ?: 0L) < times[i]) last[id] = times[i]
            }
            for ((id, t) in totals) {
                if ((count[id] ?: 0) < t.count) count[id] = t.count
                val sec = t.lastMs / 1000L
                if ((last[id] ?: 0L) < sec) last[id] = sec
            }
            return sorted(count, last)
        }
    }

    private fun sorted(count: Map<Long, Int>, last: Map<Long, Long>): List<Ranked> =
        count.map { Ranked(it.key, it.value, last[it.key] ?: 0L) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.lastSec })
}
