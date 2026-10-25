package com.example.musikplayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    c: MediaController,
    // Hochziehen mit dem Finger: Start, Weg (negativ = nach oben) und Ende mit Geschwindigkeit
    onDragStart: (MiniOrigin?) -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: (Float, Float) -> Unit = { _, _ -> },
    // Meldet jede Änderung der Position, damit der Player beim Schließen immer die aktuelle kennt
    onOriginChanged: (MiniOrigin) -> Unit = {},
    onOpen: (MiniOrigin?) -> Unit
) {
      val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val margin = 12.dp
    val deviceCorner: Dp = remember(view) {
        val px = view.rootWindowInsets
            ?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
            ?.radius ?: 0
        with(density) { px.toDp() }
    }
    val radius = if (deviceCorner > 0.dp) maxOf(deviceCorner - margin, 20.dp) else 28.dp
    val miniColor = MaterialTheme.colorScheme.surfaceContainerHigh
    // merkt sich die Position des Miniplayers, damit der Player daraus aufklappen kann
    val coordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val coverRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Großes Player-Cover im Hintergrund vorladen, sobald der Titel wechselt (gleiche Größe wie im Player)
    val warmCtx = LocalContext.current
    val warmView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(song.id) {
        if (!PREWARM_PLAYER_COVER) return@LaunchedEffect
        val px = warmView.width - 2 * with(density) { 24.dp.roundToPx() }
        prewarmPlayerCover(warmCtx, song, px)
    }
    val lastGood = remember { arrayOfNulls<MiniOrigin>(1) }
    val buildOrigin: () -> MiniOrigin? = {
        val fresh = coordsRef[0]?.takeIf { it.isAttached }
            ?.let {
                MiniOrigin(
                    it.boundsInRoot(), radius, miniColor,
                    coverRef[0]?.takeIf { c -> c.isAttached }?.boundsInRoot()
                )
            }
        // Ist die Messung gerade nicht verfügbar, gilt die letzte bekannte Position (besser als keine)
        if (fresh != null) lastGood[0] = fresh
        fresh ?: lastGood[0]
    }
    val openPlayer: () -> Unit = { onOpen(buildOrigin()) }
    // Berührt: der Miniplayer drückt sich leicht ein und federt beim Loslassen zurück
    val miniSource = remember { MutableInteractionSource() }
    val miniPress = rememberPressProgress(miniSource)
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        dragTotal += delta
        onDrag(dragTotal)
    }

    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(start = margin, end = margin, bottom = margin)
            .onGloballyPositioned {
                coordsRef[0] = it
                if (it.isAttached) buildOrigin()?.let(onOriginChanged)
            }
            // nach der Positionsmessung: das Eindrücken verfälscht die gemerkte Startposition des Players nicht
            .graphicsLayer {
                val s = 1f - 0.03f * miniPress.value
                scaleX = s
                scaleY = s
            }
            // Nach oben ziehen öffnet den Player, der Finger steuert dabei die Animation (wie Runterwischen ihn schließt)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = {
                    dragTotal = 0f
                    onDragStart(buildOrigin())
                },
                onDragStopped = { velocity ->
                    onDragEnd(dragTotal, velocity)
                    dragTotal = 0f
                }
            ),
        shape = RoundedCornerShape(radius),
        color = miniColor,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            // rechts kein Innenabstand: die Tasten sitzen dadurch ca. 14 dp weiter rechts
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 0.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Beim Titelwechsel gleiten Cover und Text mit einer kleinen Feder hinein (vorwärts von rechts, zurück von links)
            AnimatedContent(
                targetState = song,
                contentKey = { it.id },
                modifier = Modifier.weight(1f).clipToBounds().observePress(miniSource).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null   // kein Aufleuchten beim Drücken/Halten
                ) { openPlayer() },
                transitionSpec = {
                    val dir = SkipDirection.dir
                    val slide = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.72f, stiffness = 420f)
                    (slideInHorizontally(slide) { w -> dir * w / 3 } +
                        scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), initialScale = 0.8f) +
                        fadeIn(tween(Motion.MEDIUM - 80, delayMillis = 70))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease)) { w -> -dir * w / 4 } +
                            scaleOut(tween(Motion.MEDIUM, easing = Motion.Ease), targetScale = 0.9f) +
                            fadeOut(tween(Motion.FAST))) using SizeTransform(clip = false)
                },
                label = "miniSong"
            ) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.onGloballyPositioned {
                        if (s.id == song.id) {
                            coverRef[0] = it
                            if (coordsRef[0]?.isAttached == true) buildOrigin()?.let(onOriginChanged)
                        }
                    }) { Cover(s, 56.dp) }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            s.artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            SkipButton(forward = false, onClick = { skipPrev(c) }, iconSize = 24.dp)
            PlayPauseButton(
                isPlaying = isPlaying,
                onClick = { if (isPlaying) c.pause() else c.play() },
                buttonSize = 48.dp
            )
            SkipButton(forward = true, onClick = { skipNext(c) }, iconSize = 24.dp)
        }
    }
}

