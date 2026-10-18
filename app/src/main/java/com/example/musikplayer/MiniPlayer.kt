package com.example.musikplayer

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
    val buildOrigin: () -> MiniOrigin? = {
        coordsRef[0]?.takeIf { it.isAttached }
            ?.let {
                MiniOrigin(
                    it.boundsInRoot(), radius, miniColor,
                    coverRef[0]?.takeIf { c -> c.isAttached }?.boundsInRoot()
                )
            }
    }
    val openPlayer: () -> Unit = { onOpen(buildOrigin()) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        dragTotal += delta
        onDrag(dragTotal)
    }

    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(start = margin, end = margin, bottom = margin)
            .onGloballyPositioned { coordsRef[0] = it }
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
            Row(
                Modifier.weight(1f).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null   // kein Aufleuchten beim Drücken/Halten
                ) { openPlayer() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.onGloballyPositioned { coverRef[0] = it }) { Cover(song, 56.dp) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
    progress: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val fillColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val change = rememberUpdatedState(onChange)
    val finished = rememberUpdatedState(onFinished)
    var width by remember { mutableFloatStateOf(1f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
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
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(trackColor)
        )
        // Füllung startet als kleiner Kreis (Höhe = Breite) und füllt den Balken bei 100 % genau aus
        val density = LocalDensity.current
        val minPx = with(density) { 12.dp.toPx() }
        val fillPx = (minPx + progress.coerceIn(0f, 1f) * (width - minPx)).coerceAtLeast(minPx)
        Box(
            Modifier
                .width(with(density) { fillPx.toDp() })
                .height(12.dp)
                .clip(CircleShape)
                .background(fillColor)
        )
    }
}
