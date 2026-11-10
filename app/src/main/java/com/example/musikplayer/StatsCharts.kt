package com.example.musikplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

/**
 * Antippen oder Darüberwischen wählt ein Element: [hit] liefert seine Nummer oder -1 (nichts getroffen). Dasselbe Element
 * noch einmal anzutippen wählt es wieder ab. Senkrechtes Wischen bleibt für das Scrollen der Seite frei.
 */
private fun Modifier.chartSelect(
    key: Any, selected: () -> Int, onSelect: (Int) -> Unit, hit: (Offset, IntSize) -> Int
): Modifier = this
    .pointerInput(key) {
        detectTapGestures { p ->
            val i = hit(p, size)
            onSelect(if (i == selected()) -1 else i)
        }
    }
    .pointerInput(key) {
        detectHorizontalDragGestures(
            onDragStart = { p -> onSelect(hit(p, size)) },
            onHorizontalDrag = { change, _ -> onSelect(hit(change.position, size)) }
        )
    }

/** Farben und Schriften der Tooltips: wie die Zähler-Plaketten der Ranglisten. */
private class TipStyle(val bg: Color, val fg: Color, val title: TextStyle, val value: TextStyle)

@Composable
private fun tipStyle(): TipStyle = TipStyle(
    MaterialTheme.colorScheme.secondaryContainer,
    MaterialTheme.colorScheme.onSecondaryContainer,
    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    MaterialTheme.typography.labelMedium
)

