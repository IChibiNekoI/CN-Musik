package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
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
