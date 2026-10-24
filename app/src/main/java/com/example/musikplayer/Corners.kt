package com.example.musikplayer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** STANDARD = Rundungen wie bisher, UNIFORM = einheitlicher Stil für die ganze App */
enum class CornerStyle { STANDARD, UNIFORM }

/** Hintergrund des Players: Einfarbig (wie bisher), Farbverlauf aus der Cover-Farbe oder das stark verschwommene Cover */
enum class PlayerBackground { SOLID, GRADIENT, BLUR }

/** Aktuelle Wahl. Eine Änderung zeichnet die ganze App sofort neu. */
object CornerSettings {
    /** Rundung von Chips, Menüs, Eingabefeldern und Dialogen */
    var style by mutableStateOf(CornerStyle.UNIFORM)
    /** Rundung der Albumcover: immer Standard (keine Einstellung mehr) */
    var coverStyle by mutableStateOf(CornerStyle.STANDARD)
    /** Hintergrund des Players */
    var playerBg by mutableStateOf(PlayerBackground.BLUR)
    /** Menüs, Dialoge und Einstellungen sehen aus wie der Player (nur bei Unschärfe) */
    var menuUnified by mutableStateOf(false)
    /** Weicher Schatten unter dem Cover im Player, optional */
    var coverShadow by mutableStateOf(false)
    /** Fortschrittsbalken im Player als Wellenform aus einzelnen Balken, optional */
    var waveProgress by mutableStateOf(false)
    private var loaded = false

    /** Gespeicherte Wahl einmal beim Start laden (vor der ersten Anzeige) */
    fun load(ctx: android.content.Context) {
        if (!loaded) {
            loaded = true
            style = readCornerStyle(ctx)
            playerBg = readPlayerBg(ctx)
            menuUnified = readMenuUnified(ctx)
            coverShadow = readCoverShadow(ctx)
            waveProgress = readWaveProgress(ctx)
        }
    }
}

val LocalCornerStyle = compositionLocalOf { CornerStyle.STANDARD }
val LocalCoverStyle = compositionLocalOf { CornerStyle.STANDARD }

/**
 * Rundung eines Covers.
 * STANDARD: der Wert, den die Stelle schon immer hatte.
 * UNIFORM: wächst mit der Größe (22 %), mindestens 8 dp, höchstens 28 dp. Kleine und große Cover wirken so gleich rund.
 * Ein Standardwert von 0 bleibt 0 (das Cover sitzt dann in einem Rahmen, der selbst rundet).
 */
fun coverRadius(style: CornerStyle, size: Dp, standard: Dp): Dp = when {
    standard == 0.dp -> 0.dp
    style == CornerStyle.UNIFORM -> minOf(maxOf(size * 0.22f, 8.dp), 28.dp)
    else -> standard
}

/** Einheitliche Rundungen für Chips, Menüs, Eingabefelder und Dialoge */
val UniformShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
