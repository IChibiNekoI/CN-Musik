package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
import org.json.JSONObject

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
