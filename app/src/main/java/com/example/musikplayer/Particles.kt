package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Eine Position und Bewegung pro Partikel, alles relativ zur Größe (0..1), damit es in jeder Größe passt. */
private class Particle(
    val x: Float,          // Ausgangsposition links/rechts
    val y: Float,          // Ausgangsposition oben/unten
    val speed: Float,      // Aufstieg in Bildschirmhöhen pro Sekunde
    val sway: Float,       // Pendeln zur Seite (Anteil der Breite)
    val swayFreq: Float,   // Geschwindigkeit des Pendelns
    val radiusDp: Float,   // Größe
    val alpha: Float,      // Grundhelligkeit
    val twinkle: Float,    // Geschwindigkeit des Funkelns
    val phase: Float,      // Versatz, damit nicht alle gleichzeitig funkeln
    val accent: Boolean    // in Akzentfarbe statt in Textfarbe
)

private const val PARTICLE_COUNT = 36

/**
 * Bereiche, in denen keine Partikel zu sehen sein sollen (Kopfzeile mit den Symbolen, Titel mit Herz, Fortschrittsbalken und Tastenreihe),
 * so wie sie beim Cover von selbst verdeckt sind. Rechtecke in Bildschirmkoordinaten ([Rect.Zero] = nicht gemessen).
 */
@Stable
class ParticleKeepOut {
    var header by mutableStateOf(Rect.Zero)
    var title by mutableStateOf(Rect.Zero)
    var progress by mutableStateOf(Rect.Zero)
    var controls by mutableStateOf(Rect.Zero)
}

/** 0 mitten im Rechteck und am Rand, steigt weich auf 1 im Abstand [margin]. Ohne gemessenes Rechteck immer 1. */
private fun clearance(r: Rect, x: Float, y: Float, margin: Float): Float {
    if (r.width <= 0f || r.height <= 0f) return 1f
    val dx = maxOf(r.left - x, 0f, x - r.right)
    val dy = maxOf(r.top - y, 0f, y - r.bottom)
    val f = (kotlin.math.sqrt(dx * dx + dy * dy) / margin).coerceIn(0f, 1f)
    return f * f * (3f - 2f * f)
}

private fun makeParticles(): List<Particle> {
    val r = Random(7)   // feste Zufallsfolge: sieht bei jedem Öffnen gleich verteilt aus
    return List(PARTICLE_COUNT) {
        Particle(
            x = r.nextFloat(),
            y = r.nextFloat(),
            speed = 0.012f + r.nextFloat() * 0.03f,
            sway = 0.01f + r.nextFloat() * 0.03f,
            swayFreq = 0.3f + r.nextFloat() * 0.5f,
            radiusDp = 1.2f + r.nextFloat() * 2.6f,
            alpha = 0.18f + r.nextFloat() * 0.3f,
            twinkle = 0.6f + r.nextFloat() * 1.2f,
            phase = r.nextFloat() * (2f * PI.toFloat()),
            accent = r.nextFloat() < 0.4f
        )
    }
}

/**
 * Langsam aufsteigende, leicht funkelnde Punkte über dem ganzen Player-Hintergrund (optional, [CornerSettings.playerParticles]).
 * Die Position jedes Punktes wird nur aus der Zeit berechnet, es gibt also keinen Zustand pro Punkt und
 * keinen Neuaufbau der Oberfläche: pro Bild wird nur gezeichnet. Liegt hinter den Bedienelementen und reagiert nicht auf Berührung.
 *
 * Nur bei laufender Musik ([playing]): beim Pausieren blenden sie weich aus, beim Abspielen weich wieder ein.
 * Sind sie ausgeblendet, läuft keine Animation mehr (kein Stromverbrauch).
 */
@Composable
fun PlayerParticles(open: Animatable<Float, AnimationVector1D>, playing: Boolean, keepOut: ParticleKeepOut) {
    val particles = remember { makeParticles() }
    var seconds by remember { mutableStateOf(0f) }
    // 1 = Musik läuft, 0 = pausiert (weicher Übergang)
    val presence by animateFloatAsState(if (playing) 1f else 0f, tween(700), label = "particlesPresence")
    val active = playing || presence > 0.001f
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // Zeit läuft von dort weiter, wo sie stehen geblieben ist: die Punkte springen beim Wiederstart nicht
                if (last != 0L) seconds += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    if (!active) return
    // Lage dieser Zeichenfläche auf dem Bildschirm, um die Bereiche der Bedienelemente umzurechnen
    var origin by remember { mutableStateOf(Offset.Zero) }
    val plain = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // blendet wie der Hintergrund beim Öffnen des Players ein
            .graphicsLayer { alpha = ((open.value - 0.2f) / 0.6f).coerceIn(0f, 1f) * presence }
    ) {
        val t = seconds   // nur hier gelesen: ändert sich pro Bild, löst aber nur ein neues Zeichnen aus
        val w = size.width
        val h = size.height
        val dp = density
        val margin = 24f * dp   // so weit vor Balken und Tasten blenden die Punkte schon aus
        val avoidHeader = keepOut.header.translate(-origin)
        val avoidTitle = keepOut.title.translate(-origin)
        val avoidProgress = keepOut.progress.translate(-origin)
        val avoidControls = keepOut.controls.translate(-origin)
        for (p in particles) {
            // steigt auf und kommt unten wieder herein (Rest der Division durch 1)
            val rise = (p.y - p.speed * t) % 1f
            val y = (if (rise < 0f) rise + 1f else rise) * h
            val x = (p.x + sin(t * p.swayFreq + p.phase) * p.sway) * w
            val twinkle = 0.55f + 0.45f * sin(t * p.twinkle + p.phase)
            // zum oberen und unteren Rand hin ausblenden, damit sie nicht aufpoppen
            val edge = minOf(y / (h * 0.12f), (h - y) / (h * 0.12f), 1f).coerceAtLeast(0f)
            // über Kopfzeile, Titel, Fortschrittsbalken und Tasten nicht sichtbar
            val clear = clearance(avoidHeader, x, y, margin) * clearance(avoidTitle, x, y, margin) *
                clearance(avoidProgress, x, y, margin) * clearance(avoidControls, x, y, margin)
            val color = (if (p.accent) accent else plain).copy(alpha = (p.alpha * twinkle * edge * clear).coerceIn(0f, 1f))
            drawCircle(color, radius = p.radiusDp * dp, center = Offset(x, y))
        }
    }
}
