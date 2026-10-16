package com.example.musikplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Eigene Symbole im Stil von Zahnrad, Herz und Player-Tasten:
 * nur Umrisse, abgerundete Linienenden und Ecken, überall die gleiche Linienstärke.
 * Alle sind auf einem 24er-Raster gezeichnet und skalieren mit iconSize.
 */

/** Linienstärke auf dem 24er-Raster (1.8 / 24 = 7,5 %, wie beim Zahnrad) */
private const val ICON_STROKE = 1.8f

@Composable
private fun LineIcon(
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
        val line = Stroke(width = ICON_STROKE * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        this.draw(tint, line, k)
    }
}

/** Gleiche Linie, aber Ecken abgerundet (Radius in Pixeln) */
private fun Stroke.rounded(px: Float) = Stroke(
    width = width,
    cap = cap,
    join = join,
    pathEffect = PathEffect.cornerPathEffect(px)
)

private fun polyPath(k: Float, vararg pts: Float, closed: Boolean = false): Path = Path().apply {
    var i = 0
    while (i + 1 < pts.size) {
        val x = pts[i] * k
        val y = pts[i + 1] * k
        if (i == 0) moveTo(x, y) else lineTo(x, y)
        i += 2
    }
    if (closed) close()
}

private fun DrawScope.seg(c: Color, st: Stroke, k: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(c, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = st.width, cap = StrokeCap.Round)

private fun DrawScope.dot(c: Color, k: Float, x: Float, y: Float, r: Float) =
    drawCircle(c, radius = r * k, center = Offset(x * k, y * k))

/** Kalender (Sortierung) */
@Composable
fun CalendarIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawRoundRect(
            c, topLeft = Offset(3.5f * k, 5.5f * k), size = Size(17f * k, 15f * k),
            cornerRadius = CornerRadius(3.2f * k), style = st
        )
        seg(c, st, k, 3.5f, 10.5f, 20.5f, 10.5f)
        seg(c, st, k, 8f, 3.2f, 8f, 7.2f)
        seg(c, st, k, 16f, 3.2f, 16f, 7.2f)
        for (y in floatArrayOf(14.6f, 17.6f)) {
            for (x in floatArrayOf(8f, 12f, 16f)) dot(c, k, x, y, 0.9f)
        }
    }
}

/** Ordner */
@Composable
fun FolderIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawPath(
            polyPath(k, 3.5f, 18f, 3.5f, 6.5f, 9.3f, 6.5f, 11.3f, 8.8f, 20.5f, 8.8f, 20.5f, 18f, closed = true),
            c, style = st.rounded(2.2f * k)
        )
    }
}

/** Großes T (Titel der Startseite) */
@Composable
fun TitleTIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 6f, 6.5f, 18f, 6.5f)
        seg(c, st, k, 12f, 6.5f, 12f, 18.5f)
    }
}

/** Schallplatte / Album */
@Composable
fun AlbumDiscIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawCircle(c, radius = 8.6f * k, center = Offset(12f * k, 12f * k), style = st)
        drawCircle(c, radius = 2.4f * k, center = Offset(12f * k, 12f * k), style = st)
    }
}

/** Vier Kacheln (Ansicht) */
@Composable
fun GridIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        for (y in floatArrayOf(4f, 13.5f)) {
            for (x in floatArrayOf(4f, 13.5f)) {
                drawRoundRect(
                    c, topLeft = Offset(x * k, y * k), size = Size(6.5f * k, 6.5f * k),
                    cornerRadius = CornerRadius(1.9f * k), style = st
                )
            }
        }
    }
}

/** Kreispfeil (neu einlesen) */
@Composable
fun RefreshIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        val r = 7.4f * k
        val cx = 12f * k
        val cy = 12f * k
        drawArc(
            c, startAngle = -30f, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(2f * r, 2f * r), style = st
        )
        // Pfeilspitze am Ende des Bogens (Winkel 260°), zeigt in Laufrichtung
        val a = Math.toRadians(260.0).toFloat()
        val tx = -sin(a)
        val ty = cos(a)
        val tipX = cx + r * cos(a) + tx * 1.6f * k
        val tipY = cy + r * sin(a) + ty * 1.6f * k
        fun wing(deg: Double): Offset {
            val b = Math.toRadians(deg).toFloat()
            val bx = -tx
            val by = -ty
            return Offset(
                tipX + 3.6f * k * (bx * cos(b) - by * sin(b)),
                tipY + 3.6f * k * (bx * sin(b) + by * cos(b))
            )
        }
        val w1 = wing(40.0)
        val w2 = wing(-40.0)
        val head = Path().apply {
            moveTo(w1.x, w1.y)
            lineTo(tipX, tipY)
            lineTo(w2.x, w2.y)
        }
        drawPath(head, c, style = st)
    }
}

/** Info: Kreis mit i */
@Composable
fun InfoIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawCircle(c, radius = 8.6f * k, center = Offset(12f * k, 12f * k), style = st)
        dot(c, k, 12f, 7.9f, 1.1f)
        seg(c, st, k, 12f, 11.2f, 12f, 16.6f)
    }
}

/** Stift mit Linie darunter (bearbeiten) */
@Composable
fun EditIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawPath(
            polyPath(k, 4.5f, 19.5f, 5.6f, 15.4f, 15.2f, 5.8f, 18.2f, 8.8f, 8.6f, 18.4f, closed = true),
            c, style = st.rounded(1.4f * k)
        )
        seg(c, st, k, 13.3f, 7.7f, 16.3f, 10.7f)
        seg(c, st, k, 12.5f, 20.8f, 20f, 20.8f)
    }
}

