package com.example.musikplayer

import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ---------- Titel-Infos ---------- */

private data class SongDetails(
    val albumArtist: String?,
    val durationMs: Long,
    val addedSec: Long,
    val modifiedSec: Long
)

private fun loadSongDetails(ctx: android.content.Context, song: Song): SongDetails {
    var albumArtist: String? = null
    var duration = 0L
    var added = song.dateAdded
    var modified = 0L
    try {
        ctx.contentResolver.query(
            song.uri,
            arrayOf(
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DURATION
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                added = c.getLong(0)
                modified = c.getLong(1)
                duration = c.getLong(2)
            }
        }
    } catch (e: Exception) {
    }
    // Albumkünstler (und Dauer als Reserve) direkt aus den Tags der Datei
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, song.uri)
        albumArtist = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (duration <= 0L) {
            duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }
    } catch (e: Exception) {
    } finally {
        try { r.release() } catch (e: Exception) { }
    }
    return SongDetails(albumArtist, duration, added, modified)
}

private fun fmtDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun fmtDate(sec: Long): String =
    if (sec <= 0L) "–" else java.text.DateFormat
        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(sec * 1000))

@Composable
fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val details by produceState<SongDetails?>(null, song.id) {
        value = withContext(Dispatchers.IO) { loadSongDetails(ctx, song) }
    }
    val d = details
    val rows = listOf(
        "Titel" to song.title.ifBlank { "–" },
        "Künstler" to song.artist.ifBlank { "–" },
        "Album" to song.album.ifBlank { "–" },
        "Albumkünstler" to (if (d == null) "…" else d.albumArtist ?: "–"),
        "Dauer" to (if (d == null) "…" else if (d.durationMs > 0) fmtDuration(d.durationMs) else "–"),
        "Datum hinzugefügt" to (if (d == null) "…" else fmtDate(d.addedSec)),
        "Datum modifiziert" to (if (d == null) "…" else fmtDate(d.modifiedSec))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Titel-Infos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}
