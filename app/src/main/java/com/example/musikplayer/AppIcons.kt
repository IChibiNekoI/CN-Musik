package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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

/** Senkrechte Balken unterschiedlicher Höhe (Fortschrittsbalken / Wellenform) */
@Composable
fun WaveBarsIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 4.5f, 10f, 4.5f, 14f)
        seg(c, st, k, 8.25f, 7f, 8.25f, 17f)
        seg(c, st, k, 12f, 4.5f, 12f, 19.5f)
        seg(c, st, k, 15.75f, 8f, 15.75f, 16f)
        seg(c, st, k, 19.5f, 10.5f, 19.5f, 13.5f)
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

/** Abspielen: abgerundetes Dreieck */
@Composable
fun PlayTriangleIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(8.5f * k, 5.5f * k)
            lineTo(18.5f * k, 12f * k)
            lineTo(8.5f * k, 18.5f * k)
            close()
        }
        drawPath(p, c, style = st)
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

/** Vibrierendes Handy (haptisches Feedback) */
@Composable
fun VibrationIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawPath(polyPath(k, 8.2f, 3.5f, 15.8f, 3.5f, 15.8f, 20.5f, 8.2f, 20.5f, closed = true), c, style = st.rounded(2.2f * k))
        seg(c, st, k, 4.8f, 8.5f, 4.8f, 15.5f)
        seg(c, st, k, 2.3f, 10.2f, 2.3f, 13.8f)
        seg(c, st, k, 19.2f, 8.5f, 19.2f, 15.5f)
        seg(c, st, k, 21.7f, 10.2f, 21.7f, 13.8f)
    }
}

/** Kopfhörer: Bügel mit zwei Hörmuscheln */
@Composable
fun HeadphonesIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        // Bügel: obere Hälfte eines Kreises
        drawArc(
            c, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(5.3f * k, 3.8f * k), size = Size(13.4f * k, 13.4f * k), style = st
        )
        seg(c, st, k, 5.3f, 10.5f, 5.3f, 12.4f)
        seg(c, st, k, 18.7f, 10.5f, 18.7f, 12.4f)
        // Hörmuscheln
        drawRoundRect(c, topLeft = Offset(3.4f * k, 12.2f * k), size = Size(3.8f * k, 6.4f * k), cornerRadius = CornerRadius(1.6f * k), style = st)
        drawRoundRect(c, topLeft = Offset(16.8f * k, 12.2f * k), size = Size(3.8f * k, 6.4f * k), cornerRadius = CornerRadius(1.6f * k), style = st)
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
/** Auge: Umriss mit Pupille */
@Composable
fun EyeIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawEye(c, st, k)
    }
}

/** Durchgestrichenes Auge (ausgeblendet) */
@Composable
fun EyeOffIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawEye(c, st, k)
        seg(c, st, k, 4.5f, 4.5f, 19.5f, 19.5f)
    }
}

private fun DrawScope.drawEye(c: Color, st: Stroke, k: Float) {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(2.8f * k, 12f * k)
        cubicTo(5f * k, 7.5f * k, 8.5f * k, 6f * k, 12f * k, 6f * k)
        cubicTo(15.5f * k, 6f * k, 19f * k, 7.5f * k, 21.2f * k, 12f * k)
        cubicTo(19f * k, 16.5f * k, 15.5f * k, 18f * k, 12f * k, 18f * k)
        cubicTo(8.5f * k, 18f * k, 5f * k, 16.5f * k, 2.8f * k, 12f * k)
        close()
    }
    drawPath(p, c, style = st)
    drawCircle(c, radius = 2.6f * k, center = Offset(12f * k, 12f * k), style = st)
}

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
    modifier: Modifier = Modifier.size(48.dp),
    iconSize: Dp = 28.dp
) {
    // Beim Favorisieren (nicht beim Entfernen und nicht beim Songwechsel) poppt das Herz kurz auf
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // Ohne Druck-Schimmer: kein Ripple, kein Kreis um das Herz beim Antippen oder Halten
    Box(
        modifier = modifier.bounceClick(pressedScale = 0.85f) {
            if (!isFavorite) {
                scope.launch {
                    scale.snapTo(0.7f)
                    scale.animateTo(1f, Motion.pop())
                }
            }
            onClick()
        },
        contentAlignment = Alignment.Center
    ) {
        HeartIcon(
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            iconSize = iconSize,
            tint = MaterialTheme.colorScheme.primary,
            description = if (isFavorite) str(R.string.fav_remove) else str(R.string.fav_add),
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

/** Globus (Sprache) */
@Composable
fun GlobeIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawCircle(c, radius = 9f * k, center = Offset(12f * k, 12f * k), style = st)
        drawOval(c, topLeft = Offset(8.2f * k, 3f * k), size = Size(7.6f * k, 18f * k), style = st)
        seg(c, st, k, 3f, 12f, 21f, 12f)
    }
}


/** Ablage mit Pfeil nach unten (Backup) */
@Composable
fun BackupIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 12f, 3.5f, 12f, 15f)
        seg(c, st, k, 7.5f, 10.5f, 12f, 15f)
        seg(c, st, k, 16.5f, 10.5f, 12f, 15f)
        seg(c, st, k, 4f, 15.5f, 4f, 20f)
        seg(c, st, k, 4f, 20f, 20f, 20f)
        seg(c, st, k, 20f, 20f, 20f, 15.5f)
    }
}