@Composable
fun PillProgress(
    // als Lambda, damit der Wert nur beim Zeichnen gelesen wird: pro Frame wird nur neu gezeichnet, nicht neu komponiert
    progress: () -> Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val change = rememberUpdatedState(onChange)
    val finished = rememberUpdatedState(onFinished)
    var width by remember { mutableFloatStateOf(1f) }
    val scrubCtx = LocalContext.current
    val scrub = remember { ScrubHaptics(scrubCtx) }
    // Berührt der Finger den Balken (auch beim Ziehen), wird er dicker und heller
    var pressed by remember { mutableStateOf(false) }
    // Leicht unterdämpfte Feder: der Balken wächst mit einem kleinen Überschwinger, beim Loslassen federt er zurück
    val grow = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 450f),
        label = "progressGrow"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            // Nur beobachten (nichts verbrauchen): Tippen und Ziehen laufen unverändert weiter
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                    } while (ev.changes.any { it.pressed })
                    pressed = false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    scrub.tap()
                    change.value((pos.x / width).coerceIn(0f, 1f))
                    finished.value()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        val f = (pos.x / width).coerceIn(0f, 1f)
                        scrub.start(f)
                        change.value(f)
                    },
                    onDragEnd = { finished.value() },
                    onDragCancel = { finished.value() },
                    onHorizontalDrag = { ch, _ ->
                        ch.consume()
                        val f = (ch.position.x / width).coerceIn(0f, 1f)
                        scrub.move(f)   // leichtes Raster beim Ziehen, Einrasten an Anfang und Ende
                        change.value(f)
                    }
                )
            }
            .drawBehind {
                // Alles wird erst beim Zeichnen gelesen (Fortschritt und Wachstum), ohne Neu-Komposition oder Neu-Layout
                val g = grow.value
                val thin = 12.dp.toPx()
                val thick = 22.dp.toPx()
                // Der Überschwinger der Feder darf über das Ziel hinaus, aber nie über die Höhe der Berührfläche
                val h = (thin + (thick - thin) * g).coerceIn(thin * 0.8f, maxOf(size.height, thin * 0.8f))
                val top = (size.height - h) / 2f
                val gc = g.coerceIn(0f, 1f)
                val track = baseColor.copy(alpha = 0.14f + 0.08f * gc)
                val fill = baseColor.copy(alpha = 0.6f + 0.25f * gc)
                val radius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(color = track, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = radius)
                // Füllung startet als kleiner Kreis (Höhe = Breite) und füllt den Balken bei 100 % genau aus
                val fillW = (h + progress().coerceIn(0f, 1f) * (size.width - h)).coerceAtLeast(h)
                drawRoundRect(color = fill, topLeft = Offset(0f, top), size = Size(fillW, h), cornerRadius = radius)
            }
    )
}


