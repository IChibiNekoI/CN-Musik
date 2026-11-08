package com.example.musikplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/* ---------- Eigenes Playlist-Bild ----------
 * Das gewählte Bild wird quadratisch zugeschnitten, verkleinert und im App-Ordner abgelegt.
 * In der Playlist steht nur der Dateiname. Bei jeder Änderung entsteht ein neuer Name, damit Listen und Cache sofort umschalten. */

private const val PLAYLIST_COVER_PX = 600

/*
 * Playlist-ID und Bildname landen als Dateiname im App-Ordner. Beide können aus einer Backup-Datei stammen, also nur
 * schlichte Zeichen zulassen: keine Pfadtrenner, kein Punkt am Anfang (".", ".." oder versteckte Dateien).
 */
private val SAFE_COVER_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
private val SAFE_PLAYLIST_ID = Regex("[A-Za-z0-9_-]{1,64}")
private const val INVALID_COVER_NAME = "_invalid_"

internal fun isSafeCoverName(name: String): Boolean = SAFE_COVER_NAME.matches(name)

internal fun isSafePlaylistId(id: String): Boolean = SAFE_PLAYLIST_ID.matches(id)

/** Datei zu einem Bildnamen. Ein ungültiger Name ergibt eine Datei, die es nie gibt; der Zugriff bleibt immer im Bilder-Ordner. */
fun playlistCoverFile(ctx: Context, name: String): File =
    File(File(ctx.filesDir, "playlist_covers").apply { mkdirs() }, if (isSafeCoverName(name)) name else INVALID_COVER_NAME)

/** Liest das Bild, schneidet die Mitte quadratisch aus und speichert es. Gibt den Dateinamen zurück, bei Fehlern null. */
suspend fun savePlaylistCover(ctx: Context, uri: Uri, playlistId: String): String? = withContext(Dispatchers.IO) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > 2 * PLAYLIST_COVER_PX && bounds.outHeight / sample > 2 * PLAYLIST_COVER_PX) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = if (side > PLAYLIST_COVER_PX) Bitmap.createScaledBitmap(square, PLAYLIST_COVER_PX, PLAYLIST_COVER_PX, true) else square
        // Die ID geht in den Dateinamen: nur schlichte ASCII-Zeichen behalten
        val safeId = playlistId.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            .take(64).ifEmpty { "pl" }
        val name = "${safeId}_${System.currentTimeMillis()}.jpg"
        playlistCoverFile(ctx, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        name
    } catch (e: Exception) {
        null
    }
}

fun deletePlaylistCover(ctx: Context, name: String?) {
    if (name.isNullOrEmpty() || !isSafeCoverName(name)) return
    try { playlistCoverFile(ctx, name).delete() } catch (e: Exception) { }
}

private val playlistCoverCache = LruCache<String, ImageBitmap>(32)

/** Das eigene Bild einer Playlist, oder null (kein eigenes Bild, Datei fehlt oder noch am Laden) */
@Composable
fun rememberPlaylistCoverImage(cover: String?): State<ImageBitmap?> {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(cover?.let { playlistCoverCache.get(it) }, cover) {
        if (cover.isNullOrEmpty()) {
            value = null
        } else {
            val cached = playlistCoverCache.get(cover)
            if (cached != null) {
                value = cached
            } else {
                val img = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(playlistCoverFile(ctx, cover).path)?.asImageBitmap()
                }
                if (img != null) playlistCoverCache.put(cover, img)
                value = img
            }
        }
    }
}

/** Inhalt einer M3U-Datei: Pfad relativ zum Musikordner, so findet der Import die Titel wieder */
fun buildM3u(songs: List<Song>): String {
    val sb = StringBuilder("#EXTM3U\n")
    for (s in songs) {
        val secs = if (s.durationMs > 0) s.durationMs / 1000 else -1
        val label = if (s.artist.isNotBlank()) "${s.artist} - ${s.title}" else s.title
        sb.append("#EXTINF:").append(secs).append(',').append(label.replace('\n', ' ')).append('\n')
        val folder = s.folder.trim('/')
        val file = s.fileName.ifEmpty { s.title }
        sb.append(if (folder.isEmpty()) file else "$folder/$file").append('\n')
    }
    return sb.toString()
}
