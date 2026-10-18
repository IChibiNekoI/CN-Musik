package com.example.musikplayer

import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import kotlin.math.max
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.AnimationVector1D

/** Dauer der Öffnen-Animation des Players in Millisekunden (höher = mehr Zwischenframes, langsamer) */
private const val OPEN_ANIM_MS = 300

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Position, Eckenrundung und Farbe des Miniplayers - Startpunkt der Öffnen-Animation */
data class MiniOrigin(
    val bounds: androidx.compose.ui.geometry.Rect,
    val radius: Dp,
    val color: Color,
    /** Position des kleinen Covers im Miniplayer - Startpunkt des "fliegenden" Covers */
    val coverBounds: androidx.compose.ui.geometry.Rect? = null
)

/** Zeigt den Player als Ausschnitt, der vom Miniplayer-Rechteck auf Vollbild aufgeht */
private class SheetRevealShape(
    private val origin: MiniOrigin?,
    private val progress: Float,
    private val topCornerDp: Float
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val d = density.density
        val topEnd = topCornerDp * d
        if (origin == null || progress >= 1f) {
            val full = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
            val top = androidx.compose.ui.geometry.CornerRadius(topEnd)
            return androidx.compose.ui.graphics.Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(
                    full, top, top,
                    androidx.compose.ui.geometry.CornerRadius.Zero,
                    androidx.compose.ui.geometry.CornerRadius.Zero
                )
            )
        }
        val b = origin.bounds
        val e = progress
        val rect = androidx.compose.ui.geometry.Rect(
            androidx.compose.ui.util.lerp(b.left, 0f, e),
            androidx.compose.ui.util.lerp(b.top, 0f, e),
            androidx.compose.ui.util.lerp(b.right, size.width, e),
            androidx.compose.ui.util.lerp(b.bottom, size.height, e)
        )
        val mini = origin.radius.value * d
        val top = androidx.compose.ui.geometry.CornerRadius(androidx.compose.ui.util.lerp(mini, topEnd, e))
        val bottom = androidx.compose.ui.geometry.CornerRadius(androidx.compose.ui.util.lerp(mini, 0f, e))
        return androidx.compose.ui.graphics.Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(rect, top, top, bottom, bottom)
        )
    }
}

