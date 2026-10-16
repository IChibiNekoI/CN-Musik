package com.example.musikplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ---------- Zeichen-Helfer: Umriss-Stil mit runden Ecken ---------- */

private fun DrawScope.outline(widthPx: Float, cornerPx: Float) = Stroke(
    width = widthPx,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = PathEffect.cornerPathEffect(cornerPx)
)

/** Dreieck, das nach rechts zeigt. Alle Werte in Pixeln. */
private fun DrawScope.roundedTriangle(
    left: Float, tip: Float, top: Float, bottom: Float, color: Color, style: Stroke
) {
    val p = Path().apply {
        moveTo(left, top)
        lineTo(tip, (top + bottom) / 2f)
        lineTo(left, bottom)
        close()
    }
    drawPath(p, color, style = style)
}

/* ---------- Weiter / Zurück ---------- */

@Composable
fun SkipButton(
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 30.dp,
    tint: Color = LocalContentColor.current
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = if (forward) "Weiter" else "Zurück" }
    ) {
        Canvas(Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val stroke = outline(s * 0.085f, s * 0.12f)
            val draw: DrawScope.() -> Unit = {
                roundedTriangle(s * 0.14f, s * 0.70f, s * 0.20f, s * 0.80f, tint, stroke)
                drawLine(
                    tint, Offset(s * 0.90f, s * 0.20f), Offset(s * 0.90f, s * 0.80f),
                    strokeWidth = s * 0.085f, cap = StrokeCap.Round
                )
            }
            if (forward) draw() else scale(-1f, 1f, center) { draw() }
        }
    }
}

/* ---------- Play / Pause im Kreis ---------- */

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 76.dp,
    circleColor: Color = MaterialTheme.colorScheme.primary,
    iconColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(circleColor.copy(alpha = 0.45f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Abspielen" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(buttonSize * 0.5f)) {
            val s = this.size.minDimension
            val stroke = outline(s * 0.09f, s * 0.14f)
            if (isPlaying) {
                // ausgefüllte, schmale Balken mit voll gerundeten Enden
                val barW = s * 0.26f
                val gap = s * 0.08f
                val barH = s * 0.74f
                val left = (s - (2 * barW + gap)) / 2f
                val top = (s - barH) / 2f
                val corner = CornerRadius(barW / 2f)
                drawRoundRect(iconColor, Offset(left, top), Size(barW, barH), corner)
                drawRoundRect(iconColor, Offset(left + barW + gap, top), Size(barW, barH), corner)
            } else {
                // etwas nach rechts versetzt, damit das Dreieck optisch mittig sitzt
                roundedTriangle(s * 0.24f, s * 0.90f, s * 0.13f, s * 0.87f, iconColor, stroke)
            }
        }
    }
}

/* ---------- Zufall & Wiederholen im gleichen Umriss-Stil ---------- */

/** Kleiner Pfeilkopf (Winkel) mit Spitze bei (tipX, tipY); dir = +1 zeigt nach rechts, -1 nach links. */
private fun DrawScope.chevron(tipX: Float, tipY: Float, dir: Float, size: Float, color: Color, style: Stroke) {
    val p = Path().apply {
        moveTo(tipX - dir * size, tipY - size)
        lineTo(tipX, tipY)
        lineTo(tipX - dir * size, tipY + size)
    }
    drawPath(p, color, style = style)
}

@Composable
fun ShuffleButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp
) {
    val tint = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Zufall" }
    ) {
        Canvas(Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val w = s * 0.085f
            val line = outline(w, s * 0.10f)
            val head = outline(w, s * 0.03f)
            val a = Path().apply {
                moveTo(s * 0.10f, s * 0.30f); lineTo(s * 0.30f, s * 0.30f)
                lineTo(s * 0.64f, s * 0.70f); lineTo(s * 0.86f, s * 0.70f)
            }
            val b = Path().apply {
                moveTo(s * 0.10f, s * 0.70f); lineTo(s * 0.30f, s * 0.70f)
                lineTo(s * 0.64f, s * 0.30f); lineTo(s * 0.86f, s * 0.30f)
            }
            drawPath(a, tint, style = line)
            drawPath(b, tint, style = line)
            chevron(s * 0.88f, s * 0.70f, 1f, s * 0.11f, tint, head)
            chevron(s * 0.88f, s * 0.30f, 1f, s * 0.11f, tint, head)
        }
    }
}

