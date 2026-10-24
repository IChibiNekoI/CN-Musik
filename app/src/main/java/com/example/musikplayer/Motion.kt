package com.example.musikplayer

import androidx.compose.animation.core.Animatable
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
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "press"
    )
    val shift by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "nudge"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            if (nudgeX != 0.dp) translationX = shift * nudgeX.toPx()
            if (nudgeY != 0.dp) translationY = shift * nudgeY.toPx()
            if (rotate != 0f) rotationZ = shift * rotate
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

/**
 * Meldet nur, ob gerade ein Finger auf dem Element liegt (auch beim Ziehen). Verbraucht nichts,
 * Tippen, Ziehen und Scrollen laufen unverändert weiter.
 */
fun Modifier.observePress(onPressChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onPressChange(true)
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
        } while (ev.changes.any { it.pressed })
        onPressChange(false)
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
