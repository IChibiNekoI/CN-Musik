package com.example.musikplayer

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.Locale

/*
 * Track-Identität: Die MediaStore-ID ist der schnelle Schlüssel der App (Favoriten, Verlauf, Playlists, Mix, Messwerte ...),
 * aber nicht dauerhaft verlässlich. Wird eine Datei neu eingelesen, gelöscht und wieder angelegt oder von einem anderen
 * Scanner neu registriert, bekommt sie eine neue ID, und alles, was an der alten hing, wäre verwaist.
 *
 * Deshalb merkt sich die App zu jedem Titel einen Fingerabdruck (Pfad, Laufzeit, Titel, Interpret, Album). Bei jedem
 * Laden der Bibliothek werden Titel mit unbekannter ID mit Titeln verglichen, die verschwunden sind. Erkennt die App
 * einen wieder, werden alle gespeicherten Verweise von der alten auf die neue ID umgeschrieben.
 *
 * Zugeordnet wird nur eindeutig (genau ein verschwundener Titel passt zu genau einem neuen) und in Stufen, vom
 * sichersten zum unsichersten Merkmal. Ein falscher Treffer wäre schlimmer als ein verpasster, deshalb gibt es
 * keine Zuordnung allein über den Dateinamen, und mehrdeutige Fälle (z. B. zwei Kopien desselben Titels) bleiben liegen.
 * Auch derselbe Pfad genügt nicht, wenn Titel und Interpret auf beiden Seiten bekannt sind und beide nicht zusammenpassen:
 * Dann liegt an der Stelle vermutlich ein anderer Titel (Datei ersetzt), und Favoriten, Verlauf und Playlists würden sonst
 * auf ihn übergehen. Eine neu oder anders getaggte Datei (bisher ungetaggt, Tippfehler, "(Remastered)") ist dagegen kein Widerspruch.
 * Ein Hash über die Audiodaten wäre zu teuer für die ganze Bibliothek und ist hier nicht nötig.
 */
object TrackIdentity {
    private const val FILE = "track_identity.txt"
    private const val DAY_MS = 86_400_000L
    private const val KEEP_MISSING_DAYS = 120L   // Fingerabdrücke verschwundener Titel so lange aufheben
    private const val DURATION_TOLERANCE_MS = 1_500L

    private class Fp(
        val id: Long,
        val seenDay: Long,
        val durationMs: Long,
        val path: String,     // Ordner + Dateiname, klein geschrieben
        val title: String,
        val artist: String,
        val album: String
    ) {
        fun sameAs(o: Fp) = durationMs == o.durationMs && path == o.path && title == o.title &&
            artist == o.artist && album == o.album
    }

    private var records: HashMap<Long, Fp>? = null

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun fpOf(s: Song, day: Long, unknownTitle: String): Fp = Fp(
        s.id, day, s.durationMs,
        if (s.fileName.isBlank()) "" else norm(s.folder) + "/" + norm(s.fileName),
        if (s.title.trim() == unknownTitle) "" else norm(s.title),
        norm(s.artist), norm(s.album)
    )

    private fun close(a: Long, b: Long) = a > 0L && b > 0L && kotlin.math.abs(a - b) <= DURATION_TOLERANCE_MS

