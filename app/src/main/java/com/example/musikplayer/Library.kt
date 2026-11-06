package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import android.net.Uri
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.MediaFormat
import android.provider.OpenableColumns
import java.util.Locale
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class SortMode { DATE, MODIFIED, NAME }

data class Playlist(val id: String, val name: String, val songIds: List<Long>, val cover: String? = null)

private fun prefs(ctx: Context) =
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

/** Rundung der Albumcover. Ohne eigene Wahl gilt die bisherige gemeinsame Einstellung weiter. */
fun readCoverStyle(ctx: Context): CornerStyle {
    val p = prefs(ctx)
    val v = p.getString("cover_style", null) ?: p.getString("corner_style", null)
    return if (v == "uniform") CornerStyle.UNIFORM else CornerStyle.STANDARD
}

fun saveCoverStyle(ctx: Context, style: CornerStyle) {
    prefs(ctx).edit().putString("cover_style", if (style == CornerStyle.UNIFORM) "uniform" else "standard").apply()
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

fun readHeaderLogo(ctx: Context): Boolean = prefs(ctx).getBoolean("header_logo", false)

fun saveHeaderLogo(ctx: Context, v: Boolean) {
    prefs(ctx).edit().putBoolean("header_logo", v).apply()
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

fun readFolderSet(ctx: Context): Set<String>? =
    prefs(ctx).getStringSet("folders", null)?.toSet()

fun saveFolderSet(ctx: Context, set: Set<String>) {
    prefs(ctx).edit().putStringSet("folders", set).apply()
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

/** Fester Zufallswert zwischen 0 und 1 für einen Titel: Gleicher Startwert und gleicher Titel ergeben immer denselben Wert. */
private fun unitFor(seed: Long, id: Long): Double {
    var z = seed + id * -7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    z = z xor (z ushr 31)
    return ((z ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(1e-9, 1.0)
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

private const val DAY_MS = 86_400_000.0
private const val HOUR_MS = 3_600_000L
private const val SESSION_GAP_MS = 20L * 60 * 1000
private const val MIX_DISCOVER_SHARE = 0.2   // Anteil der Plätze für Titel, die du in letzter Zeit nicht gehört hast

/** Gewicht eines Ereignisses, das [ageMs] zurückliegt, wenn es alle [days] Tage halb so viel zählen soll. */
private fun decay(ageMs: Long, days: Double): Double = Math.pow(0.5, ageMs / (days * DAY_MS))

/** Tempo (80 bis 160 BPM, eine Oktave) als Stelle auf einem Kreis von 0 bis 1. */
private fun tempoPos(bpm: Float): Double = Math.log(bpm / 80.0) / Math.log(2.0)

/** Abstand zweier Tempi auf dem Oktavenkreis: 0 = gleich, 0,5 = größter Abstand. */
private fun tempoDist(a: Double, b: Double): Double {
    val d = Math.abs(a - b)
    return Math.min(d, 1.0 - d)
}

/** Dein typischer Klang, gemittelt über Favoriten und oft gehörte Titel. */
private class MixProfile(
    val tempo: Double,
    val hasTempo: Boolean,
    val energy: Double,
    val brightness: Double,
    val confidence: Double
)

/** Wie gut passt ein Titel zu deinem typischen Klang? 0 bis 1; ohne Messwert neutral. */
private fun soundMatch(f: TrackFeatures?, p: MixProfile?): Double {
    if (p == null) return 0.0
    if (f == null || f.failed) return 0.35
    val de = (f.energy - p.energy) / 0.25
    val db = (f.brightness - p.brightness) / 0.3
    val gE = Math.exp(-0.5 * de * de)
    val gB = Math.exp(-0.5 * db * db)
    val gT = if (f.hasTempo && p.hasTempo) {
        val dt = tempoDist(tempoPos(f.bpm), p.tempo) / 0.12
        Math.exp(-0.5 * dt * dt)
    } else 0.5
    return 0.4 * gT + 0.35 * gE + 0.25 * gB
}

/** Abstand zweier Titel im Klang: 0 = sehr ähnlich, etwa 1 = sehr verschieden; ohne Messwerte neutral. */
private fun soundDistance(a: TrackFeatures?, b: TrackFeatures?): Double {
    if (a == null || b == null || a.failed || b.failed) return 0.4
    val t = if (a.hasTempo && b.hasTempo) 2.0 * tempoDist(tempoPos(a.bpm), tempoPos(b.bpm)) else 0.4
    return 0.5 * t + 0.3 * Math.abs((a.energy - b.energy).toDouble()) + 0.2 * Math.abs((a.brightness - b.brightness).toDouble())
}

/**
 * Bringt die gewählten Titel in eine angenehme Reihenfolge: Der nächste Titel klingt möglichst ähnlich wie der letzte,
 * derselbe Künstler steht nicht direkt hintereinander, und der [orderSeed] sorgt für Abwechslung (Würfel).
 */
private fun flowOrder(list: List<Song>, orderSeed: Long, features: (Long) -> TrackFeatures?): List<Song> {
    val left = list.sortedBy { unitFor(orderSeed, it.id) }.toMutableList()
    if (left.size < 3) return left
    val result = ArrayList<Song>(left.size)
    var cur = left.removeAt(0)
    result += cur
    while (left.isNotEmpty()) {
        val fc = features(cur.id)
        var best = 0
        var bestScore = Double.MAX_VALUE
        for (i in left.indices) {
            val c = left[i]
            var d = soundDistance(fc, features(c.id))
            if (c.artist.isNotBlank() && c.artist == cur.artist) d += 0.6
            d += 0.35 * unitFor(orderSeed + cur.id, c.id)
            if (d < bestScore) {
                bestScore = d
                best = i
            }
        }
        cur = left.removeAt(best)
        result += cur
    }
    return result
}

/**
 * "Dein Neko Mix": eine Playlist aus der eigenen Bibliothek, die sich an deinem Hören orientiert.
 *
 * Gewertet werden (alles nur aus Daten, die die App ohnehin speichert, ohne Rechenlast im Hintergrund):
 *  - Favoriten und Wiedergaben der letzten 30 Tage, jüngere zählen mehr (Halbwertszeit 10 Tage)
 *  - Künstler, die du magst
 *  - Skips im Mix (nur dort): übersprungene Titel und ihre Künstler werden seltener gewählt
 *  - Ähnlichkeit: Titel, die in derselben Hörsitzung wie deine Favoriten liefen, und Alben deiner Favoriten
 *  - Klang: Tempo, Energie und Helligkeit im Vergleich zu deinem typischen Klang ([features], falls schon gemessen)
 *  - Wiederholungsschutz: Was du gerade erst gehört hast, steht seltener drin
 * Rund ein Fünftel der Plätze gehört Titeln, die du in letzter Zeit nicht gehört hast: neu hinzugekommene und
 * solche, die zu deinem Geschmack passen. Die Mischung wird dynamisch: Je mehr Hörverlauf da ist, desto mehr zählen
 * Wiedergaben gegenüber Favoriten.
 *
 * Jeder Titel bekommt aus Startwert und Titel-ID einen festen Zufallswert, damit der Mix stabil bleibt: Er ändert sich
 * nicht mit dem Tag, sondern nur beim Neu-Mischen oder wenn sich Hörgewohnheiten oder Bibliothek spürbar ändern.
 * Die Reihenfolge der gewählten Titel bestimmt der [orderSeed].
 */
fun buildMix(
    songs: List<Song>,
    favorites: Set<Long>,
    playLog: List<PlayEvent>,
    seed: Long,
    orderSeed: Long,
    size: Int = 30,
    skips: List<MixSkip> = emptyList(),
    features: (Long) -> TrackFeatures? = { null }
): List<Song> {
    if (songs.isEmpty()) return emptyList()
    val nowMs = System.currentTimeMillis()
    val nowSec = nowMs / 1000L
    val byId = songs.associateBy { it.id }

    // Wiedergaben der letzten 30 Tage, jüngere zählen mehr
    val songPlays = HashMap<Long, Double>()
    val artistPlays = HashMap<String, Double>()
    val lastPlay = HashMap<Long, Long>()
    val events = ArrayList<PlayEvent>()
    for (e in playLog) {
        val age = nowMs - e.time
        if (age !in 0..MIX_WINDOW_MS) continue
        val s = byId[e.id] ?: continue   // ausgeblendete oder gelöschte Titel zählen nicht
        events += e
        val w = decay(age, 10.0)
        songPlays[s.id] = (songPlays[s.id] ?: 0.0) + w
        if (s.artist.isNotBlank()) artistPlays[s.artist] = (artistPlays[s.artist] ?: 0.0) + w
        if ((lastPlay[s.id] ?: 0L) < e.time) lastPlay[s.id] = e.time
    }
    val favArtists = HashMap<String, Int>()
    for (id in favorites) {
        val a = byId[id]?.artist?.takeIf { it.isNotBlank() } ?: continue
        favArtists[a] = (favArtists[a] ?: 0) + 1
    }
    // Wie viel Hörverlauf da ist (0 bis 1): Mit wenig Verlauf zählen Favoriten mehr, mit viel die tatsächlichen Wiedergaben
    val confidence = Math.min(1.0, events.size / 150.0)

    // Skips im Mix, zwei Wochen Halbwertszeit; ein Skip in den ersten Sekunden zählt doppelt
    val songSkips = HashMap<Long, Double>()
    val artistSkips = HashMap<String, Double>()
    for (k in skips) {
        val age = nowMs - k.time
        if (age !in 0..MIX_WINDOW_MS) continue
        val w = k.weight * decay(age, 14.0)
        songSkips[k.id] = (songSkips[k.id] ?: 0.0) + w
        val a = byId[k.id]?.artist?.takeIf { it.isNotBlank() } ?: continue
        artistSkips[a] = (artistSkips[a] ?: 0.0) + w
    }

    // Ankertitel für Ähnlichkeit und Klang: Favoriten und die am meisten gehörten (ohne stark übersprungene)
    val seedIds = HashSet<Long>()
    for (id in favorites) if (id in byId) seedIds += id
    for (entry in songPlays.entries.sortedByDescending { it.value }.take(25)) {
        if ((songSkips[entry.key] ?: 0.0) < 1.5) seedIds += entry.key
    }

    // Hörsitzungen: Was lief kurz vor oder nach einem Ankertitel? (Abstand höchstens 3 Titel, höchstens 20 Minuten Pause)
    val coListen = HashMap<Long, Double>()
    if (seedIds.isNotEmpty()) {
        val ev = events.sortedBy { it.time }
        for (i in ev.indices) {
            for (j in i + 1..Math.min(i + 3, ev.size - 1)) {
                if (ev[j].time - ev[j - 1].time > SESSION_GAP_MS) break
                val a = ev[i].id
                val b = ev[j].id
                if (a == b) continue
                val w = 1.0 / (j - i)
                if (a in seedIds) coListen[b] = (coListen[b] ?: 0.0) + w
                if (b in seedIds) coListen[a] = (coListen[a] ?: 0.0) + w
            }
        }
    }
    val seedAlbums = HashMap<String, Int>()
    for (id in seedIds) {
        val al = byId[id]?.album?.takeIf { it.isNotBlank() } ?: continue
        seedAlbums[al] = (seedAlbums[al] ?: 0) + 1
    }

    // Dein typischer Klang (erst ab 5 gemessenen Ankertiteln)
    var pW = 0.0; var pE = 0.0; var pB = 0.0
    var tW = 0.0; var tC = 0.0; var tS = 0.0
    var measured = 0
    for (id in seedIds) {
        val f = features(id) ?: continue
        if (f.failed) continue
        val w = (if (id in favorites) 2.0 else 1.0) + Math.min(songPlays[id] ?: 0.0, 5.0) * 0.5
        measured++
        pW += w; pE += w * f.energy; pB += w * f.brightness
        if (f.hasTempo) {
            val ang = 2.0 * Math.PI * tempoPos(f.bpm)
            tC += w * Math.cos(ang); tS += w * Math.sin(ang); tW += w
        }
    }
    val profile = if (measured >= 5 && pW > 0.0) {
        val hasT = tW > 0.0 && tC * tC + tS * tS > 1e-9
        var pos = if (hasT) Math.atan2(tS, tC) / (2.0 * Math.PI) else 0.0
        if (pos < 0.0) pos += 1.0
        MixProfile(pos, hasT, pE / pW, pB / pW, Math.min(1.0, measured / 15.0))
    } else null

    val favW = 3.0 - confidence            // Favoriten: 3,0 (wenig Verlauf) bis 2,0 (viel Verlauf)
    val playW = 0.3 + 0.3 * confidence     // Wiedergaben: 0,3 bis 0,6 je Wiedergabe
    val known = ArrayList<Pair<Song, Double>>()   // Favoriten und kürzlich gehörte
    val fresh = ArrayList<Pair<Song, Double>>()   // in 30 Tagen nicht gehört, keine Favoriten
    for (s in songs) {
        val isFav = s.id in favorites
        val plays = songPlays[s.id] ?: 0.0
        val artistScore = Math.min((artistPlays[s.artist] ?: 0.0) * 0.4 + (favArtists[s.artist] ?: 0) * 0.5, 5.0)
        val similar = Math.min(coListen[s.id] ?: 0.0, 4.0) * 0.5 + Math.min(seedAlbums[s.album] ?: 0, 3) * 0.25
        val sound = soundMatch(features(s.id), profile) * 2.0 * (profile?.confidence ?: 0.0)
        var w: Double
        if (isFav || plays > 0.0) {
            w = 1.0 + (if (isFav) favW else 0.0) + Math.min(plays, 6.0) * playW + artistScore * 0.8 + similar + sound
            w /= 1.0 + 0.9 * (songSkips[s.id] ?: 0.0)
            w /= 1.0 + 0.12 * Math.min(artistSkips[s.artist] ?: 0.0, 5.0)
            // Wiederholungsschutz: Was du gerade erst gehört hast, kommt seltener
            val last = lastPlay[s.id]
            if (last != null) {
                val since = nowMs - last
                if (since < 12 * HOUR_MS) w *= 0.35 else if (since < 48 * HOUR_MS) w *= 0.7
            }
            // gewichtetes Ziehen ohne Zurücklegen (Efraimidis-Spirakis) mit festem Zufallswert je Titel
            known += s to Math.pow(unitFor(seed, s.id), 1.0 / w)
        } else {
            val ageDays = if (s.dateAdded > 0L) Math.max(0.0, (nowSec - s.dateAdded) / 86400.0) else 365.0
            w = 0.5 + artistScore * 0.4 + similar + sound + 1.5 * Math.exp(-ageDays / 45.0)
            w /= 1.0 + 0.9 * (songSkips[s.id] ?: 0.0)
            fresh += s to Math.pow(unitFor(seed, s.id), 1.0 / w)
        }
    }

    val freshSlots = Math.min(Math.round(size * MIX_DISCOVER_SHARE).toInt(), fresh.size)
    val picked = ArrayList<Song>(size)
    for (c in known.sortedByDescending { it.second }.take(size - freshSlots)) picked += c.first
    for (c in fresh.sortedByDescending { it.second }.take(size - picked.size)) picked += c.first
    return flowOrder(picked, orderSeed, features)
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

fun sortSongs(list: List<Song>, mode: SortMode): List<Song> = when (mode) {
    SortMode.DATE -> list.sortedByDescending { it.dateAdded }
    SortMode.MODIFIED -> list.sortedByDescending { it.dateModified }
    SortMode.NAME -> list.sortedBy { it.title.lowercase() }
}

fun toMediaItems(songs: List<Song>): List<MediaItem> = songs.map {
    MediaItem.Builder()
        .setMediaId(it.id.toString())
        .setUri(it.uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()
        )
        .build()
}

/** Führt Arbeit mit niedriger Thread-Priorität aus, damit die Oberfläche (besonders auf schwachen Geräten) nicht ruckelt. */
private inline fun <T> lowPriority(block: () -> T): T {
    val tid = android.os.Process.myTid()
    val old = android.os.Process.getThreadPriority(tid)
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
    try {
        return block()
    } finally {
        android.os.Process.setThreadPriority(old)
    }
}

/**
 * Lässt Android die Tags geänderter Dateien neu einlesen (MediaStore speichert sie sonst nur beim ersten Scan).
 * force = true prüft alle Dateien, sonst nur Dateien, die seit dem letzten Durchlauf geändert wurden.
 * Liefert die Anzahl der neu eingelesenen Dateien.
 */
suspend fun rescanChanged(ctx: Context, force: Boolean): Int = withContext(Dispatchers.IO) {
    val p = prefs(ctx)
    val last = if (force) 0L else p.getLong("last_scan", 0L)
    val started = System.currentTimeMillis()
    val paths = mutableListOf<String>()
    lowPriority {
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (force || File(path).lastModified() > last) paths += path
            }
        }
    }
    if (paths.isNotEmpty()) {
        withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val left = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                    if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
        clearCoverCaches(if (force) ctx else null)
    }
    p.edit().putLong("last_scan", started).apply()
    paths.size
}

/* ---------- Tags direkt aus den Dateien lesen ---------- */

private class TagInfo(
    val mtime: Long, val size: Long,
    val title: String?, val artist: String?, val album: String?, val track: Int
)

private fun readTagCache(ctx: Context): MutableMap<Long, TagInfo> {
    val out = HashMap<Long, TagInfo>()
    val raw = prefs(ctx).getString("tag_cache", null) ?: return out
    try {
        val o = JSONObject(raw)
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            out[k.toLong()] = TagInfo(
                e.getLong("m"), e.getLong("s"),
                e.optString("t").ifEmpty { null },
                e.optString("a").ifEmpty { null },
                e.optString("b").ifEmpty { null },
                e.optInt("n", 0)
            )
        }
    } catch (e: Exception) {
        out.clear()
    }
    return out
}

private fun writeTagCache(ctx: Context, cache: Map<Long, TagInfo>) {
    val o = JSONObject()
    for ((id, t) in cache) {
        o.put(
            id.toString(),
            JSONObject().put("m", t.mtime).put("s", t.size)
                .put("t", t.title ?: "").put("a", t.artist ?: "").put("b", t.album ?: "")
                .put("n", t.track)
        )
    }
    prefs(ctx).edit().putString("tag_cache", o.toString()).apply()
}

private fun readTags(ctx: Context, uri: Uri, mtime: Long, size: Long): TagInfo {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        fun g(key: Int): String? = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        val track = g(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        val disc = g(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        TagInfo(
            mtime, size,
            g(MediaMetadataRetriever.METADATA_KEY_TITLE),
            g(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: g(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            g(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            if (disc > 0 && track > 0) disc * 1000 + track else track
        )
    } catch (e: Exception) {
        TagInfo(mtime, size, null, null, null, 0)
    } finally {
        try { r.release() } catch (e: Exception) { }
    }
}

/**
 * Überschreibt Titel, Künstler, Album und Tracknummer mit den echten Tags aus der Datei,
 * wenn die Datei neuer oder anders groß ist als beim letzten Scan von Android (die Datenbank also veraltet ist).
 * force = true liest alle Dateien direkt. Ergebnisse werden zwischengespeichert.
 * cacheOnly = true wendet nur den Zwischenspeicher an und liest keine Dateien (schnell, für die erste Anzeige).
 */
suspend fun refreshTagsFromFiles(
    ctx: Context,
    songs: List<Song>,
    force: Boolean,
    cacheOnly: Boolean = false
): List<Song> =
    withContext(Dispatchers.IO) { lowPriority {
        // Pfad und Zeitpunkt des letzten Android-Scans je Song
        val meta = HashMap<Long, Triple<String, Long, Long>>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                meta[c.getLong(0)] = Triple(path, c.getLong(2), c.getLong(3))
            }
        }
        val cache = readTagCache(ctx)
        var cacheChanged = false
        val out = ArrayList<Song>(songs.size)
        for (s in songs) {
            val m = meta[s.id]
            if (m == null) { out += s; continue }
            val f = File(m.first)
            val mtime = f.lastModified()
            if (mtime <= 0L) { out += s; continue }
            val size = f.length()
            val cached = cache[s.id]?.takeIf { it.mtime == mtime && it.size == size }
            val tags: TagInfo? = when {
                cacheOnly -> cached
                force -> readTags(ctx, s.uri, mtime, size).also { cache[s.id] = it; cacheChanged = true }
                cached != null -> cached
                // Datei neuer oder anders groß als beim letzten Android-Scan: Datenbank ist veraltet
                mtime / 1000 > m.second + 1 || size != m.third ->
                    readTags(ctx, s.uri, mtime, size).also { cache[s.id] = it; cacheChanged = true }
                else -> null
            }
            val base = if (mtime > 0L) s.copy(dateModified = mtime / 1000) else s
            out += if (tags == null) base else base.copy(
                title = tags.title ?: s.title,
                artist = tags.artist ?: s.artist,
                album = tags.album ?: s.album,
                track = if (tags.track > 0) tags.track else s.track
            )
        }
        if (cacheChanged) writeTagCache(ctx, cache)
        out
    } }

fun loadLibrary(ctx: Context): List<Song> {
    val list = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val folderField = MediaStore.Audio.Media.RELATIVE_PATH
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.DURATION,
        folderField
    )
    ctx.contentResolver.query(
        collection, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val folderCol = c.getColumnIndexOrThrow(folderField)
        val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val raw = c.getString(folderCol) ?: ""
            val folder = raw
            list += Song(
                id = id,
                title = c.getString(titleCol) ?: str(R.string.unknown),
                artist = c.getString(artistCol) ?: str(R.string.unknown),
                uri = ContentUris.withAppendedId(collection, id),
                folder = folder,
                album = c.getString(albumCol) ?: "",
                dateAdded = c.getLong(dateCol),
                dateModified = c.getLong(modCol),
                track = c.getInt(trackCol),
                fileName = c.getString(nameCol) ?: "",
                year = c.getInt(yearCol),
                durationMs = c.getLong(durCol)
            )
        }
    }
    return list
}

fun readShowAudioInfo(ctx: Context): Boolean =
    prefs(ctx).getBoolean("audio_info", true)

fun saveShowAudioInfo(ctx: Context, value: Boolean) {
    prefs(ctx).edit().putBoolean("audio_info", value).apply()
}

/** Liefert z. B. "FLAC · 912 kbps · 44.1 kHz" oder null, wenn nichts ermittelt werden konnte. */
fun loadAudioInfo(ctx: Context, song: Song): String? {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(ctx, song.uri, null)
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { fmt = f; break }
        }
        if (fmt == null) return null
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""

        var name: String? = null
        var size = -1L
        ctx.contentResolver.query(
            song.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = c.getLong(1)
            }
        }

        // Format nach Codec bestimmen (z. B. AAC statt M4A), Dateiendung nur als Fallback
        val ext = name?.substringAfterLast('.', "")?.uppercase().orEmpty()
        val format = when (mime.removePrefix("audio/")) {
            "mp4a-latm" -> "AAC"
            "alac" -> "ALAC"
            "mpeg" -> "MP3"
            "flac" -> "FLAC"
            "vorbis" -> "OGG"
            "opus" -> "OPUS"
            // "raw" meldet das Gerät auch für andere Dateien (z. B. FLAC) -> Endung entscheidet
            "raw" -> if (ext.isNotEmpty() && ext.length <= 5) ext else "WAV"
            else -> if (ext.isNotEmpty() && ext.length <= 5) ext
            else mime.removePrefix("audio/").uppercase()
        }

        var bitrate = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE) else 0
        if (bitrate <= 0 && size > 0 && fmt.containsKey(MediaFormat.KEY_DURATION)) {
            val sec = fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            if (sec > 0) bitrate = (size * 8 / sec).toInt()
        }

        val parts = mutableListOf(format)
        if (bitrate > 0) parts.add("${(bitrate + 500) / 1000} kbps")
        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            parts.add(
                if (sr % 1000 == 0) "${sr / 1000} kHz"
                else String.format(Locale.US, "%.1f kHz", sr / 1000.0)
            )
        }
        return parts.joinToString(" · ")
    } catch (e: Exception) {
        return null
    } finally {
        ex.release()
    }
}

