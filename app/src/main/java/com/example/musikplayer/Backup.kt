package com.example.musikplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Sichert und stellt wieder her: alle Einstellungen, ausgeblendete Titel, Favoriten und Playlists in einer JSON-Datei.
 * Auf Wunsch gehört auch das persönliche Hörprofil dazu (Verlauf, Hörprotokoll, Neko-Mix-Daten).
 *
 * Titel werden in der App über ihre MediaStore-ID geführt. Die kann sich nach einer Neuinstallation oder auf einem
 * anderen Gerät ändern. Deshalb stehen im Backup zu jedem verwendeten Titel auch Ordner, Dateiname, Titel, Interpret,
 * Album und Laufzeit; beim Wiederherstellen werden die Titel damit in der aktuellen Bibliothek wiedergefunden.
 *
 * Zugeordnet wird nur eindeutig: Passen mehrere Titel gleich gut (z. B. dieselbe Datei auf Speicher und SD-Karte),
 * wird der Titel lieber als nicht zugeordnet gemeldet, als einen falschen zu nehmen.
 *
 * Wird wiederhergestellt, bevor die Bibliothek geladen ist, merkt sich die App die Merkmale und ordnet die Titel
 * nach, sobald die Bibliothek da ist ([resolvePending]).
 */
object Backup {
    private const val FORMAT = "cn-musik-backup"
    private const val VERSION = 1
    private const val PENDING_FILE = "backup_pending.json"
    private const val DURATION_TOLERANCE_MS = 2_000L

    /** Größer darf eine Backup-Datei nicht sein (eine echte ist deutlich kleiner); schützt vor Speicherproblemen */
    private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

    /** Zwischenspeicher und Laufzeit-Zustand: werden nicht gesichert (entstehen von selbst neu) */
    private val SKIP = setOf(
        "tag_cache", "last_scan", "q_ids", "q_index", "cover_seed_h", "cover_seed_s", "cover_seed_n",
        "play_counts", "mix_active"
    )

    /** Persönliches Hörprofil: nur im Backup, wenn gewünscht; Wiederherstellen ersetzt es nur, wenn das Backup es enthält */
    private val PROFILE = setOf("history", "play_log", "play_totals", "mix_dislikes", "mix_skips", "mix_seed", "mix_order_seed")

    /** Einstellungen, die Titel-IDs enthalten und beim Wiederherstellen umgerechnet werden müssen */
    private val ID_LISTS = listOf("hidden_songs", "favorites", "history")       // "id,id,id"
    private val ID_ENTRIES = listOf("play_log", "play_totals", "mix_dislikes", "mix_skips")    // "id:...,id:..."
    private const val PLAYLISTS = "playlists"                                   // JSON

    /**
     * Einstellungen, die beim Wiederherstellen übernommen werden dürfen, mit ihrem Typ (b = Boolean, i = Int, l = Long,
     * f = Float, s = String, set = Menge von Strings). Die App liest jede Einstellung mit genau diesem Typ; ein anderer
     * Typ in der Datei würde sie beim nächsten Start zum Absturz bringen. Unbekannte Schlüssel und falsche Typen werden
     * deshalb übersprungen. Kommt in der App eine Einstellung dazu, muss sie hier eingetragen werden, sonst wird sie
     * beim Wiederherstellen nicht mehr übernommen. (Zwischenspeicher aus [SKIP] stehen bewusst nicht darin.)
     */
    private val PREF_TYPES: Map<String, String> =
        listOf(
            "audio_info", "auto_gain", "cover_shadow", "fox_unlocked", "haptics", "header_logo", "menu_unified",
            "mix_shuffle", "show_home", "show_output_device", "show_song_duration", "shuffle", "swipe_header", "wave_progress"
        ).associateWith { "b" } +
            listOf("mix_repeat", "repeat").associateWith { "i" } +
            listOf("mix_order_seed", "mix_seed", "q_pos").associateWith { "l" } +
            listOf(
                "album_style", "corner_style", "favorites", "header_style", "hidden_songs", "history", "language",
                "mix_dislikes", "mix_skips", "play_log", "play_totals", "player_bg", "playlist_style", "playlists", "sort"
            ).associateWith { "s" } +
            listOf("folder_exclude", "folder_include", "folders").associateWith { "set" }