    /** Passen zwei Tag-Werte zusammen? Gleich oder einer enthält den anderen; ist eine Seite unbekannt (leer), gilt das als vereinbar. */
    private fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return true
        return minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a))
    }

    /**
     * Widerspruch zwischen einem verschwundenen und einem neuen Titel: Titel UND Interpret sind auf beiden Seiten bekannt
     * und passen beide nicht zusammen. Bewusst streng gefasst, damit eine umgetaggte Datei weiter erkannt wird.
     * [unknownArtist] sind die Platzhalter für einen unbekannten Interpreten (zählen wie leer).
     */
    private fun contradicts(g: Fp, f: Fp, unknownArtist: Set<String>): Boolean {
        fun artist(s: String) = if (s in unknownArtist) "" else s
        return !similar(g.title, f.title) && !similar(artist(g.artist), artist(f.artist))
    }

    @Synchronized
    private fun load(ctx: Context): HashMap<Long, Fp> {
        records?.let { return it }
        val out = HashMap<Long, Fp>()
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) f.forEachLine { line ->
                val p = line.split('\t', limit = 7)
                if (p.size == 7) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    out[id] = Fp(
                        id, p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L,
                        p[3], p[4], p[5], p[6]
                    )
                }
            }
        } catch (e: Exception) {
            // Beschädigte Datei: Es wird einfach neu angelegt, schlimmstenfalls fehlt die Zuordnung für diesen einen Lauf
        }
        records = out
        return out
    }

    private fun save(ctx: Context, map: Map<Long, Fp>) {
        try {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                for (r in map.values) {
                    w.write("${r.id}\t${r.seenDay}\t${r.durationMs}\t${r.path}\t${r.title}\t${r.artist}\t${r.album}\n")
                }
            }
            if (!tmp.renameTo(File(ctx.filesDir, FILE))) {
                File(ctx.filesDir, FILE).delete()
                tmp.renameTo(File(ctx.filesDir, FILE))
            }
        } catch (e: Exception) {
            // Speichern ist optional: Beim nächsten Lauf wird neu angelegt
        }
    }

    /**
     * Gleicht die Bibliothek ([songs], ungefiltert, wie von MediaStore geladen) mit den gemerkten Fingerabdrücken ab.
     * Titel, die unter einer neuen ID wieder auftauchen, bekommen ihre gespeicherten Verweise (Favoriten, Verlauf,
     * Playlists, Mix, Messwerte, Warteschlange ...) umgeschrieben. Liefert die Zuordnung alte ID -> neue ID.
     * Gehört auf einen Hintergrund-Thread.
     */
    @Synchronized
    fun reconcile(ctx: Context, songs: List<Song>): Map<Long, Long> {
        if (songs.isEmpty()) return emptyMap()   // Bibliothek nicht verfügbar: nichts ändern, nichts vergessen
        val recs = load(ctx)
        val now = System.currentTimeMillis()
        val day = now / DAY_MS
        val unknown = str(R.string.unknown)
        // Platzhalter für "Interpret unbekannt": die von Android gelieferte und die der App, in beiden Sprachen
        val unknownArtist = setOf("<unknown>", "unknown", "unbekannt", norm(unknown))
        val current = HashMap<Long, Fp>(songs.size * 2)
        for (s in songs) current[s.id] = fpOf(s, day, unknown)

        val firstRun = recs.isEmpty()
        val moved = HashMap<Long, Long>()
        if (!firstRun) {
            // Neue IDs (unbekannt) und verschwundene IDs (gemerkt, aber nicht mehr in der Bibliothek)
            val fresh = current.values.filter { it.id !in recs }.toMutableSet()
            val gone = recs.values.filter { it.id !in current }.toMutableSet()
            if (fresh.isNotEmpty() && gone.isNotEmpty()) {
                // Stufe für Stufe, vom sichersten zum unsichersten Merkmal; jeweils nur eindeutige 1:1-Treffer
                fun stage(keyOf: (Fp) -> String?, compatible: (Fp, Fp) -> Boolean) {
                    val goneBy = HashMap<String, MutableList<Fp>>()
                    val freshBy = HashMap<String, MutableList<Fp>>()
                    for (g in gone) keyOf(g)?.let { goneBy.getOrPut(it) { mutableListOf() } += g }
                    for (f in fresh) keyOf(f)?.let { freshBy.getOrPut(it) { mutableListOf() } += f }
                    for ((k, gs) in goneBy) {
                        val fs = freshBy[k] ?: continue
                        if (gs.size != 1 || fs.size != 1) continue   // mehrdeutig: nicht raten
                        val g = gs[0]
                        val f = fs[0]
                        if (!compatible(g, f)) continue
                        moved[g.id] = f.id
                        gone.remove(g)
                        fresh.remove(f)
                    }
                }
                // 1. Gleicher Pfad: dieselbe Datei, nur neu registriert (Laufzeit darf fehlen, Tags dürfen sich geändert haben,
                //    aber nicht so, dass ein anderer Titel dahintersteckt)
                stage({ it.path.takeIf { p -> p.isNotEmpty() } }) { g, f ->
                    (g.durationMs <= 0L || f.durationMs <= 0L || close(g.durationMs, f.durationMs)) &&
                        !contradicts(g, f, unknownArtist)
                }
                // 2. Gleiche Tags und Laufzeit: Datei verschoben oder umbenannt
                stage({ if (it.title.isEmpty()) null else "${it.title}|${it.artist}|${it.album}" }) { g, f ->
                    close(g.durationMs, f.durationMs)
                }
                // 3. Titel und Interpret gleich, Album geändert oder verschoben und neu getaggt
                stage({ if (it.title.isEmpty() || it.artist.isEmpty()) null else "${it.title}|${it.artist}" }) { g, f ->
                    close(g.durationMs, f.durationMs)
                }
            }
        }

        // Fingerabdrücke nachführen. Der Tag der letzten Sichtung ändert sich höchstens einmal am Tag (spart Schreibzugriffe).
        var dirty = firstRun || moved.isNotEmpty()
        for (old in moved.keys) { recs.remove(old); dirty = true }
        for (c in current.values) {
            val r = recs[c.id]
            if (r == null || !r.sameAs(c) || r.seenDay != day) { recs[c.id] = c; dirty = true }
        }
        // Lange verschwundene Titel vergessen
        val iter = recs.values.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            if (r.id !in current && day - r.seenDay > KEEP_MISSING_DAYS) { iter.remove(); dirty = true }
        }
        if (dirty) save(ctx, recs)

        if (moved.isNotEmpty()) applyRemap(ctx, moved)
        return moved
    }

    /** IDs aller Titel, die die App kennt: aktuelle und kürzlich verschwundene (für das Aufräumen der Mess-Caches). */
    @Synchronized
    fun knownIds(ctx: Context): Set<Long> = HashSet(load(ctx).keys)

    /** Schreibt alle gespeicherten Verweise von alten auf neue IDs um. */
    private fun applyRemap(ctx: Context, m: Map<Long, Long>) {
        val p = prefs(ctx)
        val e = p.edit()

        // Listen aus IDs; bei Favoriten, Ausgeblendeten und Verlauf ohne Doppelte, in der Warteschlange sind Doppelte erlaubt
        fun idList(key: String, distinct: Boolean) {
            val raw = p.getString(key, null) ?: return
            var changed = false
            val out = raw.split(',').map { part ->
                val n = part.toLongOrNull()?.let { m[it] }
                if (n != null) { changed = true; n.toString() } else part
            }
            if (changed) e.putString(key, (if (distinct) out.distinct() else out).joinToString(","))
        }
        idList("favorites", true)
        idList("hidden_songs", true)
        idList("history", true)
        idList("q_ids", false)

        // Einträge der Form "id:..." (Hörprotokoll, Mix-Entfernungen, Mix-Skips)
        for (key in listOf("play_log", "play_totals", "mix_dislikes", "mix_skips")) {
            val raw = p.getString(key, null) ?: continue
            var changed = false
            val out = raw.split(',').map { part ->
                val i = part.indexOf(':')
                val n = if (i > 0) part.substring(0, i).toLongOrNull()?.let { m[it] } else null
                if (n != null) { changed = true; n.toString() + part.substring(i) } else part
            }
            if (changed) e.putString(key, out.joinToString(","))
        }

        // Playlists (JSON): Reihenfolge und Doppelte bleiben erhalten
        p.getString("playlists", null)?.let { raw ->
            try {
                val arr = JSONArray(raw)
                var changed = false
                for (i in 0 until arr.length()) {
                    val ids = arr.getJSONObject(i).getJSONArray("songs")
                    for (j in 0 until ids.length()) {
                        val n = m[ids.getLong(j)] ?: continue
                        ids.put(j, n)
                        changed = true
                    }
                }
                if (changed) e.putString("playlists", arr.toString())
            } catch (ex: Exception) {
                // unlesbar: unverändert lassen
            }
        }
        e.apply()

        // Gemessene Lautheit und Klangmerkmale folgen dem Titel, damit nichts neu gemessen werden muss
        LoudnessCache.carryOver(ctx, m)
        TrackFeatureCache.carryOver(ctx, m)
    }
}
