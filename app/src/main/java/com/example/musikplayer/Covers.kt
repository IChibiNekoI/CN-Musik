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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

/** Ab dieser Größe gilt ein Cover als Player-Cover: Es wird nur einmal geladen und dann von allen geteilt. */
private const val BIG_COVER_PX = 600

/**
 * Ein großes Cover pro Titel, unabhängig von der genauen Pixelzahl: Weicht die vorab geladene Größe um ein paar Pixel
 * von der im Player ab, wird es trotzdem gefunden (vorher gab es dann keinen Treffer und das Cover wurde zu spät geladen).
 */
private fun bigKey(songId: Long, ver: Int) = "${songId}_${ver}_big"

private fun bigCached(songId: Long, ver: Int, px: Int): Bitmap? =
    CoverCache.get(bigKey(songId, ver))?.takeIf { minOf(it.width, it.height) >= px * 0.85f }

private val bigLoadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val bigLoads = HashMap<String, Deferred<Bitmap?>>()

/**
 * Lädt ein großes Player-Cover genau einmal. Wollen Vorab-Laden und Anzeige gleichzeitig dasselbe Cover, warten beide
 * auf denselben Ladevorgang (vorher wurde es doppelt aus der Datei gelesen, bei großen Bildern in FLAC-Dateien sehr
 * aufwendig). Der Vorgang läuft auch zu Ende, wenn der Aufrufer abbricht: Das Cover liegt dann im Cache bereit.
 */
private suspend fun loadBigCover(ctx: Context, song: Song, ver: Int, px: Int): Bitmap? {
    val key = bigKey(song.id, ver)
    bigCached(song.id, ver, px)?.let { return it }
    val appCtx = ctx.applicationContext
    val job = synchronized(bigLoads) {
        bigLoads.getOrPut(key) {
            bigLoadScope.async {
                try {
                    bigCached(song.id, ver, px) ?: loadCoverBitmap(appCtx, song, ver, px)?.also {
                        it.prepareToDraw()   // Textur schon jetzt für die GPU vorbereiten
                        CoverCache.put(key, it)   // key = bigKey
                    }
                } finally {
                    synchronized(bigLoads) { bigLoads.remove(key) }
                }
            }
        }
    }
    return job.await()
}

/** Lädt das große Player-Cover schon vorab in den Cache, damit der Titelwechsel nicht auf das Laden warten muss */
suspend fun prewarmPlayerCover(ctx: android.content.Context, song: Song, px: Int) {
    if (px <= 0) return
    loadBigCover(ctx, song, TagEvents.coverVersions[song.id] ?: 0, px)
}

/* ---------- Cover-Cache auf der Platte ----------
 * Die Cover werden aus den Audiodateien herausgelesen, das ist aufwendig. Damit das nicht bei jedem
 * App-Start neu passiert, werden verkleinerte Cover als JPEG im Cache-Ordner abgelegt. Schlüssel enthält
 * die letzte Änderungszeit der Datei: wird die Datei (Tag oder Cover) geändert, entsteht automatisch ein neuer Eintrag. */

/**
 * Auch die großen Player-Cover kommen auf die Platte: Das Auslesen aus der Datei (besonders bei FLAC mit großem Bild)
 * dauert deutlich länger als das Laden der verkleinerten Kopie, deshalb passiert es nur einmal pro Titel.
 */
private const val DISK_COVER_MAX_PX = 1600

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

/**
 * Verkleinert ein zu großes Bild auf die gewünschte Größe. Hochauflösende Cover (z. B. in FLAC-Dateien) kommen teils
 * mit mehreren tausend Pixeln: Beim ersten Zeichnen muss die GPU so ein Bild erst hochladen, das hält die Oberfläche
 * kurz an (Animation friert ein und springt dann). In der passenden Größe ist das Hochladen kaum spürbar.
 */
private fun fitToSize(src: Bitmap, px: Int): Bitmap {
    val minSide = minOf(src.width, src.height)
    if (px <= 0 || minSide <= px * 1.15f) return src
    val scale = px.toFloat() / minSide
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    return try {
        Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
    } catch (e: Exception) {
        src
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
                val b = fitToSize(ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null), px)
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

/**
 * Mehrlagiger Schatten (Faktor der Basishöhe, Deckkraft): eng und dunkel direkt am Rand (Kontaktschatten),
 * dazu ein mittlerer und ein weiter, weicher Schatten. Wirkt deutlich plastischer als eine einzelne Lage.
 */
val CoverShadowLayers = listOf(0.2f to 0.60f, 0.55f to 0.42f, 1.2f to 0.30f)

fun Modifier.layeredShadow(base: Dp, shape: androidx.compose.ui.graphics.Shape): Modifier {
    var m = this
    for ((factor, alpha) in CoverShadowLayers) {
        val c = androidx.compose.ui.graphics.Color.Black.copy(alpha = alpha)
        m = m.shadow(base * factor, shape, clip = false, ambientColor = c, spotColor = c)
    }
    return m
}

@Composable
fun Cover(song: Song, size: Dp, corner: Dp = 12.dp, fallbackSize: Dp? = null, shadow: Dp = 0.dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val fallbackPx = fallbackSize?.let { with(LocalDensity.current) { it.roundToPx() } }
    // Sofort etwas anzeigen: erst das Bild in passender Größe, sonst die kleine Version aus dem Cache
    val ver = TagEvents.coverVersions[song.id] ?: 0   // ändert sich, wenn im Tag-Editor ein neues Cover gespeichert wurde
    val initial = remember(song.id, px, fallbackPx, ver) {
        (if (px > BIG_COVER_PX) bigCached(song.id, ver, px) else CoverCache.get("${song.id}_${ver}_$px"))
            ?: fallbackPx?.let { CoverCache.get("${song.id}_${ver}_$it") }
    }
    val bmp by produceState<Bitmap?>(initial, song.id, px, ver) {
        val key = "${song.id}_${ver}_$px"
        var b: Bitmap? = if (px > BIG_COVER_PX) bigCached(song.id, ver, px) else CoverCache.get(key)
        if (b == null) {
            if (px > BIG_COVER_PX) {
                b = loadBigCover(ctx, song, ver, px)
            } else {
                b = loadCoverBitmap(ctx, song, ver, px)
                if (b != null) CoverCache.put(key, b)
            }
        }
        // Farbe fürs Theme gleich mit ermitteln (winzig, läuft im Hintergrund), damit der Titelwechsel nicht warten muss
        if (b != null) withContext(Dispatchers.Default) { rememberSeed(song.id, ver, b) }
        value = b
    }
    val coverShape = RoundedCornerShape(coverRadius(LocalCoverStyle.current, size, corner))
    Box(
        Modifier
            .size(size)
            // Schatten außerhalb des Zuschnitts, sonst würde er mit abgeschnitten
            .then(
                if (shadow > 0.dp) Modifier.layeredShadow(shadow, coverShape) else Modifier
            )
            .clip(coverShape),
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
