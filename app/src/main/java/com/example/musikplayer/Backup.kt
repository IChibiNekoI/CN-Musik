package com.example.musikplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sichert und stellt wieder her: alle Einstellungen, ausgeblendete Titel, Favoriten und Playlists in einer JSON-Datei.
 *
 * Titel werden in der App über ihre MediaStore-ID geführt. Die kann sich nach einer Neuinstallation oder auf einem
 * anderen Gerät ändern. Deshalb stehen im Backup zu jedem verwendeten Titel auch Ordner, Dateiname, Titel, Interpret,
 * Album und Laufzeit; beim Wiederherstellen werden die Titel damit in der aktuellen Bibliothek wiedergefunden.
 */
object Backup {
    private const val FORMAT = "cn-musik-backup"
    private const val VERSION = 1

    /** Zwischenspeicher und Laufzeit-Zustand: werden nicht gesichert (entstehen von selbst neu) */
    private val SKIP = setOf("tag_cache", "last_scan", "q_ids", "q_index", "cover_seed_h", "cover_seed_s", "cover_seed_n", "history", "play_counts", "play_log", "mix_seed", "mix_order_seed", "mix_dislikes")

    /** [ok] = Datei gültig und eingespielt, [matched] von [total] verwendeten Titeln wurden in der Bibliothek gefunden */
    class Result(val ok: Boolean, val matched: Int = 0, val total: Int = 0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun create(ctx: Context, songs: List<Song>): String {
        val p = prefs(ctx)
        val prefsJson = JSONObject()
        for ((key, value) in p.all) {
            if (key in SKIP || value == null) continue
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

        // Alle Titel, auf die sich Ausgeblendete, Favoriten und Playlists beziehen, mit Merkmalen zum Wiederfinden
        val used = linkedSetOf<Long>()
        used += readHiddenSongs(ctx)
        used += readFavorites(ctx)
        readPlaylists(ctx).forEach { used += it.songIds }
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
            .put("prefs", prefsJson)
            .put("songs", songsJson)
            .toString(2)
    }

    private fun norm(s: String) = s.trim().lowercase()

    fun restore(ctx: Context, text: String, songs: List<Song>): Result {
        val root = try { JSONObject(text) } catch (e: Exception) { return Result(false) }
        if (root.optString("format") != FORMAT) return Result(false)
        val prefsJson = root.optJSONObject("prefs") ?: return Result(false)
        val songsJson = root.optJSONObject("songs") ?: JSONObject()

        // Zuordnung alter Titel-ID -> Titel in der aktuellen Bibliothek
        val byId = songs.associateBy { it.id }
        val byFile = songs.filter { it.fileName.isNotBlank() }.groupBy { norm(it.folder) + "/" + norm(it.fileName) }
        val byMeta = songs.groupBy { norm(it.title) + "|" + norm(it.artist) + "|" + norm(it.album) }
        val cache = HashMap<Long, Long?>()
        fun map(old: Long): Long? = cache.getOrPut(old) {
            // Bibliothek (noch) nicht geladen: nichts umrechnen, sonst gingen alle Verweise verloren
            if (songs.isEmpty()) return@getOrPut old
            val fp = songsJson.optJSONObject(old.toString()) ?: return@getOrPut byId[old]?.id
            val fileName = fp.optString("fileName")
            if (fileName.isNotBlank()) {
                byFile[norm(fp.optString("folder")) + "/" + norm(fileName)]?.firstOrNull()?.let { return@getOrPut it.id }
            }
            val cands = byMeta[norm(fp.optString("title")) + "|" + norm(fp.optString("artist")) + "|" + norm(fp.optString("album"))]
            if (cands.isNullOrEmpty()) return@getOrPut null
            val dur = fp.optLong("durationMs", 0L)
            if (dur <= 0L) cands.first().id
            else cands.filter { it.durationMs <= 0L || kotlin.math.abs(it.durationMs - dur) <= 3000L }
                .minByOrNull { kotlin.math.abs(it.durationMs - dur) }?.id
        }

        val edit = prefs(ctx).edit()
        // Vorhandene Einstellungen ersetzen (Zwischenspeicher und Warteschlange bleiben, wie sie sind)
        for (key in prefs(ctx).all.keys) if (key !in SKIP) edit.remove(key)
        val keys = prefsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in SKIP) continue
            val o = prefsJson.optJSONObject(key) ?: continue
            when (o.optString("t")) {
                "b" -> edit.putBoolean(key, o.getBoolean("v"))
                "i" -> edit.putInt(key, o.getInt("v"))
                "l" -> edit.putLong(key, o.getLong("v"))
                "f" -> edit.putFloat(key, o.getDouble("v").toFloat())
                "s" -> edit.putString(key, o.getString("v"))
                "set" -> {
                    val arr = o.getJSONArray("v")
                    edit.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }

        // Titel-Listen mit neu zugeordneten IDs; nicht gefundene Titel fallen heraus
        val seen = linkedSetOf<Long>()
        val found = linkedSetOf<Long>()
        fun mapList(ids: List<Long>): List<Long> = ids.mapNotNull { old ->
            seen += old
            map(old)?.also { found += old }
        }.distinct()

        prefsJson.optJSONObject("hidden_songs")?.let { o ->
            val ids = o.optString("v").split(",").mapNotNull { it.toLongOrNull() }
            edit.putString("hidden_songs", mapList(ids).joinToString(","))
        }
        prefsJson.optJSONObject("favorites")?.let { o ->
            val ids = o.optString("v").split(",").mapNotNull { it.toLongOrNull() }
            edit.putString("favorites", mapList(ids).joinToString(","))
        }
        prefsJson.optJSONObject("playlists")?.let { o ->
            try {
                val arr = JSONArray(o.optString("v"))
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val pl = arr.getJSONObject(i)
                    val old = pl.getJSONArray("songs")
                    val mapped = mapList(List(old.length()) { old.getLong(it) })
                    val ids = JSONArray()
                    mapped.forEach { ids.put(it) }
                    out.put(JSONObject().put("id", pl.getString("id")).put("name", pl.getString("name")).put("songs", ids))
                }
                edit.putString("playlists", out.toString())
            } catch (e: Exception) {
                // Playlists im Backup unlesbar: die vorhandenen bleiben nicht erhalten, aber alles andere wird trotzdem eingespielt
                edit.remove("playlists")
            }
        }

        val ok = edit.commit()
        return Result(ok, found.size, seen.size)
    }
}
