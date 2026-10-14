package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
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
import android.os.Build
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

enum class SortMode { DATE, NAME }

data class Playlist(val id: String, val name: String, val songIds: List<Long>)

private fun prefs(ctx: Context) =
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

fun readSort(ctx: Context): SortMode =
    if (prefs(ctx).getString("sort", "date") == "name") SortMode.NAME else SortMode.DATE

fun saveSort(ctx: Context, mode: SortMode) {
    prefs(ctx).edit().putString("sort", if (mode == SortMode.NAME) "name" else "date").apply()
}

/** Darstellung einer Übersicht: Liste oder Kacheln (2 oder 3 nebeneinander). */
data class ViewStyle(val grid: Boolean = false, val cols: Int = 2)

fun ViewStyle.label(): String = if (!grid) "Liste" else "Kacheln · $cols nebeneinander"

private fun encodeStyle(v: ViewStyle): String = if (!v.grid) "list" else "grid${v.cols}"

private fun decodeStyle(s: String?): ViewStyle = when (s) {
    "grid2" -> ViewStyle(true, 2)
    "grid3" -> ViewStyle(true, 3)
    else -> ViewStyle()
}

/** true = ChibiNeko-Logo statt des Textes "Musik" in der Kopfzeile. */
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

fun readFolderSet(ctx: Context): Set<String>? =
    prefs(ctx).getStringSet("folders", null)?.toSet()

fun saveFolderSet(ctx: Context, set: Set<String>) {
    prefs(ctx).edit().putStringSet("folders", set).apply()
}

fun readPlaylists(ctx: Context): List<Playlist> {
    val raw = prefs(ctx).getString("playlists", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("songs")
            Playlist(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getLong(it) })
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
        arr.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", ids))
    }
    prefs(ctx).edit().putString("playlists", arr.toString()).apply()
}

fun sortSongs(list: List<Song>, mode: SortMode): List<Song> = when (mode) {
    SortMode.DATE -> list.sortedByDescending { it.dateAdded }
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
    if (paths.isNotEmpty()) {
        withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val left = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ ->
                    if (left.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
        clearCoverCaches()
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
 */
suspend fun refreshTagsFromFiles(ctx: Context, songs: List<Song>, force: Boolean): List<Song> =
    withContext(Dispatchers.IO) {
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
                force -> readTags(ctx, s.uri, mtime, size).also { cache[s.id] = it; cacheChanged = true }
                cached != null -> cached
                // Datei neuer oder anders groß als beim letzten Android-Scan: Datenbank ist veraltet
                mtime / 1000 > m.second + 1 || size != m.third ->
                    readTags(ctx, s.uri, mtime, size).also { cache[s.id] = it; cacheChanged = true }
                else -> null
            }
            out += if (tags == null) s else s.copy(
                title = tags.title ?: s.title,
                artist = tags.artist ?: s.artist,
                album = tags.album ?: s.album,
                track = if (tags.track > 0) tags.track else s.track
            )
        }
        if (cacheChanged) writeTagCache(ctx, cache)
        out
    }

fun loadLibrary(ctx: Context): List<Song> {
    val list = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val folderField = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DISPLAY_NAME,
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
        val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val folderCol = c.getColumnIndexOrThrow(folderField)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val raw = c.getString(folderCol) ?: ""
            val folder = if (Build.VERSION.SDK_INT >= 29) raw else raw.substringBeforeLast('/', "")
            list += Song(
                id = id,
                title = c.getString(titleCol) ?: "Unbekannt",
                artist = c.getString(artistCol) ?: "Unbekannt",
                uri = ContentUris.withAppendedId(collection, id),
                folder = folder,
                album = c.getString(albumCol) ?: "",
                dateAdded = c.getLong(dateCol),
                track = c.getInt(trackCol),
                fileName = c.getString(nameCol) ?: ""
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
    val name = displayName?.substringBeforeLast('.')?.trim().orEmpty().ifEmpty { "Importierte Playlist" }

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
