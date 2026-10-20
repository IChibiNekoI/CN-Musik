package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Macht aus einer Datei, die von außen geöffnet wurde ("Öffnen mit" im Dateimanager), einen Titel für den Player.
 * Ist die Datei in der Bibliothek, wird genau dieser Titel genommen (mit Cover und allen Tags).
 * Sonst entsteht ein eigener Titel direkt aus der Datei.
 */
suspend fun resolveExternalSong(ctx: Context, uri: Uri, library: List<Song>): Song = withContext(Dispatchers.IO) {
    // 1) Adresse aus der Android-Mediendatenbank: Nummer passt direkt zur Bibliothek
    if (uri.authority == "media") {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
        library.firstOrNull { it.id == id }?.let { return@withContext it }
    }

    var name: String? = null
    if (uri.scheme == "content") {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
    }
    val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()

    // 2) Gleicher Dateiname in der Bibliothek (nur wenn eindeutig, sonst wird genau diese Datei gespielt)
    if (fileName.isNotEmpty()) {
        val same = library.filter { it.fileName.equals(fileName, ignoreCase = true) }
        if (same.size == 1) return@withContext same[0]
    }

    // 3) Eigener Titel aus der Datei
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        } finally {
            r.release()
        }
    }
    Song(
        // negative Nummer, damit sie nie mit einem Titel der Bibliothek zusammenfällt
        id = -((uri.toString().hashCode().toLong() and 0x7fffffffL) + 2L),
        title = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.').ifEmpty { fileName },
        artist = artist?.takeIf { it.isNotBlank() }.orEmpty(),
        uri = uri,
        folder = "",
        album = album?.takeIf { it.isNotBlank() }.orEmpty(),
        fileName = fileName
    )
}