/** Ergebnis eines M3U-Imports: Name der Playlist, gefundene Song-IDs, Anzahl der Einträge in der Datei. */
class M3uResult(val name: String, val ids: List<Long>, val total: Int)

private fun normKey(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()

/** Liest eine M3U/M3U8-Datei und ordnet die Einträge Songs der Bibliothek zu. */
fun importM3u(ctx: Context, uri: Uri, songs: List<Song>): M3uResult? {
    val rawBytes: ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
    if (rawBytes == null) return null
    val bytes: ByteArray = rawBytes

    val decoded: String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }
    val text = decoded.removePrefix("\uFEFF")

    var displayName: String? = null
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) displayName = c.getString(0)
    }
    val name = displayName?.substringBeforeLast('.')?.trim().orEmpty().ifEmpty { str(R.string.imported_playlist) }

    val byFile = songs.filter { it.fileName.isNotEmpty() }.groupBy { normKey(it.fileName) }
    val byTitle = songs.groupBy { normKey(it.title) }

    val ids = mutableListOf<Long>()
    var total = 0
    var info: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            info = line.substringAfter(',', "").trim().ifEmpty { null }
            continue
        }
        if (line.startsWith("#")) continue
        total++

        var path = line.removePrefix("file://")
        if (path.contains('%')) {
            path = try { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { path }
        }
        path = path.replace('\\', '/')
        val fileName = path.substringAfterLast('/')
        val dir = normKey(path.substringBeforeLast('/', "").trim('/'))

        // 1) Dateiname (bei mehreren Treffern passt der Ordner)
        var match: Song? = null
        val candidates = byFile[normKey(fileName)]
        if (candidates != null) {
            match = if (candidates.size == 1) candidates[0]
            else candidates.firstOrNull {
                val f = normKey(it.folder.trim('/'))
                f.isNotEmpty() && dir.endsWith(f)
            } ?: candidates[0]
        }
        // 2) Dateiname ohne Endung = Titel
        if (match == null) {
            val base = normKey(fileName.substringBeforeLast('.'))
            byTitle[base]?.let { if (it.size == 1) match = it[0] }
        }
        // 3) #EXTINF "Künstler - Titel"
        if (match == null && info != null) {
            val i = info!!
            val artistPart = normKey(i.substringBefore(" - ", ""))
            val titlePart = normKey(i.substringAfter(" - ", i))
            val list = byTitle[titlePart] ?: byTitle[normKey(i)]
            if (list != null) {
                match = list.firstOrNull {
                    artistPart.isNotEmpty() && normKey(it.artist).contains(artistPart)
                } ?: list.firstOrNull()
            }
        }
        match?.let { if (it.id !in ids) ids.add(it.id) }
        info = null
    }
    return M3uResult(name, ids, total)
}

