package com.example.musikplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Beschriftungen unter einem Diagramm: nur nicht leere, mittig unter ihrem Platz und nie über den Rand hinaus.
 * Zwei Beschriftungen überlappen sich nie: Es wird von rechts nach links gezeichnet, und was zu nah an die Beschriftung
 * rechts daneben käme, entfällt. Bei einem Zusammenstoß gewinnt also die jüngere (z. B. der Wochenanfang statt des
 * Starttags direkt davor).
 */
private fun DrawScope.drawLabels(
    tm: TextMeasurer, style: TextStyle, color: Color, labels: List<String>, centerX: (Int) -> Float, top: Float
) {
    val minGap = 6.dp.toPx()
    var nextLeft = Float.MAX_VALUE   // linke Kante der zuletzt gezeichneten Beschriftung
    for (i in labels.indices.reversed()) {
        val text = labels[i]
        if (text.isEmpty()) continue
        val r = tm.measure(text, style)
        val x = (centerX(i) - r.size.width / 2f).coerceIn(0f, (size.width - r.size.width).coerceAtLeast(0f))
        if (x + r.size.width + minGap > nextLeft) continue
        drawText(r, color = color, topLeft = Offset(x, top))
        nextLeft = x
    }
}

/** Balkendiagramm mit abgerundeten Balken im Stil der Pillen des Players. */
@Composable
fun StatBars(values: IntArray, labels: List<String>, modifier: Modifier = Modifier, chartHeight: Dp = 110.dp) {
    val bar = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth().height(chartHeight + 22.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val labelSpace = 22.dp.toPx()
        val chartH = size.height - labelSpace
        val slot = size.width / n
        val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        for (i in 0 until n) {
            if (values[i] <= 0) continue
            val h = (chartH * values[i] / max).coerceAtLeast(bw)
            val x = slot * i + (slot - bw) / 2f
            drawRoundRect(bar, topLeft = Offset(x, chartH - h), size = Size(bw, h), cornerRadius = CornerRadius(bw / 2f))
        }
        drawLabels(tm, style, muted, labels, { i -> slot * i + slot / 2f }, chartH + 6.dp.toPx())
    }
}

/** Verlaufslinie der aufsummierten Wiedergaben. */
@Composable
fun StatCumulative(values: IntArray, labels: List<String>, modifier: Modifier = Modifier, chartHeight: Dp = 110.dp) {
    val line = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    Canvas(modifier.fillMaxWidth().height(chartHeight + 22.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val cum = IntArray(n)
        var sum = 0
        for (i in 0 until n) { sum += values[i]; cum[i] = sum }
        val max = sum.coerceAtLeast(1)
        val labelSpace = 22.dp.toPx()
        val pad = 4.dp.toPx()
        val chartH = size.height - labelSpace - pad
        val stepX = if (n > 1) size.width / (n - 1) else 0f
        val path = Path()
        for (i in 0 until n) {
            val x = if (n > 1) stepX * i else size.width / 2f
            val y = pad + chartH * (1f - cum[i].toFloat() / max)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(if (n > 1) size.width else size.width / 2f, pad + chartH)
            lineTo(if (n > 1) 0f else size.width / 2f, pad + chartH)
            close()
        }
        drawPath(fill, brush = Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent), startY = pad, endY = pad + chartH))
        drawPath(path, color = line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLabels(tm, style, muted, labels, { i -> if (n > 1) stepX * i else size.width / 2f }, pad + chartH + 6.dp.toPx())
    }
}

/** Tage als Tabelle: Spalten sind Wochen, Zeilen Montag bis Sonntag; je mehr Wiedergaben, desto kräftiger die Farbe. */
@Composable
fun StatHeatmap(heat: IntArray, weeks: Int, modifier: Modifier = Modifier) {
    val strong = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = min(maxWidth.value / weeks.coerceAtLeast(1), 26f).dp
        Canvas(Modifier.width(cell * weeks).height(cell * 7)) {
            val max = (heat.maxOrNull() ?: 0).coerceAtLeast(1)
            val c = cell.toPx()
            val gap = (c * 0.14f).coerceAtLeast(1.dp.toPx())
            for (w in 0 until weeks) for (d in 0 until 7) {
                val v = heat[w * 7 + d]
                if (v < 0) continue
                val color = if (v == 0) empty else strong.copy(alpha = 0.25f + 0.75f * v / max)
                drawRoundRect(
                    color, topLeft = Offset(w * c + gap / 2f, d * c + gap / 2f),
                    size = Size(c - gap, c - gap), cornerRadius = CornerRadius((c - gap) * 0.3f)
                )
            }
        }
    }
}

/** 24-Stunden-Uhr: je Stunde ein Strich, so lang wie die Zahl der Wiedergaben; in der Mitte die stärkste Stunde. */
@Composable
fun StatClock(hours: IntArray, peakLabel: String, centerSub: String, modifier: Modifier = Modifier, diameter: Dp = 210.dp) {
    val strong = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
            val outer = size.minDimension / 2f - 6.dp.toPx()
            val inner = outer * 0.5f
            val stroke = 6.dp.toPx()
            for (h in 0 until 24) {
                val a = Math.toRadians((h * 15 - 90).toDouble())
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val len = (outer - inner) * hours[h] / max
                val start = Offset(center.x + dx * inner, center.y + dy * inner)
                val end = Offset(center.x + dx * (inner + len.coerceAtLeast(2.dp.toPx())), center.y + dy * (inner + len.coerceAtLeast(2.dp.toPx())))
                drawLine(if (hours[h] > 0) strong else empty, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(peakLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(centerSub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Halbrunde Anzeige: [fraction] über 1 (mehr als 100 %) füllt den Bogen ganz. */
@Composable
fun StatGauge(fraction: Float, centerText: String, subText: String, modifier: Modifier = Modifier, diameter: Dp = 190.dp) {
    val strong = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 16.dp.toPx()
            val d = size.minDimension - w
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arc = Size(d, d)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arc,
                style = Stroke(width = w, cap = StrokeCap.Round))
            drawArc(strong, startAngle = 135f, sweepAngle = 270f * fraction.coerceIn(0f, 1f), useCenter = false, topLeft = topLeft, size = arc,
                style = Stroke(width = w, cap = StrokeCap.Round))
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
