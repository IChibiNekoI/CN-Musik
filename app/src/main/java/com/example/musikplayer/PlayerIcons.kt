package com.example.musikplayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Zeichnet ein Symbol. Erhaben: vorher zwei dunkle, leicht nach unten versetzte Kopien (weicher Schlagschatten),
 * damit das Symbol vom Hintergrund abgehoben wirkt.
 */
private fun DrawScope.glyph(elevated: Boolean, tint: Color, draw: DrawScope.(Color) -> Unit) {
    if (elevated) {
        val s = this.size.minDimension
        translate(0f, s * 0.09f) { draw(Color.Black.copy(alpha = 0.14f)) }
        translate(0f, s * 0.05f) { draw(Color.Black.copy(alpha = 0.22f)) }
    }
    draw(tint)
}

/* ---------- Bittiefe-Symbol: drei Wellenlinien ---------- */

@Composable
fun BitDepthIcon(modifier: Modifier = Modifier, size: Dp = 14.dp, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val style = Stroke(width = s * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val amp = s * 0.075f
        for (y in listOf(0.24f, 0.50f, 0.76f)) {
            val cy = s * y
            // zwei Wellen: erst nach oben, dann nach unten ausgelenkt
            val p = Path().apply {
                moveTo(s * 0.06f, cy)
                quadraticBezierTo(s * 0.215f, cy - 2 * amp, s * 0.37f, cy)
                quadraticBezierTo(s * 0.525f, cy + 2 * amp, s * 0.68f, cy)
                quadraticBezierTo(s * 0.77f, cy - amp, s * 0.94f, cy - amp * 0.2f)
            }
            drawPath(p, tint, style = style)
        }
    }
}

/* ---------- Weiter / Zurück ---------- */

@Composable
fun SkipButton(
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 30.dp,
    tint: Color = LocalContentColor.current,
    elevated: Boolean = false
) {
    Box(
        // drückt sich ein und schubst ein Stück in Pfeilrichtung; beim Loslassen federt die Taste mit Überschwinger zurück
        modifier = modifier.size(48.dp)
            .bounceClick(nudgeX = if (forward) 5.dp else (-5).dp, onClick = onClick)
            .semantics { contentDescription = if (forward) str(R.string.cd_next) else str(R.string.back) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val stroke = outline(s * 0.085f, s * 0.12f)
            val draw: DrawScope.() -> Unit = {
                glyph(elevated, tint) { c ->
                    roundedTriangle(s * 0.14f, s * 0.70f, s * 0.20f, s * 0.80f, c, stroke)
                    drawLine(
                        c, Offset(s * 0.90f, s * 0.20f), Offset(s * 0.90f, s * 0.80f),
                        strokeWidth = s * 0.085f, cap = StrokeCap.Round
                    )
                }
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
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    elevated: Boolean = false
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .bounceClick(pressedScale = 0.94f, onClick = onClick)
            .then(
                if (elevated) Modifier
                    // zwei Lagen: enger Kontaktschatten und weiter, weicher Schatten
                    .shadow(
                        elevation = buttonSize * 0.05f, shape = CircleShape, clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.50f),
                        spotColor = Color.Black.copy(alpha = 0.60f)
                    )
                    .shadow(
                        elevation = buttonSize * 0.22f, shape = CircleShape, clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.30f),
                        spotColor = Color.Black.copy(alpha = 0.40f)
                    )
                else Modifier
            )
            .clip(CircleShape)
            .then(
                if (elevated) Modifier
                    // oben etwas heller, unten etwas dunkler: wie eine leicht gewölbte Fläche
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.10f).compositeOver(circleColor.copy(alpha = 0.55f)),
                                circleColor.copy(alpha = 0.40f)
                            )
                        )
                    )
                    // feine Lichtkante oben, dezente Schattenkante unten
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.32f), Color.Transparent, Color.Black.copy(alpha = 0.18f))
                        ),
                        CircleShape
                    )
                else Modifier.background(circleColor.copy(alpha = 0.45f))
            )
            .semantics { contentDescription = if (isPlaying) str(R.string.cd_pause) else str(R.string.cd_play) },
        contentAlignment = Alignment.Center
    ) {
        // Beim Wechsel zwischen Play und Pause tauscht das Symbol mit einem kleinen Zoom
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (scaleIn(tween(Motion.MEDIUM, easing = Motion.Ease), initialScale = 0.6f) +
                    fadeIn(tween(Motion.FAST))) togetherWith
                    (scaleOut(tween(Motion.FAST), targetScale = 0.6f) +
                        fadeOut(tween(Motion.FAST))) using SizeTransform(clip = false)
            },
            label = "playPause"
        ) { playing ->
            Canvas(Modifier.size(buttonSize * 0.5f)) {
                val s = this.size.minDimension
                val stroke = outline(s * 0.09f, s * 0.14f)
                if (playing) {
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
    iconSize: Dp = 26.dp,
    elevated: Boolean = false
) {
    val tint by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.FAST), label = "shuffleTint"
    )
    val pop by popOnChange(active)
    Box(
        modifier = modifier.size(48.dp).bounceClick(onClick = onClick).semantics { contentDescription = str(R.string.cd_shuffle) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(iconSize).graphicsLayer { scaleX = pop; scaleY = pop }) {
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
            glyph(elevated, tint) { c ->
                drawPath(a, c, style = line)
                drawPath(b, c, style = line)
                chevron(s * 0.88f, s * 0.70f, 1f, s * 0.11f, c, head)
                chevron(s * 0.88f, s * 0.30f, 1f, s * 0.11f, c, head)
            }
        }
    }
}