/** Liste mit Plus (zur Playlist hinzufügen) */
@Composable
fun PlaylistAddIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 4f, 7f, 15f, 7f)
        seg(c, st, k, 4f, 12f, 15f, 12f)
        seg(c, st, k, 4f, 17f, 10f, 17f)
        seg(c, st, k, 17.5f, 13.3f, 17.5f, 20.3f)
        seg(c, st, k, 14.2f, 16.8f, 20.8f, 16.8f)
    }
}

/** Plus */
@Composable
fun PlusIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 12f, 5f, 12f, 19f)
        seg(c, st, k, 5f, 12f, 19f, 12f)
    }
}

/** Mülleimer */
@Composable
fun TrashIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 4.5f, 7.2f, 19.5f, 7.2f)
        drawPath(polyPath(k, 9.5f, 7.2f, 9.5f, 4.6f, 14.5f, 4.6f, 14.5f, 7.2f), c, style = st.rounded(1.2f * k))
        drawPath(polyPath(k, 6.4f, 7.2f, 7.4f, 19.2f, 16.6f, 19.2f, 17.6f, 7.2f), c, style = st.rounded(1.8f * k))
        seg(c, st, k, 10.2f, 10.8f, 10.2f, 15.8f)
        seg(c, st, k, 13.8f, 10.8f, 13.8f, 15.8f)
    }
}

/** Pfeil nach rechts (weiter) */
@Composable
fun ChevronRightIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawPath(polyPath(k, 9f, 5.5f, 15.5f, 12f, 9f, 18.5f), c, style = st)
    }
}

/** Herz: nur Umriss, oder ausgefüllt (filled = true) */
@Composable
fun HeartIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null,
    filled: Boolean = false
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        val heart = Path().apply {
            moveTo(12f * k, 20.2f * k)
            cubicTo(4.8f * k, 15.4f * k, 2.4f * k, 11f * k, 2.4f * k, 8.2f * k)
            cubicTo(2.4f * k, 4.8f * k, 5.3f * k, 3.4f * k, 7.7f * k, 3.4f * k)
            cubicTo(10.1f * k, 3.4f * k, 11.5f * k, 4.8f * k, 12f * k, 6.7f * k)
            cubicTo(12.5f * k, 4.8f * k, 13.9f * k, 3.4f * k, 16.3f * k, 3.4f * k)
            cubicTo(18.7f * k, 3.4f * k, 21.6f * k, 4.8f * k, 21.6f * k, 8.2f * k)
            cubicTo(21.6f * k, 11f * k, 19.2f * k, 15.4f * k, 12f * k, 20.2f * k)
            close()
        }
        if (filled) drawPath(heart, c)
        drawPath(heart, c, style = st)
    }
}

/** Favoriten-Taste: Herz in der Akzentfarbe, ausgefüllt wenn der Titel ein Favorit ist */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp
) {
    IconButton(onClick = onClick, modifier = modifier) {
        HeartIcon(
            iconSize = iconSize,
            tint = MaterialTheme.colorScheme.primary,
            description = if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen",
            filled = isFavorite
        )
    }
}

/** Quadrat mit stark gerundeten Ecken (Rundungen) */
@Composable
fun CornersIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawRoundRect(
            c, topLeft = Offset(4f * k, 4f * k), size = Size(16f * k, 16f * k),
            cornerRadius = CornerRadius(6.5f * k), style = st
        )
    }
}

/** Lupe (Suche) */
@Composable
fun SearchIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawCircle(c, radius = 6.6f * k, center = Offset(10.5f * k, 10.5f * k), style = st)
        seg(c, st, k, 15.4f, 15.4f, 20.2f, 20.2f)
    }
}

/** Pfeil nach links (zurück) */
@Composable
fun BackIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 19f, 12f, 5.2f, 12f)
        drawPath(polyPath(k, 11f, 6f, 5f, 12f, 11f, 18f), c, style = st)
    }
}

/** Kreuz (schließen, leeren) */
@Composable
fun CloseIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 6.2f, 6.2f, 17.8f, 17.8f)
        seg(c, st, k, 17.8f, 6.2f, 6.2f, 17.8f)
    }
}

/** Drei ausgefüllte Punkte untereinander (Mehr-Menü in Listen) */
@Composable
fun MoreVertIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, _, k ->
        dot(c, k, 12f, 5.5f, 1.7f)
        dot(c, k, 12f, 12f, 1.7f)
        dot(c, k, 12f, 18.5f, 1.7f)
    }
}

/** Raute (Track-Nummer) */
@Composable
fun HashIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 9.2f, 4.5f, 9.2f, 19.5f)
        seg(c, st, k, 14.8f, 4.5f, 14.8f, 19.5f)
        seg(c, st, k, 4.5f, 9.2f, 19.5f, 9.2f)
        seg(c, st, k, 4.5f, 14.8f, 19.5f, 14.8f)
    }
}

/** Note (Platzhalter, wenn ein Cover fehlt) */
@Composable
fun MusicNoteIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawCircle(c, radius = 3.1f * k, center = Offset(8.3f * k, 17.2f * k), style = st)
        seg(c, st, k, 11.4f, 17.2f, 11.4f, 5.2f)
        val flag = Path().apply {
            moveTo(11.4f * k, 5.2f * k)
            cubicTo(14.2f * k, 5.4f * k, 17.3f * k, 6.8f * k, 17.6f * k, 10.8f * k)
        }
        drawPath(flag, c, style = st)
    }
}
