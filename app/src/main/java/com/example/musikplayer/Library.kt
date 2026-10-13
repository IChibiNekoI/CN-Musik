package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.OpenableColumns
import java.util.Locale
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
                track = c.getInt(trackCol)
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