@Composable
fun RepeatButton(
    active: Boolean,
    repeatOne: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp
) {
    val tint = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Wiederholen" }
    ) {
        Canvas(Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val w = s * 0.085f
            val line = outline(w, s * 0.14f)
            val head = outline(w, s * 0.03f)
            // oben: von links unten hoch, dann nach rechts
            val top = Path().apply {
                moveTo(s * 0.14f, s * 0.58f); lineTo(s * 0.14f, s * 0.30f); lineTo(s * 0.80f, s * 0.30f)
            }
            // unten: von rechts oben runter, dann nach links
            val bottom = Path().apply {
                moveTo(s * 0.86f, s * 0.42f); lineTo(s * 0.86f, s * 0.70f); lineTo(s * 0.20f, s * 0.70f)
            }
            drawPath(top, tint, style = line)
            drawPath(bottom, tint, style = line)
            chevron(s * 0.82f, s * 0.30f, 1f, s * 0.11f, tint, head)
            chevron(s * 0.18f, s * 0.70f, -1f, s * 0.11f, tint, head)
            if (repeatOne) {
                drawLine(tint, Offset(s * 0.52f, s * 0.40f), Offset(s * 0.52f, s * 0.60f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, Offset(s * 0.52f, s * 0.40f), Offset(s * 0.44f, s * 0.46f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

/* ---------- Schließen-Pfeil (Player nach unten wegschieben) ---------- */

@Composable
fun CloseSheetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 30.dp,   // größer = höherer/breiterer Pfeil
    tint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)   // gleiche Farbe wie der Kreis des Play-Buttons
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Schließen" }
    ) {
        Canvas(Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val style = outline(s * 0.075f, s * 0.06f)
            val p = Path().apply {
                moveTo(s * 0.12f, s * 0.30f)
                lineTo(s * 0.50f, s * 0.72f)
                lineTo(s * 0.88f, s * 0.30f)
            }
            drawPath(p, tint, style = style)
        }
    }
}

/* ---------- Mehr-Menü: drei ausgefüllte Punkte (waagerecht) im Kreis ---------- */

@Composable
fun MoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 32.dp,
    circleColor: Color = MaterialTheme.colorScheme.primary,
    iconColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(circleColor.copy(alpha = 0.45f))   // gleiche Füllung wie der Play-Button
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Mehr" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(buttonSize * 0.5f)) {
            val s = this.size.minDimension
            val r = s * 0.095f
            for (x in listOf(0.14f, 0.50f, 0.86f)) {
                drawCircle(iconColor, radius = r, center = Offset(s * x, s * 0.5f))
            }
        }
    }
}

/* ---------- Update-Symbol: abgerundetes Quadrat mit Pfeil nach oben und Ablage ---------- */

@Composable
fun UpdateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Update verfügbar" }
    ) {
        Canvas(Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val w = s * 0.08f
            val line = outline(w, s * 0.04f)
            // Rahmen mit großen, runden Ecken
            drawRoundRect(
                color = tint,
                topLeft = Offset(s * 0.08f, s * 0.08f),
                size = Size(s * 0.84f, s * 0.84f),
                cornerRadius = CornerRadius(s * 0.26f),
                style = line
            )
            // Pfeil nach oben
            drawLine(tint, Offset(s * 0.50f, s * 0.57f), Offset(s * 0.50f, s * 0.29f), strokeWidth = w, cap = StrokeCap.Round)
            val head = Path().apply {
                moveTo(s * 0.38f, s * 0.41f)
                lineTo(s * 0.50f, s * 0.29f)
                lineTo(s * 0.62f, s * 0.41f)
            }
            drawPath(head, tint, style = line)
            // Ablage unter dem Pfeil
            val tray = Path().apply {
                moveTo(s * 0.27f, s * 0.62f)
                quadraticBezierTo(s * 0.50f, s * 0.82f, s * 0.73f, s * 0.62f)
            }
            drawPath(tray, tint, style = line)
        }
    }
}

/* ---------- Zahnrad (Einstellungen) im gleichen Umriss-Stil ---------- */

/** Sechs abgerundete Zacken: Der Radius schwankt zwischen Zacke (außen) und Kerbe (innen) */
private fun gearPath(s: Float): Path = Path().apply {
    val steps = 360
    for (i in 0..steps) {
        val th = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
        val c = kotlin.math.cos(6f * th)
        val f = kotlin.math.tanh(2.2f * (c + 0.3f))   // breite, runde Zacken, schmale Kerben
        val r = s * (0.34f + 0.062f * f)
        val x = s / 2f + r * kotlin.math.cos(th)
        val y = s / 2f + r * kotlin.math.sin(th)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Zahnrad mit Mittelring, nur Umriss */
@Composable
fun SettingsGearIcon(
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    description: String? = null
) {
    Canvas(
        modifier
            .size(iconSize)
            .semantics { if (description != null) contentDescription = description }
    ) {
        val s = this.size.minDimension
        val st = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(gearPath(s), tint, style = st)
        drawCircle(tint, radius = s * 0.11f, center = Offset(s / 2f, s / 2f), style = st)
    }
}