@Composable
fun NowPlayingSheet(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    showAudioInfo: Boolean = false,
    origin: MiniOrigin? = null,
    controlledOpen: Animatable<Float, AnimationVector1D>? = null,
    onGoToAlbum: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onDismiss: () -> Unit
) {
    // Der Player ist immer dunkel -> Statusleisten-Icons hell halten
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = false
        ctrl?.isAppearanceLightNavigationBars = false
        onDispose {
            if (ctrl != null) {
                if (prevStatus != null) ctrl.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) ctrl.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
    CoverTheme(song, dark = true) {
        NowPlayingSheetContent(song, isPlaying, shuffle, repeat, c, showAudioInfo, origin, controlledOpen, onGoToAlbum, onAddToPlaylist, isFavorite, onToggleFavorite, onDismiss)
    }
}

@Composable
private fun NowPlayingSheetContent(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    showAudioInfo: Boolean,
    origin: MiniOrigin?,
    controlledOpen: Animatable<Float, AnimationVector1D>?,
    onGoToAlbum: () -> Unit,
    onAddToPlaylist: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    // Öffnen-Animation: der Miniplayer wächst zum Vollbild-Player (0 = Miniplayer, 1 = Vollbild)
    // Beim Hochziehen des Miniplayers steuert der Finger den Fortschritt (controlledOpen), sonst läuft er von selbst
    val ownOpen = remember { Animatable(if (origin == null) 1f else 0f) }
    val open = controlledOpen ?: ownOpen
    LaunchedEffect(Unit) {
        if (origin != null && controlledOpen == null) {
            // Das erste Layout des Players ist teuer. Zwei Frames abwarten, damit es nicht
            // in die Animation fällt und sie am Anfang nicht ruckelt.
            withFrameNanos { }
            withFrameNanos { }
            open.animateTo(
                1f,
                // länger + sanfterer Start: die Bewegung verteilt sich auf mehr Frames statt in den ersten
                // Frames fast komplett zu springen (vorher 360 ms mit sehr steilem Anfang)
                androidx.compose.animation.core.tween(
                    durationMillis = OPEN_ANIM_MS,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    val infoCtx = LocalContext.current
    LaunchedEffect(song.id) {
        kotlinx.coroutines.delay(500)
        SkipDirection.dir = 1
    }
    val audioInfo by produceState<String?>(null, song.id, showAudioInfo) {
        value = if (!showAudioInfo) null
        else {
            // erst nach der Öffnen-Animation laden, sonst löst das Ergebnis mittendrin eine Neuzeichnung aus
            if (open.value < 1f) snapshotFlow { open.value }.first { it >= 1f }
            withContext(Dispatchers.IO) { loadAudioInfo(infoCtx, song) }
        }
    }
    var position by remember { mutableLongStateOf(c.currentPosition) }
    var duration by remember { mutableLongStateOf(max(c.duration, 0L)) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val coverOffset = remember { Animatable(0f) }
    val coverScope = rememberCoroutineScope()

    LaunchedEffect(c) {
        while (true) {
            // während der Öffnen-Animation nicht aktualisieren (vermeidet Neuzeichnen mitten in der Animation)
            if (open.value >= 1f) {
                position = c.currentPosition
                duration = max(c.duration, 0L)
            }
            delay(250)
        }
    }
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    val shownFraction = if (dragging) dragFraction else fraction
    val shownMs = (shownFraction * duration).toLong()

          var offsetY by remember { mutableFloatStateOf(0f) }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        offsetY = (offsetY + delta).coerceAtLeast(0f)
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    // Ecken werden beim Runterziehen nach und nach rund (bis 38.dp)
    val density = LocalDensity.current.density
    // Endposition des großen Covers (für die Flug-Animation) und ob geflogen wird
    var finalCover by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val hasFly = origin?.coverBounds != null
    val dens = LocalDensity.current
    val sheetColor = MaterialTheme.colorScheme.surface
    // open.value wird nur in Lambdas der Zeichenphase gelesen; der Inhalt wird pro Frame nicht neu komponiert
    val flying by remember { derivedStateOf { open.value < 1f } }
    Box(Modifier.fillMaxSize()) {
        // Abdunkelung der Liste hinter dem aufgehenden Player
        if (origin != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = open.value * 0.55f * (1f - (offsetY / size.height).coerceIn(0f, 1f))
                    }
                    .background(Color.Black)
            )
        }
        Surface(
            Modifier
                .fillMaxSize()
                .offset { androidx.compose.ui.unit.IntOffset(0, offsetY.toInt()) }
                .draggable(
                    state = dragState,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (offsetY > 400f || velocity > 1200f) {
                            androidx.compose.animation.core.animate(
                                offsetY, 3000f,
                                animationSpec = androidx.compose.animation.core.tween(200)
                            ) { value, _ -> offsetY = value }
                            onDismiss()
                        } else {
                            androidx.compose.animation.core.animate(offsetY, 0f) { value, _ ->
                                offsetY = value
                            }
                        }
                    }
                )
                .pointerInput(Unit) {}
                .graphicsLayer {
                    // Ecken werden beim Runterziehen nach und nach rund (bis 38.dp)
                    val corner = ((offsetY / density) / 24f).coerceIn(0f, 1f) * 38f
                    shape = SheetRevealShape(origin, open.value, corner)
                    clip = true
                }
                .drawBehind {
                    val color = if (origin == null) sheetColor else
                        androidx.compose.ui.graphics.lerp(origin.color, sheetColor, open.value)
                            .copy(alpha = (open.value / 0.3f).coerceIn(0f, 1f))
                    drawRect(color)
                },
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            // Hintergrund hinter den Bedienelementen: Einfarbig, Farbverlauf oder verschwommenes Cover
            PlayerBackdrop(song, CornerSettings.playerBg, open)
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp)
            ) {
                val coverSize = minOf(maxWidth, maxHeight * 0.55f)
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth().reveal({ open.value }, 0.30f, 0.75f, 0.dp)) {
                        CloseSheetButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart))
                        Text(
                            song.album.ifBlank { "Unbekanntes Album" },
                            // Albumname: genau in der Mitte des Bildschirms, solange er nicht zu lang ist.
                            // Ist er zu lang, beginnt er direkt neben dem Pfeil und füllt den Platz bis zur Menü-Taste.
                            // Platz links: Pfeil 48 dp + 8 dp Abstand, rechts: Menü-Taste 36 dp + 8 dp Abstand
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .layout { measurable, constraints ->
                                    val left = 56.dp.roundToPx()
                                    val right = 44.dp.roundToPx()
                                    val total = constraints.maxWidth
                                    val placeable = measurable.measure(
                                        constraints.copy(minWidth = 0, maxWidth = (total - left - right).coerceAtLeast(0))
                                    )
                                    layout(total, placeable.height) {
                                        placeable.placeRelative(maxOf(left, (total - placeable.width) / 2), 0)
                                    }
                                },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),   // wie die Audio-Infos
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(Modifier.align(Alignment.CenterEnd)) {
                            MoreButton(onClick = { menuOpen = true }, modifier = Modifier.padding(end = 4.dp))
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Titel-Infos") },
                                    leadingIcon = { InfoIcon() },
                                    onClick = { menuOpen = false; showInfo = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Tags bearbeiten") },
                                    leadingIcon = { EditIcon() },
                                    onClick = { menuOpen = false; showTags = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Zum Album gehen") },
                                    leadingIcon = { AlbumDiscIcon() },
                                    onClick = { menuOpen = false; onGoToAlbum() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Zu Playlist hinzufügen") },
                                    leadingIcon = {
                                        PlaylistAddIcon()
                                    },
                                    onClick = { menuOpen = false; onAddToPlaylist() }
                                )
                            }
                            if (showInfo) SongInfoDialog(song) { showInfo = false }
                            if (showTags) TagEditDialog(song) { showTags = false }
                        }
                    }
                    Spacer(Modifier.weight(1.25f))   // Abstand oben: kleiner = Cover weiter oben
                    Box(
                        Modifier
                            .onGloballyPositioned { finalCover = it.boundsInRoot() }
                            .graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                // beim Öffnen übernimmt das fliegende Cover; danach wird getauscht
                                alpha = if (hasFly) (if (open.value < 1f) 0f else 1f)
                                else ((open.value - 0.35f) / 0.45f).coerceIn(0f, 1f)
                            }
                            .offset { IntOffset(coverOffset.value.roundToInt(), 0) }
                            .pointerInput(c) {
                                val threshold = size.width * 0.25f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val dx = coverOffset.value
                                        if (dx < -threshold) skipNext(c)
                                        else if (dx > threshold) skipPrev(c)
                                        coverScope.launch { coverOffset.animateTo(0f) }
                                    },
                                    onDragCancel = {
                                        coverScope.launch { coverOffset.animateTo(0f) }
                                    },
                                    onHorizontalDrag = { change, delta ->
                                        change.consume()
                                        coverScope.launch {
                                            coverOffset.snapTo(coverOffset.value + delta)
                                        }
                                    }
                                )
                            }
                    ) {
                        // Beim Titelwechsel gleitet das alte Cover heraus und das neue herein
                        // (vorwärts von rechts, zurück von links). Ändern sich nur die Tags, bleibt das Cover ruhig.
                        AnimatedContent(
                            targetState = song,
                            contentKey = { it.id },
                            transitionSpec = {
                                val dir = SkipDirection.dir
                                (slideInHorizontally(
                                    animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
                                    initialOffsetX = { w -> dir * w / 4 }
                                ) + fadeIn(tween(Motion.MEDIUM - 40))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
                                        targetOffsetX = { w -> -dir * w / 4 }
                                    ) + fadeOut(tween(Motion.FAST))) using SizeTransform(clip = false)
                            },
                            label = "cover"
                        ) { s -> Cover(s, coverSize, corner = 20.dp) }
                    }
                    Spacer(Modifier.weight(1.7f))    // Abstand Cover -> Titel
                    // Titel links, Favoriten-Herz rechts auf gleicher Höhe
                    Row(
                        Modifier.fillMaxWidth().reveal({ open.value }, 0.35f, 0.85f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            song.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 6 dp nach rechts: das Herz schließt bündig mit dem Cover ab
                        FavoriteButton(
                            isFavorite = isFavorite,
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(40.dp).offset(x = 6.dp)
                        )
                    }
                    Text(
                        song.artist,
                        modifier = Modifier.fillMaxWidth().reveal({ open.value }, 0.40f, 0.88f),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(0.8f))    // Abstand Künstler -> Fortschrittsbalken

                    Column(Modifier.fillMaxWidth().reveal({ open.value }, 0.45f, 0.92f)) {
                        PillProgress(
                            progress = shownFraction,
                            onChange = { dragging = true; dragFraction = it },
                            onFinished = {
                                val target = (dragFraction * duration).toLong()
                                c.seekTo(target)
                                position = target
                                dragging = false
                            }
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(fmt(shownMs), style = MaterialTheme.typography.labelMedium)
                            Text(fmt(duration), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.weight(1.0f))    // Abstand Balken -> Tasten

                    Row(
                        Modifier.fillMaxWidth().reveal({ open.value }, 0.50f, 1.0f, 32.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ShuffleButton(active = shuffle, onClick = { c.shuffleModeEnabled = !shuffle })
                        SkipButton(forward = false, onClick = { skipPrev(c) })
                        PlayPauseButton(
                            isPlaying = isPlaying,
                            onClick = { if (isPlaying) c.pause() else c.play() },
                            buttonSize = 76.dp
                        )
                        SkipButton(forward = true, onClick = { skipNext(c) })
                        RepeatButton(
                            active = repeat != Player.REPEAT_MODE_OFF,
                            repeatOne = repeat == Player.REPEAT_MODE_ONE,
                            onClick = {
                                c.repeatMode = when (repeat) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().weight(1.95f).reveal({ open.value }, 0.60f, 1.0f, 16.dp),   // Abstand unten
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        audioInfo?.let {
                            Text(
                                it,
                                modifier = Modifier.padding(bottom = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),   // Akzentfarbe, gleiche Deckkraft wie vorher
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        // Das Cover fliegt vom Miniplayer an seinen Platz im Player
        val fb = origin?.coverBounds
        val fin = finalCover
        if (fb != null && fin != null && flying) {
            val finalDp = with(dens) { fin.width.toDp() }
            val coverStyle = LocalCoverStyle.current
            val r0 = coverRadius(coverStyle, 56.dp, 12.dp).value   // Rundung im Miniplayer
            val r1 = coverRadius(coverStyle, finalDp, 20.dp).value // Rundung im Player
            Box(
                Modifier
                    .size(finalDp)
                    .graphicsLayer {
                        val e = open.value
                        val sc = androidx.compose.ui.util.lerp(fb.width / fin.width, 1f, e)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        translationX = androidx.compose.ui.util.lerp(fb.left, fin.left, e)
                        translationY = androidx.compose.ui.util.lerp(fb.top, fin.top, e)
                        // Ecken: von der Rundung im Mini zu der im Player, vor der Skalierung umgerechnet
                        val eff = androidx.compose.ui.util.lerp(r0, r1, e) * density
                        shape = RoundedCornerShape(eff / sc)
                        clip = true
                    }
            ) {
                Cover(song, finalDp, corner = 0.dp, fallbackSize = 56.dp)
            }
        }
    }
}

/** Blendet ein Element gestaffelt ein (progress zwischen start und end) und schiebt es leicht nach oben */
private fun Modifier.reveal(progress: () -> Float, start: Float, end: Float, rise: Dp = 24.dp): Modifier =
    graphicsLayer {
        // Alpha direkt beim Zeichnen anwenden statt über einen Zwischenspeicher (spart GPU-Arbeit pro Frame)
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        val t = ((progress() - start) / (end - start)).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * rise.toPx()
    }