@Composable
fun RepeatButton(
    active: Boolean,
    repeatOne: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    elevated: Boolean = false
) {
    val tint by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Motion.FAST), label = "repeatTint"
    )
    val pop by popOnChange(active to repeatOne)
    Box(
        modifier = modifier.size(48.dp).bounceClick(onClick = onClick).semantics { contentDescription = str(R.string.cd_repeat) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(iconSize).graphicsLayer { scaleX = pop; scaleY = pop }) {
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
            glyph(elevated, tint) { c ->
                drawPath(top, c, style = line)
                drawPath(bottom, c, style = line)
                chevron(s * 0.82f, s * 0.30f, 1f, s * 0.11f, c, head)
                chevron(s * 0.18f, s * 0.70f, -1f, s * 0.11f, c, head)
                if (repeatOne) {
                    drawLine(c, Offset(s * 0.52f, s * 0.40f), Offset(s * 0.52f, s * 0.60f), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(c, Offset(s * 0.52f, s * 0.40f), Offset(s * 0.44f, s * 0.46f), strokeWidth = w, cap = StrokeCap.Round)
                }
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
    tint: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),   // gleiche Farbe wie der Kreis des Play-Buttons
    // 0 = Pfeil, 1 = flache Linie (wird nur beim Zeichnen gelesen)
    flatten: () -> Float = { 0f }
) {
    Box(
        // drückt sich ein und schubst ein Stück nach unten (Richtung Schließen), federt mit Überschwinger zurück
        modifier = modifier.size(48.dp)
            .bounceClick(pressedScale = 0.8f, nudgeY = 5.dp, onClick = onClick)
            .semantics { contentDescription = str(R.string.close) },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(iconSize)) {
            val f = flatten().coerceIn(0f, 1f)
            val s = this.size.minDimension
            val style = outline(s * 0.075f, s * 0.06f)
            // Seiten und Spitze laufen zur gemeinsamen Mittelhöhe zusammen, der Pfeil wird zur Linie
            val sideY = androidx.compose.ui.util.lerp(0.30f, 0.46f, f)
            val midY = androidx.compose.ui.util.lerp(0.72f, 0.46f, f)
            val p = Path().apply {
                moveTo(s * 0.12f, s * sideY)
                lineTo(s * 0.50f, s * midY)
                lineTo(s * 0.88f, s * sideY)
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
            .bounceClick(pressedScale = 0.82f, onClick = onClick)
            .clip(CircleShape)
            .background(circleColor.copy(alpha = 0.45f))   // gleiche Füllung wie der Play-Button
            .semantics { contentDescription = str(R.string.more) },
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
    Box(
        modifier = modifier.size(48.dp).bounceClick(onClick = onClick).semantics { contentDescription = str(R.string.update_available) },
        contentAlignment = Alignment.Center
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