/** Zeichnet einen Tooltip mit zwei Zeilen über dem Punkt ([anchorX], [anchorY]), nie über den Rand des Diagramms hinaus. */
private fun DrawScope.drawTip(
    tm: TextMeasurer, ts: TipStyle, title: String, value: String, anchorX: Float, anchorY: Float
) {
    val t = tm.measure(title, ts.title)
    val v = tm.measure(value, ts.value)
    val padX = 10.dp.toPx()
    val padY = 6.dp.toPx()
    val w = maxOf(t.size.width, v.size.width) + padX * 2f
    val h = (t.size.height + v.size.height) + padY * 2f
    val x = (anchorX - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val y = (anchorY - h - 6.dp.toPx()).coerceAtLeast(0f)
    drawRoundRect(ts.bg, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(10.dp.toPx()))
    drawText(t, color = ts.fg, topLeft = Offset(x + padX, y + padY))
    drawText(v, color = ts.fg, topLeft = Offset(x + padX, y + padY + t.size.height))
}

/**
 * Balkendiagramm mit abgerundeten Balken im Stil der Pillen des Players. Mit [tipTitles] lässt sich ein Balken antippen:
 * Der Tooltip zeigt dessen Überschrift und darunter [tipValue] mit dem Wert des Balkens.
 */
@Composable
fun StatBars(
    values: IntArray, labels: List<String>, modifier: Modifier = Modifier, chartHeight: Dp = 110.dp,
    tipTitles: List<String>? = null, tipValue: (Int) -> String = { it.toString() }
) {
    val bar = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val tip = tipStyle()
    var sel by remember(values) { mutableIntStateOf(-1) }
    val touch = if (tipTitles == null || values.isEmpty()) Modifier
    else Modifier.chartSelect(values, { sel }, { sel = it }) { p, sz ->
        (p.x / (sz.width.toFloat() / values.size)).toInt().coerceIn(0, values.size - 1)
    }
    Canvas(modifier.fillMaxWidth().height(chartHeight + 22.dp).then(touch)) {
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
        if (tipTitles != null && sel in 0 until n) {
            val v = values[sel]
            val h = if (v > 0) (chartH * v / max).coerceAtLeast(bw) else 0f
            drawTip(tm, tip, tipTitles.getOrElse(sel) { "" }, tipValue(v), slot * sel + slot / 2f, chartH - h)
        }
    }
}

/** Verlaufslinie der aufsummierten Wiedergaben. Mit [tipTitles] zeigt ein Tippen den Stand an dem Tag ([tipValue] bekommt die Summe). */
@Composable
fun StatCumulative(
    values: IntArray, labels: List<String>, modifier: Modifier = Modifier, chartHeight: Dp = 110.dp,
    tipTitles: List<String>? = null, tipValue: (Int) -> String = { it.toString() }
) {
    val line = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val tip = tipStyle()
    val cum = remember(values) {
        IntArray(values.size).also { arr ->
            var sum = 0
            for (i in values.indices) { sum += values[i]; arr[i] = sum }
        }
    }
    var sel by remember(values) { mutableIntStateOf(-1) }
    val touch = if (tipTitles == null || values.isEmpty()) Modifier
    else Modifier.chartSelect(values, { sel }, { sel = it }) { p, sz ->
        if (values.size > 1) (p.x / (sz.width.toFloat() / (values.size - 1))).roundToInt().coerceIn(0, values.size - 1) else 0
    }
    Canvas(modifier.fillMaxWidth().height(chartHeight + 22.dp).then(touch)) {
        val n = values.size
        if (n == 0) return@Canvas
        val max = cum[n - 1].coerceAtLeast(1)
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
        if (tipTitles != null && sel in 0 until n) {
            val x = if (n > 1) stepX * sel else size.width / 2f
            val y = pad + chartH * (1f - cum[sel].toFloat() / max)
            drawCircle(line, radius = 5.dp.toPx(), center = Offset(x, y))
            drawTip(tm, tip, tipTitles.getOrElse(sel) { "" }, tipValue(cum[sel]), x, y - 4.dp.toPx())
        }
    }
}

/** Füllfarbe einer Zelle der Tabellen: leer, oder je mehr Wiedergaben, desto kräftiger. */
private fun heatColor(v: Int, max: Int, strong: Color, empty: Color): Color =
    if (v <= 0) empty else strong.copy(alpha = 0.25f + 0.75f * v / max)

/**
 * Tage als Tabelle: Spalten sind Wochen, Zeilen Montag bis Sonntag; je mehr Wiedergaben, desto kräftiger die Farbe.
 * Oben stehen die Monate ([monthLabels], je Spalte), links jeder zweite Wochentag ([rowLabels], 7 Namen ab Montag).
 * [startDay] ist die Nummer des Tages, mit dem die erste Spalte beginnt. Ein Tippen auf einen Tag zeigt Datum und Wert.
 */
@Composable
fun StatHeatmap(
    heat: IntArray, weeks: Int, startDay: Long, monthLabels: List<String>, rowLabels: List<String>,
    modifier: Modifier = Modifier, tipTitle: (Long) -> String = { it.toString() }, tipValue: (Int) -> String = { it.toString() }
) {
    val strong = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val tip = tipStyle()
    val density = LocalDensity.current
    var sel by remember(heat) { mutableIntStateOf(-1) }   // Spalte * 7 + Zeile
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val leftW = 22.dp
        val topH = 18.dp
        val cell = min((maxWidth - leftW).value / weeks.coerceAtLeast(1), 26f).dp
        val touch = Modifier.chartSelect(heat, { sel }, { sel = it }) { p, _ ->
            val fx = (p.x - with(density) { leftW.toPx() }) / with(density) { cell.toPx() }
            val fy = (p.y - with(density) { topH.toPx() }) / with(density) { cell.toPx() }
            if (fx < 0f || fy < 0f) -1
            else {
                val col = fx.toInt()
                val row = fy.toInt()
                if (col >= weeks || row >= 7 || heat[col * 7 + row] < 0) -1 else col * 7 + row
            }
        }
        Canvas(Modifier.width(leftW + cell * weeks).height(topH + cell * 7).then(touch)) {
            val max = (heat.maxOrNull() ?: 0).coerceAtLeast(1)
            val c = cell.toPx()
            val lw = leftW.toPx()
            val th = topH.toPx()
            val gap = (c * 0.14f).coerceAtLeast(1.dp.toPx())
            drawLabels(tm, labelStyle, muted, monthLabels, { w -> lw + w * c + c / 2f }, 0f)
            for (d in 0 until 7 step 2) {
                val name = rowLabels.getOrNull(d) ?: continue
                val r = tm.measure(name, labelStyle)
                drawText(r, color = muted, topLeft = Offset(0f, th + d * c + (c - r.size.height) / 2f))
            }
            for (w in 0 until weeks) for (d in 0 until 7) {
                val v = heat[w * 7 + d]
                if (v < 0) continue
                drawRoundRect(
                    heatColor(v, max, strong, empty), topLeft = Offset(lw + w * c + gap / 2f, th + d * c + gap / 2f),
                    size = Size(c - gap, c - gap), cornerRadius = CornerRadius((c - gap) * 0.3f)
                )
            }
            if (sel in heat.indices && heat[sel] >= 0) {
                val col = sel / 7
                val row = sel % 7
                drawRoundRect(
                    tip.fg, topLeft = Offset(lw + col * c + gap / 2f, th + row * c + gap / 2f),
                    size = Size(c - gap, c - gap), cornerRadius = CornerRadius((c - gap) * 0.3f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawTip(tm, tip, tipTitle(startDay + col * 7L + row), tipValue(heat[sel]), lw + col * c + c / 2f, th + row * c)
            }
        }
    }
}

/**
 * Wiedergaben nach Wochentag und Stunde als Tabelle: 24 Spalten (Stunden), 7 Zeilen ab Montag. [cells] ist wie
 * Wochentag * 24 + Stunde aufgebaut. Ein Tippen zeigt über [tipTitle] (Wochentag, Stunde) und [tipValue] den Wert.
 */
@Composable
fun StatWeekHours(
    cells: IntArray, rowLabels: List<String>, modifier: Modifier = Modifier,
    tipTitle: (Int, Int) -> String = { d, h -> "$d $h" }, tipValue: (Int) -> String = { it.toString() }
) {
    val strong = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val tip = tipStyle()
    val density = LocalDensity.current
    val hourLabels = remember { List(24) { if (it % 3 == 0) it.toString().padStart(2, '0') else "" } }
    var sel by remember(cells) { mutableIntStateOf(-1) }   // Wochentag * 24 + Stunde
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val leftW = 22.dp
        val topH = 18.dp
        val cell = min((maxWidth - leftW).value / 24f, 18f).dp
        val touch = Modifier.chartSelect(cells, { sel }, { sel = it }) { p, _ ->
            val fx = (p.x - with(density) { leftW.toPx() }) / with(density) { cell.toPx() }
            val fy = (p.y - with(density) { topH.toPx() }) / with(density) { cell.toPx() }
            if (fx < 0f || fy < 0f) -1
            else {
                val col = fx.toInt()
                val row = fy.toInt()
                if (col >= 24 || row >= 7) -1 else row * 24 + col
            }
        }
        Canvas(Modifier.width(leftW + cell * 24).height(topH + cell * 7).then(touch)) {
            val max = (cells.maxOrNull() ?: 0).coerceAtLeast(1)
            val c = cell.toPx()
            val lw = leftW.toPx()
            val th = topH.toPx()
            val gap = (c * 0.14f).coerceAtLeast(1.dp.toPx())
            drawLabels(tm, labelStyle, muted, hourLabels, { h -> lw + h * c + c / 2f }, 0f)
            for (d in 0 until 7 step 2) {
                val name = rowLabels.getOrNull(d) ?: continue
                val r = tm.measure(name, labelStyle)
                drawText(r, color = muted, topLeft = Offset(0f, th + d * c + (c - r.size.height) / 2f))
            }
            for (d in 0 until 7) for (h in 0 until 24) {
                drawRoundRect(
                    heatColor(cells[d * 24 + h], max, strong, empty),
                    topLeft = Offset(lw + h * c + gap / 2f, th + d * c + gap / 2f),
                    size = Size(c - gap, c - gap), cornerRadius = CornerRadius((c - gap) * 0.3f)
                )
            }
            if (sel in cells.indices) {
                val d = sel / 24
                val h = sel % 24
                drawRoundRect(
                    tip.fg, topLeft = Offset(lw + h * c + gap / 2f, th + d * c + gap / 2f),
                    size = Size(c - gap, c - gap), cornerRadius = CornerRadius((c - gap) * 0.3f),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawTip(tm, tip, tipTitle(d, h), tipValue(cells[sel]), lw + h * c + c / 2f, th + d * c)
            }
        }
    }
}

/**
 * 24-Stunden-Uhr: je Stunde ein Strich, so lang wie die Zahl der Wiedergaben; in der Mitte die stärkste Stunde, außen die
 * Stunden 00, 06, 12 und 18. Mit [tipValue] lässt sich eine Stunde antippen: Sie bleibt hervorgehoben, und die Mitte zeigt
 * ihre Uhrzeit und den Wert.
 */
@Composable
fun StatClock(
    hours: IntArray, peakLabel: String, centerSub: String, modifier: Modifier = Modifier, diameter: Dp = 232.dp,
    tipValue: ((Int) -> String)? = null
) {
    val strong = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    var sel by remember(hours) { mutableIntStateOf(-1) }
    val labelRoom = 24.dp   // Platz außen für die Stundenmarken
    val touch = if (tipValue == null) Modifier
    else Modifier.pointerInput(hours) {
        detectTapGestures { p ->
            val dx = p.x - size.width / 2f
            val dy = p.y - size.height / 2f
            val outer = size.minDimension / 2f - labelRoom.toPx()
            val inner = outer * 0.5f
            val r = sqrt(dx * dx + dy * dy)
            var hit = -1
            if (r >= inner * 0.7f && r <= outer + 12.dp.toPx()) {
                // 0 Grad liegt rechts; die Uhr beginnt oben, jede Stunde ist 15 Grad breit und um ihren Strich zentriert
                val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                hit = ((((deg + 90f + 360f) % 360f) + 7.5f) / 15f).toInt() % 24
            }
            sel = if (hit == sel) -1 else hit
        }
    }
    Box(modifier.size(diameter).then(touch), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
            val outer = size.minDimension / 2f - labelRoom.toPx()
            val inner = outer * 0.5f
            val stroke = 6.dp.toPx()
            for (h in 0 until 24) {
                val a = Math.toRadians((h * 15 - 90).toDouble())
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val len = (outer - inner) * hours[h] / max
                val start = Offset(center.x + dx * inner, center.y + dy * inner)
                val end = Offset(center.x + dx * (inner + len.coerceAtLeast(2.dp.toPx())), center.y + dy * (inner + len.coerceAtLeast(2.dp.toPx())))
                val base = if (hours[h] > 0) strong else empty
                drawLine(if (sel >= 0 && sel != h) base.copy(alpha = base.alpha * 0.4f) else base, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
            }
            // Stundenmarken oben (00), rechts (06), unten (12) und links (18)
            val away = 4.dp.toPx() + stroke / 2f
            val m0 = tm.measure("00", labelStyle)
            val m6 = tm.measure("06", labelStyle)
            val m12 = tm.measure("12", labelStyle)
            val m18 = tm.measure("18", labelStyle)
            drawText(m0, color = muted, topLeft = Offset(center.x - m0.size.width / 2f, center.y - outer - away - m0.size.height))
            drawText(m6, color = muted, topLeft = Offset(center.x + outer + away, center.y - m6.size.height / 2f))
            drawText(m12, color = muted, topLeft = Offset(center.x - m12.size.width / 2f, center.y + outer + away))
            drawText(m18, color = muted, topLeft = Offset(center.x - outer - away - m18.size.width, center.y - m18.size.height / 2f))
        }
        androidx.compose.foundation.layout.Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (sel >= 0 && tipValue != null) {
                Text(sel.toString().padStart(2, '0') + ":00", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    tipValue(hours[sel]), style = MaterialTheme.typography.labelMedium, color = muted, textAlign = TextAlign.Center
                )
            } else {
                Text(peakLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(centerSub, style = MaterialTheme.typography.labelMedium, color = muted)
            }
        }
    }
}

/** Ein Abschnitt des Kreisrings: [value] bestimmt seine Größe, [color] seine Farbe. */
class DonutSlice(val value: Int, val color: Color)

/** Ringdiagramm: die Abschnitte teilen den Kreis im Verhältnis ihrer Werte, getrennt durch eine kleine Lücke; in der Mitte zwei Zeilen Text. */
@Composable
fun StatDonut(
    slices: List<DonutSlice>, centerText: String, subText: String, modifier: Modifier = Modifier, diameter: Dp = 150.dp
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 24.dp.toPx()
            val d = size.minDimension - w
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arc = Size(d, d)
            val total = slices.sumOf { it.value }
            if (total <= 0) {
                drawArc(track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(width = w))
                return@Canvas
            }
            val visible = slices.count { it.value > 0 }
            val gap = if (visible > 1) 2.5f else 0f
            var start = -90f
            for (s in slices) {
                if (s.value <= 0) continue
                val sweep = 360f * s.value / total
                drawArc(
                    s.color, startAngle = start + gap / 2f, sweepAngle = (sweep - gap).coerceAtLeast(0.5f), useCenter = false,
                    topLeft = topLeft, size = arc, style = Stroke(width = w)
                )
                start += sweep
            }
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
