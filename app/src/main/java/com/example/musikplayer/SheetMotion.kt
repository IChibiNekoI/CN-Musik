package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Gummiband-Widerstand wie bei iOS: Je weiter man über die Grenze zieht, desto zäher wird es.
 * [offset] = Zug über die Grenze hinaus (px), [limit] = Wert, dem sich die Anzeige nur annähert.
 */
fun rubberBand(offset: Float, limit: Float, stiffness: Float = 0.55f): Float {
    if (offset <= 0f || limit <= 0f) return 0f
    return (1f - 1f / (offset * stiffness / limit + 1f)) * limit
}

/** Anteil der Bildschirmhöhe, über den der Finger den Player vollständig auf- bzw. zuzieht */
const val SHEET_DRAG_FRACTION = 0.4f

/** Wie weit (in Sekunden) die Loslass-Geschwindigkeit vorausgerechnet wird, um zu entscheiden: auf oder zu */
const val SHEET_PROJECTION_S = 0.15f

/** Feder für Öffnen, Schließen und Zurückschnappen: kein Überschwingen, aber mit übernommener Fingergeschwindigkeit */
fun sheetSpring(): SpringSpec<Float> = spring(
    dampingRatio = 1f,
    stiffness = 320f,
    // Fortschritt läuft von 0 bis 1: der Standardwert (0.01) würde ~20 px vor dem Ziel abschneiden
    visibilityThreshold = 0.0005f
)

/**
 * Ein einziger Antrieb für den Player-Übergang (0 = Miniplayer, 1 = Vollbild).
 *
 * Finger, Loslassen, Zurück-Taste und Zurück-Geste schicken alle ihre Befehle hierher.
 * Ein neuer Befehl unterbricht den laufenden sofort (collectLatest), deshalb lässt sich
 * jede Animation mitten in der Bewegung mit dem Finger abfangen. Pro Fingerbewegung wird
 * keine neue Coroutine gestartet; nur der letzte Wert zählt (CONFLATED).
 */
@Stable
class SheetMotion {
    // Auf 0..1 begrenzt: eine Feder mit viel Anfangsschwung darf nicht darüber hinausschwingen,
    // sonst bekämen Form, Cover-Flug und Farben ungültige Werte
    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }

    private sealed interface Cmd
    private class Follow(val value: Float) : Cmd
    private class Settle(
        val from: Float?,
        val target: Float,
        val velocity: Float,
        val onEnd: (() -> Unit)?
    ) : Cmd

    private val cmds = Channel<Cmd>(Channel.CONFLATED)

    /**
     * Höchste Geschwindigkeit (Fortschritt pro Sekunde, immer positiv) der zuletzt abgeschlossenen [settle]-Bewegung.
     * Wird vor [onEnd] gesetzt und sagt, wie schnell der Player zuletzt geschlossen wurde.
     */
    var lastSettleSpeed: Float = 0f
        private set

    /**
     * Wird einmal aufgerufen, wenn der Player kurz vor seiner Endposition ankommt (Ziel 0 oder 1 als Wert),
     * aber nur, wenn er vorher ein Stück unterwegs war. Dafür die Haptik beim Einrasten.
     */
    var onLanded: ((Float) -> Unit)? = null

    /** Der Finger bestimmt den Fortschritt direkt */
    fun follow(value: Float) {
        cmds.trySend(Follow(value.coerceIn(0f, 1f)))
    }

    /**
     * Fortschritt per Feder zu [target] laufen lassen.
     * [velocity] in Fortschritt pro Sekunde (positiv = Richtung Vollbild), [from] = Stand beim Loslassen.
     * [onEnd] läuft nur, wenn die Animation ungestört bis zum Ziel kommt.
     */
    fun settle(target: Float, velocity: Float = 0f, from: Float? = null, onEnd: (() -> Unit)? = null) {
        cmds.trySend(Settle(from, target, velocity, onEnd))
    }

    /** Sofort auf einen Wert setzen (nur für Fälle ohne Fingerbewegung, z. B. Öffnen über die Benachrichtigung) */
    suspend fun snapTo(value: Float) {
        progress.snapTo(value.coerceIn(0f, 1f))
    }

    /** Muss einmal in einem LaunchedEffect laufen */
    suspend fun run() {
        cmds.receiveAsFlow().collectLatest { cmd ->
            when (cmd) {
                is Follow -> progress.snapTo(cmd.value)
                is Settle -> {
                    cmd.from?.let { progress.snapTo(it.coerceIn(0f, 1f)) }
                    var peak = kotlin.math.abs(cmd.velocity)
                    val travels = kotlin.math.abs(progress.value - cmd.target) > 0.08f
                    var landed = false
                    progress.animateTo(cmd.target, sheetSpring(), cmd.velocity) {
                        peak = maxOf(peak, kotlin.math.abs(velocity))
                        if (travels && !landed && kotlin.math.abs(value - cmd.target) < 0.025f) {
                            landed = true
                            onLanded?.invoke(cmd.target)
                        }
                    }
                    lastSettleSpeed = peak
                    cmd.onEnd?.invoke()
                }
            }
        }
    }
}
