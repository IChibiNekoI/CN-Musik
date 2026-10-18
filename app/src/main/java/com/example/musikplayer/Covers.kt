package com.example.musikplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlinx.coroutines.withContext

/** Großes Player-Cover vorab laden. Bei Problemen mit der Animation zum Vergleich auf false setzen. */
const val PREWARM_PLAYER_COVER = true

// nach Speichergröße begrenzt (32 MB), weil jetzt auch große Player-Cover darin liegen
private object CoverCache : LruCache<String, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Lädt das große Player-Cover schon vorab in den Cache, damit das Öffnen nicht auf das Laden warten muss */
suspend fun prewarmPlayerCover(ctx: android.content.Context, song: Song, px: Int) {
    if (px <= 0) return
    val key = "${song.id}_${TagEvents.coverVersions[song.id] ?: 0}_$px"
    if (CoverCache.get(key) != null) return
    val b = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
        } catch (e: Exception) {
            null
        }
    }
    if (b != null) {
        b.prepareToDraw()   // Textur schon jetzt für die GPU vorbereiten
        CoverCache.put(key, b)
    }
}

/* ---------- Cover-Cache auf der Platte ----------
 * Die Cover werden aus den Audiodateien herausgelesen, das ist aufwendig. Damit das nicht bei jedem
 * App-Start neu passiert, werden verkleinerte Cover als JPEG im Cache-Ordner abgelegt. Schlüssel enthält
 * die letzte Änderungszeit der Datei: wird die Datei (Tag oder Cover) geändert, entsteht automatisch ein neuer Eintrag. */

/** Nur kleine Cover (Listen, Kacheln, Miniplayer) auf die Platte; die großen Player-Cover nicht. */
private const val DISK_COVER_MAX_PX = 600

private const val DISK_COVER_MAX_BYTES = 120L * 1024 * 1024

/** Höchstens 3 Cover gleichzeitig aus Dateien extrahieren, sonst überlastet ein Schwung Zeilen die CPU. */
private val coverLoadGate = Semaphore(3)

private fun coverDir(ctx: Context): File = File(ctx.cacheDir, "covers").apply { mkdirs() }

private fun coverBase(song: Song, ver: Int) = "${song.id}_${song.dateModified}_$ver"

private fun saveCoverFile(file: File, b: Bitmap) {
    try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(file)) tmp.delete()
    } catch (e: Exception) {
        // Cache ist nur eine Beschleunigung, Fehler sind unkritisch
    }
}

/** Lädt ein Cover: erst von der Platte, sonst aus der Audiodatei (und legt es dann auf der Platte ab). */
suspend fun loadCoverBitmap(ctx: Context, song: Song, ver: Int, px: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val useDisk = px <= DISK_COVER_MAX_PX
        val dir = coverDir(ctx)
        val file = File(dir, "${coverBase(song, ver)}_$px.jpg")
        val none = File(dir, "${coverBase(song, ver)}.none")   // Marker: Datei hat kein Cover
        if (useDisk) {
            if (none.exists()) return@withContext null
            if (file.exists()) {
                val b = BitmapFactory.decodeFile(file.path)
                if (b != null) {
                    file.setLastModified(System.currentTimeMillis())
                    return@withContext b
                }
                file.delete()
            }
        }
        coverLoadGate.withPermit {
            try {
                val b = ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
                if (useDisk) saveCoverFile(file, b)
                b
            } catch (e: java.io.IOException) {
                // kein eingebettetes Cover: merken, damit nicht bei jedem Start erneut versucht wird
                if (useDisk) {
                    try { none.createNewFile() } catch (e2: Exception) { }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

/** Räumt den Cover-Cache auf der Platte auf, wenn er zu groß wird (älteste Einträge zuerst). */
fun trimCoverDiskCache(ctx: Context) {
    val files = coverDir(ctx).listFiles() ?: return
    var total = files.sumOf { it.length() }
    if (total <= DISK_COVER_MAX_BYTES) return
    for (f in files.sortedBy { it.lastModified() }) {
        if (total <= DISK_COVER_MAX_BYTES * 8 / 10) break
        total -= f.length()
        f.delete()
    }
}

/**
 * Leert die Cover-Zwischenspeicher im Arbeitsspeicher, damit geänderte Cover nach einem Neu-Einlesen neu geladen werden.
 * Mit ctx wird auch der Cache auf der Platte geleert (nur beim erzwungenen Neu-Einlesen).
 */
fun clearCoverCaches(ctx: Context? = null) {
    CoverCache.evictAll()
    clearSeedCache()
    if (ctx != null) coverDir(ctx).listFiles()?.forEach { it.delete() }
}

@Composable
fun Cover(song: Song, size: Dp, corner: Dp = 12.dp, fallbackSize: Dp? = null) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val fallbackPx = fallbackSize?.let { with(LocalDensity.current) { it.roundToPx() } }
    // Sofort etwas anzeigen: erst das Bild in passender Größe, sonst die kleine Version aus dem Cache
    val ver = TagEvents.coverVersions[song.id] ?: 0   // ändert sich, wenn im Tag-Editor ein neues Cover gespeichert wurde
    val initial = remember(song.id, px, fallbackPx, ver) {
        CoverCache.get("${song.id}_${ver}_$px") ?: fallbackPx?.let { CoverCache.get("${song.id}_${ver}_$it") }
    }
    val bmp by produceState<Bitmap?>(initial, song.id, px, ver) {
        val key = "${song.id}_${ver}_$px"
        var b: Bitmap? = CoverCache.get(key)
        if (b == null) {
            b = loadCoverBitmap(ctx, song, ver, px)
            if (b != null) CoverCache.put(key, b)
        }
        value = b
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(coverRadius(LocalCoverStyle.current, size, corner))),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                MusicNoteIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
