package com.example.musikplayer

import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import androidx.core.graphics.ColorUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.withContext

/** Dominanter Farbton eines Covers (HSL). */
private class CoverSeed(val h: Float, val s: Float, val neutral: Boolean)

private object SeedCache : LruCache<String, CoverSeed>(100)

/**
 * Merkt sich die Cover-Farbe schon, sobald irgendwo (Liste, Kacheln) das Cover geladen wurde.
 * Beim Titelwechsel ist die Farbe dann sofort da und der Übergang startet ohne Wartezeit.
 */
fun rememberSeed(songId: Long, ver: Int, bmp: Bitmap) {
    val key = "${songId}_$ver"
    if (SeedCache.get(key) != null) return
    seedFromBitmap(bmp)?.let { SeedCache.put(key, it) }
}

/** Zustand des Players, den das Theme kennen muss */
object PlayerUiState {
    var queueOpen by androidx.compose.runtime.mutableStateOf(false)
}

/** Leert den Zwischenspeicher der Cover-Farben. */
fun clearSeedCache() = SeedCache.evictAll()

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** Ermittelt den dominanten, kräftigen Farbton des Covers. */
private fun seedFromBitmap(src: Bitmap): CoverSeed? {
    return try {
        val n = 48
        val bmp = Bitmap.createScaledBitmap(src, n, n, true)
        val pixels = IntArray(n * n)
        bmp.getPixels(pixels, 0, n, 0, 0, n, n)
        val buckets = 24
        val weight = FloatArray(buckets)
        val satSum = FloatArray(buckets)
        val buf = FloatArray(3)
        for (px in pixels) {
            ColorUtils.colorToHSL(px, buf)
            val l = buf[2]
            if (l < 0.08f || l > 0.96f) continue
            val w = buf[1] * (1f - kotlin.math.abs(2f * l - 1f))
            val b = ((buf[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[b] += w
            satSum[b] += buf[1] * w
        }
        var best = 0
        for (i in 1 until buckets) if (weight[i] > weight[best]) best = i
        val neutral = weight[best] < 20f
        val h = (best + 0.5f) * (360f / buckets)
        val s = if (neutral) 0f else satSum[best] / weight[best]
        CoverSeed(h, s, neutral)
    } catch (e: Exception) {
        null
    }
}

/** Alle Farben, die das Cover-Theme überschreibt (feste Reihenfolge, damit man sie überblenden kann). */
private class Tones(val v: List<Color>)

/**
 * Zeitversatz je Farbe (Anteil der Gesamtdauer): Akzente (Tasten, Regler) wechseln zuerst,
 * danach folgen die Texte, zuletzt die Flächen. So rollt die neue Farbe wie eine Welle ins Bild.
 */
private fun toneDelay(i: Int): Float = when (i) {
    in 9..16 -> 0f        // primary / secondary und deren Container
    1, 2 -> 0.12f         // Texte
    17, 18 -> 0.12f       // Linien
    else -> 0.3f          // Hintergründe und Flächen
}

private const val TONE_WINDOW = 0.7f

/**
 * Überblendung in Oklab (Compose-Standard).
 * [wave] = true: jede Farbe mit eigenem Zeitversatz und sehr weichem Start (für den Unschärfe-Hintergrund, der die Flächen verdeckt).
 * [wave] = false: alle Farben starten sofort gemeinsam, damit der Wechsel bei einfarbigem Hintergrund gleich sichtbar beginnt.
 */
private fun lerpTones(a: Tones, b: Tones, f: Float, wave: Boolean) =
    Tones(List(a.v.size) {
        val local = if (wave) ((f - toneDelay(it)) / TONE_WINDOW).coerceIn(0f, 1f) else f
        val eased = if (wave) Motion.Soft.transform(local) else Motion.Ease.transform(local)
        androidx.compose.ui.graphics.lerp(a.v[it], b.v[it], eased)
    })

private fun tonesOf(c: ColorScheme) = Tones(
    listOf(
        c.surface, c.onSurface, c.onSurfaceVariant, c.surfaceVariant,
        c.surfaceContainerLowest, c.surfaceContainerLow, c.surfaceContainer,
        c.surfaceContainerHigh, c.surfaceContainerHighest,
        c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer,
        c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer,
        c.outline, c.outlineVariant
    )
)

private fun Tones.applyTo(base: ColorScheme): ColorScheme = base.copy(
    background = v[0], surface = v[0], onBackground = v[1], onSurface = v[1],
    onSurfaceVariant = v[2], surfaceVariant = v[3],
    surfaceContainerLowest = v[4], surfaceContainerLow = v[5], surfaceContainer = v[6],
    surfaceContainerHigh = v[7], surfaceContainerHighest = v[8],
    primary = v[9], onPrimary = v[10], primaryContainer = v[11], onPrimaryContainer = v[12],
    secondary = v[13], onSecondary = v[14],
    secondaryContainer = v[15], onSecondaryContainer = v[16],
    outline = v[17], outlineVariant = v[18]
)

private fun tonesFromSeed(seed: CoverSeed, dark: Boolean): Tones {
    val h = seed.h
    val s = seed.s
    val sp = if (seed.neutral) 0f else s.coerceIn(0.5f, 0.95f)
    return if (dark) {
        val sb = (s * 0.6f).coerceAtMost(0.55f)
        val sm = (s * 0.4f).coerceAtMost(0.4f)
        Tones(
            listOf(
                hsl(h, sb, 0.18f), hsl(h, sm, 0.95f), hsl(h, sm, 0.80f), hsl(h, sm, 0.28f),
                hsl(h, sb, 0.14f), hsl(h, sb, 0.20f), hsl(h, sb, 0.22f),
                hsl(h, sb, 0.26f), hsl(h, sb, 0.30f),
                hsl(h, sp, 0.80f), hsl(h, sp * 0.8f, 0.12f), hsl(h, sp * 0.6f, 0.34f), hsl(h, sm, 0.92f),
                hsl(h, sm, 0.80f), hsl(h, sm, 0.15f), hsl(h, sm, 0.34f), hsl(h, sm, 0.94f),
                hsl(h, sm, 0.55f), hsl(h, sm, 0.38f)
            )
        )
    } else {
        val sb = (s * 0.6f).coerceAtMost(0.6f)
        val sm = (s * 0.4f).coerceAtMost(0.4f)
        Tones(
            listOf(
                hsl(h, sb, 0.96f), hsl(h, sm, 0.10f), hsl(h, sm, 0.30f), hsl(h, sb, 0.88f),
                hsl(h, sb, 0.995f), hsl(h, sb, 0.95f), hsl(h, sb, 0.93f),
                hsl(h, sb, 0.91f), hsl(h, sb, 0.89f),
                hsl(h, sp, 0.38f), hsl(h, sp, 0.98f), hsl(h, sp, 0.88f), hsl(h, sm, 0.15f),
                hsl(h, sm, 0.35f), hsl(h, sm, 0.98f), hsl(h, sm, 0.86f), hsl(h, sm, 0.15f),
                hsl(h, sm, 0.45f), hsl(h, sm, 0.80f)
            )
        )
    }
}

/**
 * Färbt den Inhalt passend zum Cover von [song] ein (weiche Überblendung beim Wechsel).
 * Ohne Song bleibt das normale Theme erhalten.
 */
@Composable
fun CoverTheme(song: Song?, dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val coverVer = song?.let { TagEvents.coverVersions[it.id] } ?: 0
    val seed by produceState<CoverSeed?>(null, song?.id, coverVer) {
        val sg = song
        val seedKey = "${sg?.id}_$coverVer"
        value = if (sg == null) null else SeedCache.get(seedKey) ?: withContext(Dispatchers.IO) {
            try {
                // aus dem Platten-Cache (schnell), nur im Notfall direkt aus der Datei
                (loadCoverBitmap(ctx, sg, coverVer, 96)
                    ?: ctx.contentResolver.loadThumbnail(sg.uri, Size(96, 96), null))
                    ?.let { seedFromBitmap(it) }
            } catch (e: Exception) {
                null
            }
        }?.also { SeedCache.put(seedKey, it) }
    }

    val base = MaterialTheme.colorScheme
    val sd = seed
    val target = remember(sd, dark, base) {
        if (sd != null) tonesFromSeed(sd, dark) else tonesOf(base)
    }
    // Mit Unschärfe darf der Wechsel lang und verspielt sein, bei einfarbigem Hintergrund (und Verlauf) geht er flotter
    // Bei geöffneter Warteschlange liegt eine einfarbige Fläche im Bild: alle Farben sofort gemeinsam wechseln, keine Welle
    val wave = CornerSettings.playerBg == PlayerBackground.BLUR && !PlayerUiState.queueOpen
    val duration = if (wave) Motion.COLOR else Motion.COLOR_QUICK
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        // Mitten im Wechsel (schnelles Weiterschalten): ab der aktuell sichtbaren Mischung weiter überblenden
        from = lerpTones(from, to, t.value, wave)
        to = target
        t.snapTo(0f)
        t.animateTo(1f, androidx.compose.animation.core.tween(duration, easing = androidx.compose.animation.core.LinearEasing))
    }
    val shown = lerpTones(from, to, t.value, wave)

    MaterialTheme(
        colorScheme = shown.applyTo(base),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