    /**
     * [ok] = Datei gültig und eingespielt, [matched] von [total] verwendeten Titeln wurden in der Bibliothek gefunden,
     * davon [ambiguous] nicht, weil mehrere Titel gleich gut passten. [pending] = Bibliothek war noch nicht geladen,
     * die Zuordnung folgt automatisch.
     */
    class Result(
        val ok: Boolean, val matched: Int = 0, val total: Int = 0,
        val ambiguous: Int = 0, val pending: Boolean = false
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun idsOfList(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    private fun idsOfEntries(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.substringBefore(':').trim().toLongOrNull() } ?: emptyList()

    /** Liest eine Backup-Datei als Text. Null, wenn sie größer als [MAX_BACKUP_BYTES] ist. */
    fun readLimited(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BACKUP_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    fun create(ctx: Context, songs: List<Song>, includeProfile: Boolean = false): String {
        val p = prefs(ctx)
        val prefsJson = JSONObject()
        for ((key, value) in p.all) {
            if (key in SKIP || value == null) continue
            if (key in PROFILE && !includeProfile) continue
            val o = JSONObject()
            when (value) {
                is Boolean -> o.put("t", "b").put("v", value)
                is Int -> o.put("t", "i").put("v", value)
                is Long -> o.put("t", "l").put("v", value)
                is Float -> o.put("t", "f").put("v", value.toDouble())
                is String -> o.put("t", "s").put("v", value)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            prefsJson.put(key, o)
        }

        // Alle Titel, auf die sich Ausgeblendete, Favoriten, Playlists (und das Hörprofil) beziehen, mit Merkmalen zum Wiederfinden
        val used = linkedSetOf<Long>()
        used += readHiddenSongs(ctx)
        used += readFavorites(ctx)
        readPlaylists(ctx).forEach { used += it.songIds }
        if (includeProfile) {
            used += idsOfList(p.getString("history", null))
            for (key in ID_ENTRIES) used += idsOfEntries(p.getString(key, null))
        }
        val byId = songs.associateBy { it.id }
        val songsJson = JSONObject()
        for (id in used) {
            val s = byId[id] ?: continue
            songsJson.put(
                id.toString(),
                JSONObject()
                    .put("title", s.title).put("artist", s.artist).put("album", s.album)
                    .put("folder", s.folder).put("fileName", s.fileName).put("durationMs", s.durationMs)
            )
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created", System.currentTimeMillis())
            .put("profile", includeProfile)
            .put("prefs", prefsJson)
            .put("songs", songsJson)
            .toString(2)
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)

    private fun durationFits(a: Long, b: Long) = a <= 0L || b <= 0L || kotlin.math.abs(a - b) <= DURATION_TOLERANCE_MS

    /**
     * Findet zu einer alten Titel-ID den Titel in der aktuellen Bibliothek. Stufen vom sichersten zum unsichersten
     * Merkmal; jede Stufe nimmt nur einen eindeutigen Treffer. Bleibt mehr als ein Kandidat übrig, wird nichts zugeordnet.
     */
    private class Mapper(private val songsJson: JSONObject, songs: List<Song>) {
        private val byId = songs.associateBy { it.id }
        private val byFile = songs.filter { it.fileName.isNotBlank() }.groupBy { norm(it.folder) + "/" + norm(it.fileName) }
        private val byMeta = songs.filter { it.title.isNotBlank() }
            .groupBy { norm(it.title) + "|" + norm(it.artist) + "|" + norm(it.album) }
        private val cache = HashMap<Long, Long?>()
        val seen = linkedSetOf<Long>()
        val found = linkedSetOf<Long>()
        val ambiguous = linkedSetOf<Long>()

        /** Liefert die neue ID oder null (nicht gefunden oder nicht eindeutig) und zählt mit. */
        fun lookup(old: Long): Long? {
            seen += old
            val r = cache.getOrPut(old) { resolve(old) }
            if (r != null) found += old
            return r
        }

        private fun metaKey(fp: JSONObject) =
            norm(fp.optString("title")) + "|" + norm(fp.optString("artist")) + "|" + norm(fp.optString("album"))

        private fun resolve(old: Long): Long? {
            val fp = songsJson.optJSONObject(old.toString()) ?: return byId[old]?.id   // keine Merkmale gesichert: nur gleiche ID
            val dur = fp.optLong("durationMs", 0L)
            val fileName = fp.optString("fileName")
            val fileKey = norm(fp.optString("folder")) + "/" + norm(fileName)
            val mKey = metaKey(fp)

            // Gleiche ID, und die Datei passt noch dazu (selbes Gerät): sicherster Fall
            byId[old]?.let { s ->
                val samePath = fileName.isNotBlank() && s.fileName.isNotBlank() &&
                    norm(s.folder) + "/" + norm(s.fileName) == fileKey
                val sameMeta = s.title.isNotBlank() && norm(s.title) + "|" + norm(s.artist) + "|" + norm(s.album) == mKey
                if ((samePath || sameMeta) && durationFits(s.durationMs, dur)) return s.id
            }

            // 1. Gleicher Ordner und Dateiname (Laufzeit darf nicht widersprechen)
            if (fileName.isNotBlank()) {
                val c = byFile[fileKey].orEmpty().filter { durationFits(it.durationMs, dur) }
                if (c.size == 1) return c[0].id
                if (c.size > 1) {
                    // Derselbe Pfad mehrfach (z. B. interner Speicher und SD-Karte): über die Tags eingrenzen
                    val narrowed = c.filter { s -> norm(s.title) + "|" + norm(s.artist) + "|" + norm(s.album) == mKey }
                    if (narrowed.size == 1) return narrowed[0].id
                    ambiguous += old
                    return null
                }
            }

            // 2. Gleiche Tags und Laufzeit: Datei verschoben, umbenannt oder auf ein anderes Gerät kopiert
            if (norm(fp.optString("title")).isEmpty()) return null
            val c = byMeta[mKey].orEmpty().filter { durationFits(it.durationMs, dur) }
            if (c.size == 1) return c[0].id
            if (c.size > 1) {
                val sameName = if (fileName.isBlank()) emptyList() else c.filter { norm(it.fileName) == norm(fileName) }
                if (sameName.size == 1) return sameName[0].id
                ambiguous += old
            }
            return null
        }
    }

    // ---- Umrechnen der Titel-IDs in den gespeicherten Einstellungen ----

    private fun remapIdList(raw: String, m: (Long) -> Long?): String =
        idsOfList(raw).mapNotNull(m).distinct().joinToString(",")

    private fun remapEntries(raw: String, m: (Long) -> Long?): String =
        raw.split(',').mapNotNull { part ->
            val i = part.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val n = part.substring(0, i).trim().toLongOrNull()?.let(m) ?: return@mapNotNull null
            n.toString() + part.substring(i)
        }.joinToString(",")

    /** Playlists: Reihenfolge bleibt, nicht gefundene Titel fallen heraus, alle anderen Felder bleiben unverändert. */
    private fun remapPlaylists(raw: String, m: (Long) -> Long?): String? = try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val pl = arr.getJSONObject(i)
            val old = pl.getJSONArray("songs")
            val ids = JSONArray()
            for (j in 0 until old.length()) m(old.getLong(j))?.let { ids.put(it) }
            pl.put("songs", ids)
        }
        arr.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Rechnet alle Einstellungen mit Titel-IDs um. [read] liefert den Rohwert einer Einstellung (aus dem Backup oder
     * aus den aktuellen Einstellungen) oder null, wenn es sie nicht gibt; nur vorhandene werden geschrieben.
     */
    private fun remapAll(read: (String) -> String?, edit: SharedPreferences.Editor, mapper: Mapper) {
        val m: (Long) -> Long? = { mapper.lookup(it) }
        for (key in ID_LISTS) read(key)?.let { edit.putString(key, remapIdList(it, m)) }
        for (key in ID_ENTRIES) read(key)?.let { edit.putString(key, remapEntries(it, m)) }
        read(PLAYLISTS)?.let { raw ->
            val out = remapPlaylists(raw, m)
            // Playlists unlesbar: alles andere wird trotzdem eingespielt
            if (out != null) edit.putString(PLAYLISTS, out) else edit.remove(PLAYLISTS)
        }
    }

    /** Liest den Wert einer Einstellung in genau dem erwarteten Typ; null, wenn er fehlt oder nicht passt. */
    private fun parsePrefValue(type: String, o: JSONObject): Any? = try {
        when (type) {
            "b" -> o.getBoolean("v")
            "i" -> o.getInt("v")
            "l" -> o.getLong("v")
            "f" -> o.getDouble("v").takeIf { it.isFinite() }?.toFloat()
            "s" -> o.getString("v")
            "set" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Prüft die Playlists aus einer Backup-Datei und baut sie neu auf: Nur Name, Titel-IDs, ID und Bildname bleiben, alles
     * andere fällt weg. ID und Bildname werden später als Dateiname benutzt und müssen deshalb schlicht sein; eine
     * ungültige oder doppelte ID wird ersetzt, ein ungültiger Bildname entfällt. Null, wenn die Daten unlesbar sind.
     */
    private fun sanitizePlaylists(raw: String): String? = try {
        val arr = JSONArray(raw)
        val out = JSONArray()
        val usedIds = HashSet<String>()
        val stamp = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val pl = arr.optJSONObject(i) ?: continue
            val name = pl.opt("name") as? String ?: continue
            val songsIn = pl.optJSONArray("songs") ?: continue
            val songsOut = JSONArray()
            for (j in 0 until songsIn.length()) {
                val n = songsIn.opt(j) as? Number ?: continue
                songsOut.put(n.toLong())
            }
            val given = pl.opt("id") as? String
            val id = if (given != null && isSafePlaylistId(given) && usedIds.add(given)) given
            else "r${stamp}_$i".also { usedIds.add(it) }
            val o = JSONObject().put("id", id).put("name", name).put("songs", songsOut)
            val cover = pl.opt("cover") as? String
            if (cover != null && isSafeCoverName(cover)) o.put("cover", cover)
            out.put(o)
        }
        out.toString()
    } catch (e: Exception) {
        null
    }

    /** Stellt aus einer Backup-Datei wieder her. Eine beschädigte oder manipulierte Datei ergibt [Result] mit ok = false, nie einen Absturz. */
    fun restore(ctx: Context, text: String, songs: List<Song>): Result =
        try {
            restoreChecked(ctx, text, songs)
        } catch (e: Exception) {
            Result(false)
        }

    private fun restoreChecked(ctx: Context, text: String, songs: List<Song>): Result {
        val root = try { JSONObject(text) } catch (e: Exception) { return Result(false) }
        if (root.optString("format") != FORMAT) return Result(false)
        // Eine Datei aus einer neueren App-Version kennt vielleicht Dinge, die diese Version falsch deuten würde
        if (root.optInt("version", 0) !in 1..VERSION) return Result(false)
        val prefsJson = root.optJSONObject("prefs") ?: return Result(false)
        val songsJson = root.optJSONObject("songs") ?: JSONObject()

        // Erst alles prüfen, dann schreiben: nur bekannte Einstellungen, jede im erwarteten Typ
        val staged = LinkedHashMap<String, Any>()
        val keys = prefsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in SKIP) continue
            val type = PREF_TYPES[key] ?: continue
            val o = prefsJson.optJSONObject(key) ?: continue
            if (o.optString("t") != type) continue
            var value: Any = parsePrefValue(type, o) ?: continue
            if (key == PLAYLISTS) value = sanitizePlaylists(value as String) ?: continue
            staged[key] = value
        }

        val p = prefs(ctx)
        val edit = p.edit()
        // Vorhandene Einstellungen ersetzen (Zwischenspeicher und Warteschlange bleiben, wie sie sind; das Hörprofil
        // wird nur ersetzt, wenn das Backup eines enthält)
        for (key in p.all.keys) if (key !in SKIP && key !in PROFILE) edit.remove(key)
        for ((key, v) in staged) {
            when (v) {
                is Boolean -> edit.putBoolean(key, v)
                is Int -> edit.putInt(key, v)
                is Long -> edit.putLong(key, v)
                is Float -> edit.putFloat(key, v)
                is String -> edit.putString(key, v)
                is Set<*> -> edit.putStringSet(key, v.map { it.toString() }.toSet())
            }
        }

        val pendingFile = File(ctx.filesDir, PENDING_FILE)
        if (songs.isEmpty()) {
            // Bibliothek noch nicht geladen: die alten IDs bleiben vorerst stehen (sonst gingen alle Verweise verloren).
            // Die Merkmale werden gemerkt, und sobald die Bibliothek da ist, ordnet resolvePending die Titel zu.
            val ok = edit.commit()
            if (ok) {
                try { pendingFile.writeText(songsJson.toString()) } catch (e: Exception) { /* dann bleiben die alten IDs */ }
            }
            return Result(ok, pending = true)
        }

        // Titel-Listen mit neu zugeordneten IDs; nicht gefundene und mehrdeutige Titel fallen heraus
        val mapper = Mapper(songsJson, songs)
        remapAll({ key -> staged[key] as? String }, edit, mapper)
        val ok = edit.commit()
        if (ok) pendingFile.delete()   // ein früheres, noch offenes Nachordnen ist damit überholt
        return Result(ok, mapper.found.size, mapper.seen.size, mapper.ambiguous.size)
    }

    /** Wartet nach einer Wiederherstellung noch eine Zuordnung auf die Bibliothek? (billige Prüfung) */
    fun hasPending(ctx: Context): Boolean = File(ctx.filesDir, PENDING_FILE).exists()

    /**
     * Ordnet nach, wenn die Wiederherstellung lief, bevor die Bibliothek geladen war: Die gemerkten Merkmale werden mit der
     * Bibliothek abgeglichen und alle Titel-Verweise in den Einstellungen umgeschrieben. Liefert null, wenn nichts offen
     * war oder die Bibliothek noch leer ist. Danach müssen Listen und Zustand neu aus den Einstellungen geladen werden.
     * Gehört auf einen Hintergrund-Thread.
     */
    fun resolvePending(ctx: Context, songs: List<Song>): Result? {
        val f = File(ctx.filesDir, PENDING_FILE)
        if (!f.exists() || songs.isEmpty()) return null
        val songsJson = try { JSONObject(f.readText()) } catch (e: Exception) { f.delete(); return null }
        val p = prefs(ctx)
        val edit = p.edit()
        val mapper = Mapper(songsJson, songs)
        remapAll({ key -> p.getString(key, null) }, edit, mapper)
        val ok = edit.commit()
        if (ok) f.delete()
        return Result(ok, mapper.found.size, mapper.seen.size, mapper.ambiguous.size)
    }
}
