package com.example.musikplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Symbole der Statistik im gleichen Linienstil wie in AppIcons.kt (24er Raster, runde Enden)

private const val STAT_ICON_STROKE = 1.8f

@Composable
private fun StatLineIcon(
    modifier: Modifier,
    iconSize: Dp,
    tint: Color,
    description: String?,
    draw: DrawScope.(Color, Stroke, Float) -> Unit
) {
    Canvas(
        modifier
            .size(iconSize)
            .semantics { if (description != null) contentDescription = description }
    ) {
        val k = size.minDimension / 24f
        val line = Stroke(width = STAT_ICON_STROKE * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        this.draw(tint, line, k)
    }
}

private fun DrawScope.statLine(c: Color, st: Stroke, k: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(c, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = st.width, cap = StrokeCap.Round)

/** Statistik: abgerundetes Quadrat mit zwei Säulen unterschiedlicher Höhe. */
@Composable
fun StatsIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    StatLineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawRoundRect(
            c, topLeft = Offset(3.5f * k, 3.5f * k), size = Size(17f * k, 17f * k),
            cornerRadius = CornerRadius(4.6f * k), style = st
        )
        drawRoundRect(
            c, topLeft = Offset(7.6f * k, 11.4f * k), size = Size(3.2f * k, 5.8f * k),
            cornerRadius = CornerRadius(1.6f * k), style = st
        )
        drawRoundRect(
            c, topLeft = Offset(13.2f * k, 6.8f * k), size = Size(3.2f * k, 10.4f * k),
            cornerRadius = CornerRadius(1.6f * k), style = st
        )
    }
}

/** Pokal: Schale mit zwei Henkeln, Stiel und Fuß. */
@Composable
fun TrophyIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    StatLineIcon(modifier, iconSize, tint, description) { c, st, k ->
        val cup = Path().apply {
            moveTo(7.5f * k, 4.5f * k)
            lineTo(16.5f * k, 4.5f * k)
            lineTo(16.5f * k, 9.5f * k)
            cubicTo(16.5f * k, 12.3f * k, 14.5f * k, 14f * k, 12f * k, 14f * k)
            cubicTo(9.5f * k, 14f * k, 7.5f * k, 12.3f * k, 7.5f * k, 9.5f * k)
            close()
        }
        drawPath(cup, c, style = st)
        val left = Path().apply {
            moveTo(7.5f * k, 6.2f * k)
            cubicTo(3.6f * k, 6.2f * k, 3.6f * k, 11.2f * k, 8.2f * k, 11.4f * k)
        }
        val right = Path().apply {
            moveTo(16.5f * k, 6.2f * k)
            cubicTo(20.4f * k, 6.2f * k, 20.4f * k, 11.2f * k, 15.8f * k, 11.4f * k)
        }
        drawPath(left, c, style = st)
        drawPath(right, c, style = st)
        statLine(c, st, k, 12f, 14f, 12f, 18f)
        statLine(c, st, k, 8.2f, 19.8f, 15.8f, 19.8f)
    }
}

/** Wiederholen: zwei Pfeile im Kreis, wie die Taste im Player. */
@Composable
fun RepeatIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    StatLineIcon(modifier, iconSize, tint, description) { c, st, k ->
        val soft = Stroke(width = st.width, cap = st.cap, join = st.join, pathEffect = PathEffect.cornerPathEffect(3.2f * k))
        val top = Path().apply {
            moveTo(3.4f * k, 13.9f * k); lineTo(3.4f * k, 7.2f * k); lineTo(19.2f * k, 7.2f * k)
        }
        val bottom = Path().apply {
            moveTo(20.6f * k, 10.1f * k); lineTo(20.6f * k, 16.8f * k); lineTo(4.8f * k, 16.8f * k)
        }
        drawPath(top, c, style = soft)
        drawPath(bottom, c, style = soft)
        // Pfeilspitzen
        val headRight = Path().apply {
            moveTo(17.1f * k, 4.6f * k); lineTo(19.7f * k, 7.2f * k); lineTo(17.1f * k, 9.8f * k)
        }
        val headLeft = Path().apply {
            moveTo(6.9f * k, 14.2f * k); lineTo(4.3f * k, 16.8f * k); lineTo(6.9f * k, 19.4f * k)
        }
        drawPath(headRight, c, style = st)
        drawPath(headLeft, c, style = st)
    }
}

/** Mikrofon (Top Künstler): hohe Kapsel mit zwei Wellenbögen darin und ein U-Bügel darunter. */
@Composable
fun MicrophoneIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    StatLineIcon(modifier, iconSize, tint, description) { c, st, k ->
        // Kapsel
        drawRoundRect(
            c, topLeft = Offset(6.18f * k, 2.22f * k), size = Size(11.64f * k, 16.76f * k),
            cornerRadius = CornerRadius(5.82f * k), style = st
        )
        // Bügel: zwei kurze gerade Stücke und unten ein Halbkreis um die Kapsel
        val stand = Path().apply {
            moveTo(3.2f * k, 11.18f * k)
            lineTo(3.2f * k, 13.02f * k)
            arcTo(Rect(3.2f * k, 4.22f * k, 20.8f * k, 21.82f * k), 180f, -180f, false)
            lineTo(20.8f * k, 11.18f * k)
        }
        drawPath(stand, c, style = st)
        // Zwei Wellenbögen in der oberen Hälfte der Kapsel, der untere kürzer
        val upper = Path().apply {
            moveTo(9.4f * k, 7.3f * k)
            cubicTo(11.13f * k, 6.77f * k, 12.87f * k, 6.77f * k, 14.6f * k, 7.3f * k)
        }
        val lower = Path().apply {
            moveTo(10.3f * k, 10.35f * k)
            cubicTo(11.43f * k, 10.02f * k, 12.57f * k, 10.02f * k, 13.7f * k, 10.35f * k)
        }
        drawPath(upper, c, style = st)
        drawPath(lower, c, style = st)
    }
}

/** Verlauf (Gewohnheiten): abgerundetes Quadrat mit einer Zickzack-Linie darin. */
@Composable
fun TrendIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    StatLineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawRoundRect(
            c, topLeft = Offset(2.9f * k, 2.9f * k), size = Size(18.2f * k, 18.2f * k),
            cornerRadius = CornerRadius(5f * k), style = st
        )
        val soft = Stroke(width = st.width, cap = st.cap, join = st.join, pathEffect = PathEffect.cornerPathEffect(0.7f * k))
        val line = Path().apply {
            moveTo(7.5f * k, 14.31f * k)
            lineTo(10.58f * k, 10.92f * k)
            lineTo(13.46f * k, 12.85f * k)
            lineTo(16.46f * k, 9.58f * k)
        }
        drawPath(line, c, style = soft)
    }
}