/** Kleine, feste Wellenform pro Titel: glatter Verlauf aus drei Sinuswellen plus etwas Zufall, an den Rändern flacher */
private fun waveHeights(seed: Long, count: Int): FloatArray {
    val r = java.util.Random(seed * 1103515245L + 12345L)
    val p1 = r.nextFloat() * 6.2832f
    val p2 = r.nextFloat() * 6.2832f
    val p3 = r.nextFloat() * 6.2832f
    val f1 = 0.10f + r.nextFloat() * 0.06f
    val f2 = 0.22f + r.nextFloat() * 0.08f
    val f3 = 0.45f + r.nextFloat() * 0.15f
    return FloatArray(count) { i ->
        val s = kotlin.math.sin(i * f1 + p1) * 0.5f + kotlin.math.sin(i * f2 + p2) * 0.3f + kotlin.math.sin(i * f3 + p3) * 0.2f
        val jitter = (r.nextFloat() - 0.5f) * 0.18f
        var v = 0.58f + 0.34f * s + jitter
        val edge = minOf(i, count - 1 - i)
        if (edge < 2) v *= 0.55f + 0.225f * edge
        v.coerceIn(0.18f, 1f)
    }
}

/**
 * Fortschrittsbalken als Wellenform aus runden, senkrechten Balken. Bedienung wie [PillProgress]
 * (Tippen und Ziehen), beim Berühren werden die Balken etwas höher.
 * Die Form ist dekorativ und pro Titel immer gleich (aus der Titel-ID berechnet), sie zeigt nicht den echten Klang.
 */
@Composable
fun WaveProgress(
    progress: () -> Float,
    seed: Long,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val change = rememberUpdatedState(onChange)
    val finished = rememberUpdatedState(onFinished)
    var width by remember { mutableFloatStateOf(1f) }
    val scrubCtx = LocalContext.current
    val scrub = remember { ScrubHaptics(scrubCtx) }
    var pressed by remember { mutableStateOf(false) }
    val grow = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 450f),
        label = "waveGrow"
    )
    val cache = remember(seed) { HashMap<Int, FloatArray>() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                    } while (ev.changes.any { it.pressed })
                    pressed = false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    scrub.tap()
                    change.value((pos.x / width).coerceIn(0f, 1f))
                    finished.value()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        val f = (pos.x / width).coerceIn(0f, 1f)
                        scrub.start(f)
                        change.value(f)
                    },
                    onDragEnd = { finished.value() },
                    onDragCancel = { finished.value() },
                    onHorizontalDrag = { ch, _ ->
                        ch.consume()
                        val f = (ch.position.x / width).coerceIn(0f, 1f)
                        scrub.move(f)   // leichtes Raster beim Ziehen, Einrasten an Anfang und Ende
                        change.value(f)
                    }
                )
            }
            .drawBehind {
                val g = grow.value
                val gc = g.coerceIn(0f, 1f)
                val n = (size.width / 8.dp.toPx()).toInt().coerceAtLeast(8)
                val heights = cache.getOrPut(n) { waveHeights(seed, n) }
                val pitch = size.width / n
                val bw = pitch * 0.5f
                val p = progress().coerceIn(0f, 1f)
                val track = baseColor.copy(alpha = 0.14f + 0.08f * gc)
                val fill = baseColor.copy(alpha = 0.6f + 0.25f * gc)
                // Erst alle Balken im Hintergrundton, dann dieselben Balken in der Füllfarbe, von links bis zur aktuellen
                // Position abgeschnitten. So läuft die Füllung stufenlos durch die Balken, statt von Balken zu Balken zu springen.
                val progressX = p * size.width
                for (pass in 0..1) {
                    val drawPass: DrawScope.() -> Unit = {
                        for (i in 0 until n) {
                            val h = (size.height * heights[i] * (1f + 0.1f * g)).coerceIn(bw, size.height)
                            val x = i * pitch + (pitch - bw) / 2f
                            drawRoundRect(
                                color = if (pass == 0) track else fill,
                                topLeft = Offset(x, (size.height - h) / 2f),
                                size = Size(bw, h),
                                cornerRadius = CornerRadius(bw / 2f, bw / 2f)
                            )
                        }
                    }
                    if (pass == 0) drawPass()
                    else clipRect(left = 0f, top = 0f, right = progressX, bottom = size.height) { drawPass() }
                }
            }
    )
}
