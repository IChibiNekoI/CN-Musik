package com.example.musikplayer

import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

enum class SortMode { DATE, MODIFIED, NAME }

data class Playlist(val id: String, val name: String, val songIds: List<Long>, val cover: String? = null)

internal fun prefs(ctx: Context) =
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

/** Die Einstellungen der App, auch für Listener (z. B. im Player-Dienst). */
fun appPrefs(ctx: Context): android.content.SharedPreferences = prefs(ctx)

/** Auto-Gain: gleicht die Lautstärke zwischen Titeln an. Ohne eigene Wahl an. */
fun readAutoGain(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_gain", true)

fun saveAutoGain(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("auto_gain", on).apply()
}

fun readSort(ctx: Context): SortMode =
    when (prefs(ctx).getString("sort", "date")) {
        "name" -> SortMode.NAME
        "modified" -> SortMode.MODIFIED
        else -> SortMode.DATE
    }

fun saveSort(ctx: Context, mode: SortMode) {
    val key = when (mode) {
        SortMode.NAME -> "name"
        SortMode.MODIFIED -> "modified"
        SortMode.DATE -> "date"
    }
    prefs(ctx).edit().putString("sort", key).apply()
}

/** Darstellung einer Übersicht: Liste oder Kacheln (2 oder 3 nebeneinander). */
data class ViewStyle(val grid: Boolean = false, val cols: Int = 2)

fun ViewStyle.label(): String = if (!grid) str(R.string.style_list) else str(R.string.style_tiles_cols, cols)

private fun encodeStyle(v: ViewStyle): String = if (!v.grid) "list" else "grid${v.cols}"

private fun decodeStyle(s: String?): ViewStyle = when (s) {
    "grid2" -> ViewStyle(true, 2)
    "grid3" -> ViewStyle(true, 3)
    else -> ViewStyle()
}

/** Rundung von Chips, Menüs, Feldern und Dialogen. Ohne eigene Wahl (nach der Installation): Einheitliche Rundungen. */
fun readCornerStyle(ctx: Context): CornerStyle =
    if (prefs(ctx).getString("corner_style", "uniform") == "uniform") CornerStyle.UNIFORM else CornerStyle.STANDARD

fun saveCornerStyle(ctx: Context, style: CornerStyle) {
    prefs(ctx).edit().putString("corner_style", if (style == CornerStyle.UNIFORM) "uniform" else "standard").apply()
}

/** Hintergrund des Players. Ohne eigene Wahl (nach der Installation): Unschärfe. */
fun readPlayerBg(ctx: Context): PlayerBackground = when (prefs(ctx).getString("player_bg", null)) {
    "gradient" -> PlayerBackground.GRADIENT
    "solid" -> PlayerBackground.SOLID
    else -> PlayerBackground.BLUR
}

fun savePlayerBg(ctx: Context, bg: PlayerBackground) {
    prefs(ctx).edit().putString(
        "player_bg",
        when (bg) {
            PlayerBackground.GRADIENT -> "gradient"
            PlayerBackground.BLUR -> "blur"
            PlayerBackground.SOLID -> "solid"
        }
    ).apply()
}

fun readMenuUnified(ctx: Context): Boolean = prefs(ctx).getBoolean("menu_unified", false)

fun saveMenuUnified(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("menu_unified", on).apply()
}

fun readCoverShadow(ctx: Context): Boolean = prefs(ctx).getBoolean("cover_shadow", false)

fun saveCoverShadow(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("cover_shadow", on).apply()
}

fun readShowOutputDevice(ctx: Context): Boolean = prefs(ctx).getBoolean("show_output_device", false)

fun saveShowOutputDevice(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("show_output_device", on).apply()
}

fun readShowSongDuration(ctx: Context): Boolean = prefs(ctx).getBoolean("show_song_duration", false)

fun saveShowSongDuration(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("show_song_duration", on).apply()
}

fun readSwipeHeader(ctx: Context): Boolean = prefs(ctx).getBoolean("swipe_header", false)

fun saveSwipeHeader(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("swipe_header", on).apply()
}

fun readWaveProgress(ctx: Context): Boolean = prefs(ctx).getBoolean("wave_progress", false)

fun saveWaveProgress(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("wave_progress", on).apply()
}

/** Aussehen der Kopfzeile: Text, Logo oder (nach dem Easter Egg) der müde Fuchs */
enum class HeaderStyle { TEXT, LOGO, FOX }

fun readHeaderStyle(ctx: Context): HeaderStyle {
    val p = prefs(ctx)
    p.getString("header_style", null)?.let { n -> HeaderStyle.values().firstOrNull { it.name == n } }?.let { return it }
    // frühere Einstellung (Logo an/aus) übernehmen
    return if (p.getBoolean("header_logo", false)) HeaderStyle.LOGO else HeaderStyle.TEXT
}

fun saveHeaderStyle(ctx: Context, v: HeaderStyle) {
    prefs(ctx).edit().putString("header_style", v.name).apply()
}

fun readFoxUnlocked(ctx: Context): Boolean = prefs(ctx).getBoolean("fox_unlocked", false)

fun saveFoxUnlocked(ctx: Context) {
    prefs(ctx).edit().putBoolean("fox_unlocked", true).apply()
}

fun readAlbumStyle(ctx: Context): ViewStyle = decodeStyle(prefs(ctx).getString("album_style", null))

fun saveAlbumStyle(ctx: Context, v: ViewStyle) {
    prefs(ctx).edit().putString("album_style", encodeStyle(v)).apply()
}

fun readPlaylistStyle(ctx: Context): ViewStyle = decodeStyle(prefs(ctx).getString("playlist_style", null))

fun savePlaylistStyle(ctx: Context, v: ViewStyle) {
    prefs(ctx).edit().putString("playlist_style", encodeStyle(v)).apply()
}

fun readRepeat(ctx: Context): Int =
    prefs(ctx).getInt("repeat", androidx.media3.common.Player.REPEAT_MODE_OFF)

fun saveRepeat(ctx: Context, mode: Int) {
    prefs(ctx).edit().putInt("repeat", mode).apply()
}

/** Shuffle im normalen Profil (alles außer dem Neko Mix). */
fun readShuffle(ctx: Context): Boolean = prefs(ctx).getBoolean("shuffle", false)

fun saveShuffle(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("shuffle", on).apply()
}

/** Eigene Einstellungen für den Neko Mix: Er läuft standardmäßig endlos (Repeat alle) und ohne Shuffle. */
fun readMixRepeat(ctx: Context): Int =
    prefs(ctx).getInt("mix_repeat", androidx.media3.common.Player.REPEAT_MODE_ALL)

fun saveMixRepeat(ctx: Context, mode: Int) {
    prefs(ctx).edit().putInt("mix_repeat", mode).apply()
}

fun readMixShuffle(ctx: Context): Boolean = prefs(ctx).getBoolean("mix_shuffle", false)

fun saveMixShuffle(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("mix_shuffle", on).apply()
}

/** Läuft gerade die Warteschlange des Neko Mixes? Dann gelten Repeat und Shuffle des Mixes (überlebt einen Neustart). */
fun readMixActive(ctx: Context): Boolean = prefs(ctx).getBoolean("mix_active", false)

fun saveMixActive(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("mix_active", on).apply()
}

/** Zuletzt gespielte Warteschlange mit aktuellem Titel und Position. */
class SavedQueue(val ids: List<Long>, val index: Int, val positionMs: Long)

/**
 * full = false speichert nur Titel-Position und Zeit (billig). Die ganze Warteschlange wird nur
 * geschrieben, wenn sie sich geändert hat, denn bei Tausenden Titeln ist das spürbar aufwendig.
 */
fun saveQueueState(ctx: Context, player: androidx.media3.common.Player, full: Boolean = true) {
    val n = player.mediaItemCount
    if (n == 0) return
    if (!full) {
        prefs(ctx).edit()
            .putInt("q_index", player.currentMediaItemIndex)
            .putLong("q_pos", player.currentPosition.coerceAtLeast(0L))
            .apply()
        return
    }
    val ids = ArrayList<Long>(n)
    for (i in 0 until n) player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { ids += it }
    if (ids.isEmpty()) return
    prefs(ctx).edit()
        .putString("q_ids", ids.joinToString(","))
        .putInt("q_index", player.currentMediaItemIndex)
        .putLong("q_pos", player.currentPosition.coerceAtLeast(0L))
        .apply()
}

fun readQueueState(ctx: Context): SavedQueue? {
    val p = prefs(ctx)
    val raw = p.getString("q_ids", null) ?: return null
    val ids = raw.split(',').mapNotNull { it.toLongOrNull() }
    if (ids.isEmpty()) return null
    return SavedQueue(ids, p.getInt("q_index", 0), p.getLong("q_pos", 0L))
}

/** Favoriten als Liste von Song-IDs, neueste zuerst */
/** Titel, die der Nutzer im Titel-Tab ausgeblendet hat (MediaStore-IDs) */
fun readHiddenSongs(ctx: Context): Set<Long> =
    prefs(ctx).getString("hidden_songs", null)
        ?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

fun saveHiddenSongs(ctx: Context, ids: Set<Long>) {
    prefs(ctx).edit().putString("hidden_songs", ids.joinToString(",")).apply()
}

fun readFavorites(ctx: Context): List<Long> =
    prefs(ctx).getString("favorites", null)
        ?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

fun saveFavorites(ctx: Context, ids: List<Long>) {
    prefs(ctx).edit().putString("favorites", ids.joinToString(",")).apply()
}

/** Optionale Startseite (Mix, neue Alben, zuletzt gehört). Ohne eigene Wahl an. */
fun readShowHome(ctx: Context): Boolean = prefs(ctx).getBoolean("show_home", true)

fun saveShowHome(ctx: Context, on: Boolean) {
    prefs(ctx).edit().putBoolean("show_home", on).apply()
}

/** Zuletzt gehörte Titel (Song-IDs), neueste zuerst. */
const val HISTORY_MAX = 60

fun readHistory(ctx: Context): List<Long> =
    prefs(ctx).getString("history", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

/** Trägt einen Titel sofort in den Verlauf ein (nach vorn, ohne Doppelte). Gibt den neuen Verlauf zurück. */
fun recordHistory(ctx: Context, id: Long, history: List<Long>): List<Long> {
    val newHistory = (listOf(id) + history.filter { it != id }).take(HISTORY_MAX)
    prefs(ctx).edit().putString("history", newHistory.joinToString(",")).apply()
    return newHistory
}

/** Eine gezählte Wiedergabe: Titel und Zeitpunkt (Millisekunden). */
class PlayEvent(val id: Long, val time: Long)

/** Der Mix wertet nur die Wiedergaben der letzten 30 Tage. */
const val MIX_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
private const val PLAY_LOG_MAX = 3000

private fun pruneLog(log: List<PlayEvent>, now: Long): List<PlayEvent> =
    log.filter { now - it.time in 0..MIX_WINDOW_MS }.takeLast(PLAY_LOG_MAX)

/** Hörprotokoll der letzten 30 Tage, gespeichert als "id:sekunden,id:sekunden,...". */
fun readPlayLog(ctx: Context): List<PlayEvent> {
    val raw = prefs(ctx).getString("play_log", null) ?: return emptyList()
    val events = raw.split(',').mapNotNull { part ->
        val i = part.indexOf(':')
        if (i < 0) return@mapNotNull null
        val id = part.substring(0, i).toLongOrNull() ?: return@mapNotNull null
        val sec = part.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
        PlayEvent(id, sec * 1000L)
    }
    return pruneLog(events, System.currentTimeMillis())
}

/** Hängt eine Wiedergabe ans Protokoll, wirft Einträge über 30 Tage weg und speichert. */
fun recordPlay(ctx: Context, id: Long, log: List<PlayEvent>): List<PlayEvent> {
    val now = System.currentTimeMillis()
    val newLog = pruneLog(log + PlayEvent(id, now), now)
    prefs(ctx).edit()
        .putString("play_log", newLog.joinToString(",") { "${it.id}:${it.time / 1000L}" })
        .remove("play_counts")   // alter Gesamtzähler, ersetzt durch das 30-Tage-Protokoll
        .apply()
    return newLog
}

/** Titel, die aus der Warteschlange des Mixes entfernt wurden (Titel-ID -> Zeitpunkt in ms). Sie bleiben 30 Tage aus dem Mix. */
fun readMixDislikes(ctx: Context): Map<Long, Long> {
    val raw = prefs(ctx).getString("mix_dislikes", null) ?: return emptyMap()
    val now = System.currentTimeMillis()
    return buildMap {
        for (part in raw.split(',')) {
            val i = part.indexOf(':')
            if (i < 0) continue
            val id = part.substring(0, i).toLongOrNull() ?: continue
            val t = (part.substring(i + 1).toLongOrNull() ?: continue) * 1000L
            if (now - t in 0..MIX_WINDOW_MS) put(id, t)
        }
    }
}

fun addMixDislike(ctx: Context, id: Long, current: Map<Long, Long>): Map<Long, Long> {
    val now = System.currentTimeMillis()
    val newMap = current.filterValues { now - it in 0..MIX_WINDOW_MS } + (id to now)
    prefs(ctx).edit()
        .putString("mix_dislikes", newMap.entries.joinToString(",") { "${it.key}:${it.value / 1000L}" })
        .apply()
    return newMap
}

/** Startwert für die Auswahl des Mixes: Wird einmal erzeugt und bleibt, damit die Auswahl nur von deinem Hören abhängt. */
fun readMixSeed(ctx: Context): Long {
    val p = prefs(ctx)
    if (p.contains("mix_seed")) return p.getLong("mix_seed", 0L)
    val seed = java.util.Random().nextLong()
    p.edit().putLong("mix_seed", seed).apply()
    return seed
}

/** Startwert für die Reihenfolge des Mixes: Der Würfel erzeugt einen neuen, die Auswahl bleibt dabei gleich. */
fun readMixOrderSeed(ctx: Context): Long {
    val p = prefs(ctx)
    if (p.contains("mix_order_seed")) return p.getLong("mix_order_seed", 0L)
    return newMixOrderSeed(ctx)
}

fun newMixOrderSeed(ctx: Context): Long {
    val seed = java.util.Random().nextLong()
    prefs(ctx).edit().putLong("mix_order_seed", seed).apply()
    return seed
}

/** Ein Skip im Neko Mix: Titel, Zeitpunkt (ms) und Stärke (2 = in den ersten Sekunden übersprungen, 1 = nach einer Weile). */
class MixSkip(val id: Long, val time: Long, val weight: Int)

private const val SKIP_LOG_MAX = 400

/** Skips der letzten 30 Tage, gespeichert als "id:sekunden:stärke,...". Sie zählen nur, wenn der Titel aus dem Neko Mix lief. */
fun readMixSkips(ctx: Context): List<MixSkip> {
    val raw = prefs(ctx).getString("mix_skips", null) ?: return emptyList()
    val now = System.currentTimeMillis()
    return raw.split(',').mapNotNull { part ->
        val p = part.split(':')
        if (p.size != 3) return@mapNotNull null
        val id = p[0].toLongOrNull() ?: return@mapNotNull null
        val t = (p[1].toLongOrNull() ?: return@mapNotNull null) * 1000L
        val w = p[2].toIntOrNull() ?: return@mapNotNull null
        if (now - t in 0..MIX_WINDOW_MS) MixSkip(id, t, w) else null
    }
}

fun addMixSkip(ctx: Context, id: Long, weight: Int, current: List<MixSkip>): List<MixSkip> {
    val now = System.currentTimeMillis()
    val newList = (current.filter { now - it.time in 0..MIX_WINDOW_MS } + MixSkip(id, now, weight)).takeLast(SKIP_LOG_MAX)
    prefs(ctx).edit()
        .putString("mix_skips", newList.joinToString(",") { "${it.id}:${it.time / 1000L}:${it.weight}" })
        .apply()
    return newList
}

fun readPlaylists(ctx: Context): List<Playlist> {
    val raw = prefs(ctx).getString("playlists", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("songs")
            Playlist(
                o.getString("id"), o.getString("name"), List(ids.length()) { ids.getLong(it) },
                o.optString("cover", "").ifEmpty { null }
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun savePlaylists(ctx: Context, list: List<Playlist>) {
    val arr = JSONArray()
    for (p in list) {
        val ids = JSONArray()
        for (id in p.songIds) ids.put(id)
        arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", ids).apply { p.cover?.let { put("cover", it) } })
    }
    prefs(ctx).edit().putString("playlists", arr.toString()).apply()
}

fun readShowAudioInfo(ctx: Context): Boolean =
    prefs(ctx).getBoolean("audio_info", true)

fun saveShowAudioInfo(ctx: Context, value: Boolean) {
    prefs(ctx).edit().putBoolean("audio_info", value).apply()
}
