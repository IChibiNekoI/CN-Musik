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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    var miniPressed by remember { mutableStateOf(false) }
    val miniScale = animateFloatAsState(
        targetValue = if (miniPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "miniPress"
    )
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
                scaleX = miniScale.value
                scaleY = miniScale.value
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
                modifier = Modifier.weight(1f).clipToBounds().observePress { miniPressed = it }.clickable(
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
    onFinished: () -> Unit,
    elevated: Boolean = false
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val change = rememberUpdatedState(onChange)
    val finished = rememberUpdatedState(onFinished)
    var width by remember { mutableFloatStateOf(1f) }
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
                    change.value((pos.x / width).coerceIn(0f, 1f))
                    finished.value()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> change.value((pos.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { finished.value() },
                    onDragCancel = { finished.value() },
                    onHorizontalDrag = { ch, _ ->
                        ch.consume()
                        change.value((ch.position.x / width).coerceIn(0f, 1f))
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
                if (elevated) {
                    // Rinne wirkt eingelassen: oben dunkler Schatten nach innen, unten ein Hauch Licht
                    val groove = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, top, size.width, top + h, radius))
                    }
                    androidx.compose.ui.graphics.drawscope.clipPath(groove) {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent, Color.White.copy(alpha = 0.10f)),
                                startY = top, endY = top + h
                            ),
                            topLeft = Offset(0f, top), size = Size(size.width, h)
                        )
                    }
                }
                // Füllung startet als kleiner Kreis (Höhe = Breite) und füllt den Balken bei 100 % genau aus
                val fillW = (h + progress().coerceIn(0f, 1f) * (size.width - h)).coerceAtLeast(h)
                if (elevated) {
                    // weicher Schatten unter der Füllung: drei versetzte, immer größere, sehr blasse Lagen
                    for (i in 1..3) {
                        val spread = i * 1.5.dp.toPx()
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.07f),
                            topLeft = Offset(-spread, top + 2.dp.toPx() - spread * 0.4f),
                            size = Size(fillW + 2 * spread, h + 2 * spread),
                            cornerRadius = CornerRadius(h / 2f + spread, h / 2f + spread)
                        )
                    }
                    // Füllung mit leichtem Verlauf (oben heller) statt flacher Farbe
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(fill.copy(alpha = (fill.alpha + 0.15f).coerceAtMost(1f)), fill.copy(alpha = fill.alpha * 0.8f)),
                            startY = top, endY = top + h
                        ),
                        topLeft = Offset(0f, top), size = Size(fillW, h), cornerRadius = radius
                    )
                    // Lichtkante oben und dunklere Unterkante: die Füllung wirkt wie ein erhabener Wulst
                    val bead = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, top, fillW, top + h, radius))
                    }
                    androidx.compose.ui.graphics.drawscope.clipPath(bead) {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.16f)),
                                startY = top, endY = top + h
                            ),
                            topLeft = Offset(0f, top), size = Size(fillW, h)
                        )
                    }
                } else {
                    drawRoundRect(color = fill, topLeft = Offset(0f, top), size = Size(fillW, h), cornerRadius = radius)
                }
            }
    )
}
