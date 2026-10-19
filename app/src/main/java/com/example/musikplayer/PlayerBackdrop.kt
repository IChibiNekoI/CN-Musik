package com.example.musikplayer

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.AnimationVector1D

/**
 * Hintergrund des Players.
 * Farbverlauf: oben die Cover-Farbe, nach unten in den Hintergrund auslaufend.
 * Unschärfe: Das Cover wird einmal auf 48 x 48 Pixel verkleinert und weichgezeichnet, danach nur noch groß gezeichnet.
 * Das kostet beim Zeichnen praktisch nichts und funktioniert auf jeder Android-Version.
 */
@Composable
fun PlayerBackdrop(song: Song, mode: PlayerBackground, open: Animatable<Float, AnimationVector1D>) {
    if (mode == PlayerBackground.SOLID) return
    val scheme = MaterialTheme.colorScheme
    // blendet beim Öffnen des Players mit ein
    val fade = Modifier.graphicsLayer { alpha = ((open.value - 0.2f) / 0.6f).coerceIn(0f, 1f) }
    if (mode == PlayerBackground.GRADIENT) {
        Box(
            Modifier
                .fillMaxSize()
                .then(fade)
                .background(
                    Brush.verticalGradient(
                        0f to scheme.primaryContainer.copy(alpha = 0.65f),   // etwas abgedunkelt, damit der Player ruhig bleibt
                        0.65f to scheme.surface,
                        1f to scheme.surface
                    )
                )
        )
    } else {
        val ctx = LocalContext.current
        val ver = TagEvents.coverVersions[song.id] ?: 0
        val blurred by produceState<Bitmap?>(null, song.id, ver) {
            value = loadBlurredBackdrop(ctx, song)
        }
        Box(Modifier.fillMaxSize().then(fade)) {
            SoftCoverImage(blurred, Modifier.fillMaxSize())
            // Schleier in der Hintergrundfarbe, nach unten (zu Titel und Tasten) dunkler: bleibt ruhig und gut lesbar.
            // Liegt über beiden Bildern, damit er beim Überblenden nicht flackert.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to scheme.surface.copy(alpha = 0.5f),
                        1f to scheme.surface.copy(alpha = 0.78f)
                    )
                )
            )
        }
    }
}

/**
 * Zeigt ein Bild und blendet bei einem neuen Bild weich über: das alte bleibt voll sichtbar darunter,
 * das neue erscheint darüber und "setzt sich" dabei leicht (kleine Zoom-Bewegung).
 * Kein Durchscheinen des Untergrunds wie beim normalen Crossfade.
 */
@Composable
fun SoftCoverImage(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    var below by remember { mutableStateOf<Bitmap?>(null) }
    var top by remember { mutableStateOf(bitmap) }
    val a = remember { Animatable(1f) }
    LaunchedEffect(bitmap) {
        if (bitmap === top) return@LaunchedEffect
        below = top
        top = bitmap
        a.snapTo(0f)
        a.animateTo(1f, tween(Motion.COLOR, easing = Motion.Soft))
        below = null
    }
    Box(modifier) {
        below?.let { b ->
            Image(
                b.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // fehlt ein neues Bild (z. B. Unschärfe aus), blendet das alte aus
                    alpha = if (top != null) 1f else 1f - a.value
                },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High
            )
        }
        top?.let { t ->
            Image(
                t.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = a.value
                    val sc = 1.12f - 0.12f * a.value
                    scaleX = sc; scaleY = sc
                },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High
            )
        }
    }
}

private object BackdropCache : LruCache<String, Bitmap>(6)

/** Stark verschwommenes Cover (48 x 48 Pixel) für Player und Menüs. Wird zwischengespeichert. */
suspend fun loadBlurredBackdrop(ctx: Context, song: Song): Bitmap? = withContext(Dispatchers.IO) {
    val ver = TagEvents.coverVersions[song.id] ?: 0
    val key = "${song.id}_$ver"
    BackdropCache.get(key)
        ?: loadCoverBitmap(ctx, song, ver, 96)?.let { blurSmall(it) }?.also { BackdropCache.put(key, it) }
}

/** Verkleinert das Bild auf 48 x 48 Pixel und zeichnet es sehr stark weich (3 x Box-Blur mit Radius 5). */
private fun blurSmall(src: Bitmap): Bitmap {
    val size = 48
    val small = Bitmap.createScaledBitmap(src, size, size, true)
    val px = IntArray(size * size)
    small.getPixels(px, 0, size, 0, 0, size, size)
    repeat(3) { boxBlur(px, size, size, 5) }
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, size, 0, 0, size, size)
    return out
}

private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val d = 2 * r + 1
    val tmp = IntArray(px.size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = px[y * w + (x + k).coerceIn(0, w - 1)]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            tmp[y * w + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
        }
    }
    for (y in 0 until h) {
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
            }
            px[y * w + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
        }
    }
}