/* ---------- Update-Prüfung über GitHub-Releases ---------- */

const val UPDATE_REPO = "IChibiNekoI/CN-Musik"

/** Neuere Version auf GitHub: Versionsnummer, Download-Link (APK, sonst Release-Seite). */
class UpdateInfo(val version: String, val url: String)

private fun versionParts(v: String): List<Int> =
    v.trim().removePrefix("v").removePrefix("V")
        .split(Regex("[^0-9]+"))
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }

private fun isNewerVersion(latest: String, current: String): Boolean {
    val a = versionParts(latest)
    val b = versionParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Über F-Droid installiert? Dann aktualisiert F-Droid die App, sie sucht selbst nicht nach Updates. */
private val FDROID_INSTALLERS = setOf(
    "org.fdroid.fdroid", "org.fdroid.basic", "com.machiav3lli.fdroid", "eu.bubu1.fdroidclassic"
)

private fun isInstalledFromFDroid(ctx: Context): Boolean = try {
    val installer = ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
    installer != null && installer in FDROID_INSTALLERS
} catch (e: Exception) {
    false
}

/** Fragt das neueste GitHub-Release ab. Gibt null zurück, wenn kein Update da ist oder die Abfrage scheitert. */
fun checkForUpdate(ctx: Context): UpdateInfo? {
    if (isInstalledFromFDroid(ctx)) return null
    return try {
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        if (current == null) return null
        val conn = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CN-Musik")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            if (tag.isEmpty() || !isNewerVersion(tag, current)) return null
            var url = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        url = a.optString("browser_download_url", url)
                        break
                    }
                }
            }
            if (url.isEmpty()) return null
            UpdateInfo(tag.removePrefix("v").removePrefix("V"), url)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
