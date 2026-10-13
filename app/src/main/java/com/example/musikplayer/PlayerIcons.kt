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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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

/* ---------- Play / Pause im leuchtenden Kreis ---------- */

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
            // weicher Schein um den Kreis
            .drawBehind {
                val r = this.size.minDimension * 0.85f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(circleColor.copy(alpha = 0.40f), Color.Transparent),
                        center = center,
                        radius = r
                    ),
                    radius = r
                )
            }
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
                val barW = s * 0.17f
                val barH = s * 0.66f
                val top = (s - barH) / 2f
                val corner = CornerRadius(barW / 2f)
                drawRoundRect(iconColor, Offset(s * 0.27f, top), Size(barW, barH), corner, style = stroke)
                drawRoundRect(iconColor, Offset(s * 0.56f, top), Size(barW, barH), corner, style = stroke)
            } else {
                // etwas nach rechts versetzt, damit das Dreieck optisch mittig sitzt
                roundedTriangle(s * 0.24f, s * 0.90f, s * 0.13f, s * 0.87f, iconColor, stroke)
            }
        }
    }
}
