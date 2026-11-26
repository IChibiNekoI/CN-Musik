package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 */
@Composable
fun PlayerParticles(open: Animatable<Float, AnimationVector1D>) {
    val particles = remember { makeParticles() }
    var seconds by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                seconds = (now - start) / 1_000_000_000f
            }
        }
    }
    val plain = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            // blendet wie der Hintergrund beim Öffnen des Players ein
            .graphicsLayer { alpha = ((open.value - 0.2f) / 0.6f).coerceIn(0f, 1f) }
    ) {
        val t = seconds   // nur hier gelesen: ändert sich pro Bild, löst aber nur ein neues Zeichnen aus
        val w = size.width
        val h = size.height
        val dp = density
        for (p in particles) {
            // steigt auf und kommt unten wieder herein (Rest der Division durch 1)
            val rise = (p.y - p.speed * t) % 1f
            val y = (if (rise < 0f) rise + 1f else rise) * h
            val x = (p.x + sin(t * p.swayFreq + p.phase) * p.sway) * w
            val twinkle = 0.55f + 0.45f * sin(t * p.twinkle + p.phase)
            // zum oberen und unteren Rand hin ausblenden, damit sie nicht aufpoppen
            val edge = minOf(y / (h * 0.12f), (h - y) / (h * 0.12f), 1f).coerceAtLeast(0f)
            val color = (if (p.accent) accent else plain).copy(alpha = (p.alpha * twinkle * edge).coerceIn(0f, 1f))
            drawCircle(color, radius = p.radiusDp * dp, center = Offset(x, y))
        }
    }
}