/** Ablage mit Pfeil nach oben (Backup wiederherstellen) */
@Composable
fun RestoreIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        seg(c, st, k, 12f, 15f, 12f, 3.5f)
        seg(c, st, k, 7.5f, 8f, 12f, 3.5f)
        seg(c, st, k, 16.5f, 8f, 12f, 3.5f)
        seg(c, st, k, 4f, 15.5f, 4f, 20f)
        seg(c, st, k, 4f, 20f, 20f, 20f)
        seg(c, st, k, 20f, 20f, 20f, 15.5f)
    }
}

/** Haus (Startseite) */
@Composable
fun HomeIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        val p = Path().apply {
            moveTo(4f * k, 11f * k)
            lineTo(12f * k, 4.2f * k)
            lineTo(20f * k, 11f * k)
            lineTo(20f * k, 19f * k)
            lineTo(14.5f * k, 19f * k)
            lineTo(14.5f * k, 14f * k)
            lineTo(9.5f * k, 14f * k)
            lineTo(9.5f * k, 19f * k)
            lineTo(4f * k, 19f * k)
            close()
        }
        drawPath(p, c, style = st.rounded(1.6f * k))
    }
}

/** Würfel (neu mischen): abgerundetes Quadrat mit fünf Augen */
@Composable
fun DiceIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        drawRoundRect(
            c, topLeft = Offset(3.8f * k, 3.8f * k), size = Size(16.4f * k, 16.4f * k),
            cornerRadius = CornerRadius(5f * k), style = st
        )
        dot(c, k, 8.6f, 8.6f, 1.25f)
        dot(c, k, 15.4f, 8.6f, 1.25f)
        dot(c, k, 12f, 12f, 1.25f)
        dot(c, k, 8.6f, 15.4f, 1.25f)
        dot(c, k, 15.4f, 15.4f, 1.25f)
    }
}

/** Funkeln (Mix): großer vierzackiger Stern mit zwei kleinen Funken */
@Composable
fun SparkleIcon(
    modifier: Modifier = Modifier, iconSize: Dp = 24.dp,
    tint: Color = LocalContentColor.current, description: String? = null
) {
    LineIcon(modifier, iconSize, tint, description) { c, st, k ->
        fun star(cx: Float, cy: Float, r: Float): Path {
            val q = r * 0.22f
            return Path().apply {
                moveTo(cx * k, (cy - r) * k)
                quadraticBezierTo((cx + q) * k, (cy - q) * k, (cx + r) * k, cy * k)
                quadraticBezierTo((cx + q) * k, (cy + q) * k, cx * k, (cy + r) * k)
                quadraticBezierTo((cx - q) * k, (cy + q) * k, (cx - r) * k, cy * k)
                quadraticBezierTo((cx - q) * k, (cy - q) * k, cx * k, (cy - r) * k)
                close()
            }
        }
        drawPath(star(10.5f, 13.5f, 7.2f), c, style = st)
        drawPath(star(18.2f, 5.8f, 2.8f), c, style = st)
    }
}
