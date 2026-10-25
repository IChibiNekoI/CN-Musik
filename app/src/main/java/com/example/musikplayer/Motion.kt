package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.InteractionSource
import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Gemeinsame Bewegungssprache der App: verspielt, aber nicht übertrieben.
 * Alle Animationen nutzen diese Zeiten und Federn, damit sich alles gleich anfühlt.
 */
object Motion {
    /** kurze Rückmeldung, Ausblenden */
    const val FAST = 160
    /** normale Übergänge (Seiten, Tabs, Cover) */
    const val MEDIUM = 300
    /** große Bewegungen */
    const val SLOW = 420

    /** Farbwechsel beim Titelwechsel (Hintergrund, Tasten, Texte): schnell und flüssig */
    const val COLOR = 320

    /** Farbwechsel bei einfarbigem Hintergrund: flotter */
    const val COLOR_QUICK = 260

    val Ease = FastOutSlowInEasing

    /** Sehr sanfter Start und langes, weiches Ausklingen (für Farb- und Hintergrundwechsel) */
    val Soft = CubicBezierEasing(0.4f, 0f, 0.1f, 1f)

    /** Federt kurz über das Ziel hinaus und beruhigt sich dann */
    fun <T> pop(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 500f)
}

/**
 * Antippen: Das Element drückt sich leicht ein und federt beim Loslassen zurück.
 * Ersetzt den grauen Kreis (Ripple) bei Symbolen und Tasten.
 */
fun Modifier.bounceClick(
    pressedScale: Float = 0.9f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    // Beim Drücken zusätzlich ein Stück zur Seite schieben (z. B. Weiter-Taste nach rechts), federt mit Überschwinger zurück
    nudgeX: Dp = 0.dp,
    nudgeY: Dp = 0.dp,
    // Beim Drücken um so viele Grad drehen (z. B. Zahnrad), federt mit Überschwinger zurück
    rotate: Float = 0f,
    // false = kein Ticken (für Listenzeilen und Kacheln)
    haptic: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val ctx = LocalContext.current
    // 0 = Ruhe, 1 = ganz eingedrückt; beim Zurückfedern kurz negativ (= Überschwinger über die Ruhelage hinaus)
    val press = rememberPressProgress(source)
    this
        .graphicsLayer {
            val p = press.value
            val scale = 1f + (pressedScale - 1f) * p
            scaleX = scale
            scaleY = scale
            if (nudgeX != 0.dp) translationX = p * nudgeX.toPx()
            if (nudgeY != 0.dp) translationY = p * nudgeY.toPx()
            if (rotate != 0f) rotationZ = p * rotate
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = {
                if (haptic) Haptics.click(ctx)   // kurzer Klick (nur wenn in den Einstellungen eingeschaltet)
                onClick()
            }
        )
}

/** So lange (ms) bleibt ein Element mindestens eingedrückt, damit auch ein ganz kurzes Antippen sichtbar reagiert */
private const val PRESS_MIN_HOLD_MS = 90L

/**
 * Bewegung beim Berühren, wie ein Gummiband mit Gewicht: Beim Drücken gibt das Element schnell nach, beim Loslassen
 * schwingt es schwer und mit Überschwinger zurück. Auch ein sehr kurzes Antippen drückt es sichtbar ein: Das Eindrücken
 * läuft mindestens [PRESS_MIN_HOLD_MS] weiter, bevor es zurückfedert. Die Ereignisse werden einzeln verarbeitet, sodass
 * Drücken und Loslassen innerhalb eines Frames nicht verschluckt werden.
 *
 * Ergebnis: 0 = Ruhe, 1 = ganz eingedrückt, kurz negativ beim Überschwingen. Nur beim Zeichnen lesen.
 */
@Composable
fun rememberPressProgress(source: InteractionSource): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(source) {
        coroutineScope {
            var job: Job? = null
            var pressedAt = 0L
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressedAt = SystemClock.uptimeMillis()
                        job?.cancel()
                        job = launch { progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 1100f)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        val held = SystemClock.uptimeMillis() - pressedAt
                        job?.cancel()
                        job = launch {
                            val rest = PRESS_MIN_HOLD_MS - held
                            if (rest > 0 && progress.value < 1f) {
                                withTimeoutOrNull(rest) { progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 1100f)) }
                            }
                            // schwer zurück: niedrige Steifigkeit, deutlich unterdämpft
                            progress.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 380f))
                        }
                    }
                }
            }
        }
    }
    return progress.asState()
}

/**
 * Meldet nur, ob gerade ein Finger auf dem Element liegt (auch beim Ziehen). Verbraucht nichts,
 * Tippen, Ziehen und Scrollen laufen unverändert weiter.
 */
fun Modifier.observePress(source: MutableInteractionSource): Modifier = pointerInput(source) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val press = PressInteraction.Press(down.position)
        source.tryEmit(press)
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
        } while (ev.changes.any { it.pressed })
        source.tryEmit(PressInteraction.Release(press))
    }
}

/**
 * Kleiner Pop, sobald sich [key] ändert (nicht beim ersten Anzeigen).
 * Den Wert nur im graphicsLayer lesen, dann wird nichts neu zusammengesetzt.
 */
@Composable
fun popOnChange(key: Any?): State<Float> {
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) {
            first = false
        } else {
            scale.snapTo(0.8f)
            scale.animateTo(1f, Motion.pop())
        }
    }
    return scale.asState()
}
