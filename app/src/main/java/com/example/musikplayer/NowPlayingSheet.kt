package com.expackage com.example.musikplayer

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.TransformOrigin
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException

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
        val e = progress.coerceIn(0f, 1f)
        val rect = androidx.compose.ui.geometry.Rect(
            androidx.compose.ui.util.lerp(b.left, 0f, e),
            androidx.compose.ui.util.lerp(b.top, 0f, e),
            androidx.compose.ui.util.lerp(b.right, size.width, e),
            androidx.compose.ui.util.lerp(b.bottom, size.height, e)
        )
        val mini = origin.radius.value * d
        // Sobald man am Player zieht, werden die Ecken innerhalb der ersten ~6 % des Wegs rund (statt erst
        // kurz vor dem Miniplayer), damit oben und unten keine harten Kanten sichtbar werden
        val rounded = 38f * d * ((1f - e) / 0.06f).coerceIn(0f, 1f)
        val top = androidx.compose.ui.geometry.CornerRadius(androidx.compose.ui.util.lerp(mini, maxOf(topEnd, rounded), e))
        val bottom = androidx.compose.ui.geometry.CornerRadius(androidx.compose.ui.util.lerp(mini, rounded, e))
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
    library: List<Song> = emptyList(),
    showAudioInfo: Boolean = false,
    origin: MiniOrigin? = null,
    motion: SheetMotion,
    // true, wenn der Finger den Player beim Hochziehen bereits aufgezogen hat (dann keine eigene Öffnen-Animation)
    fingerDriven: Boolean = false,
    onGoToAlbum: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onQueueRemoved: (Song) -> Unit = {},
    onDismiss: () -> Unit
) {
    // Der Player folgt dem hellen oder dunklen Modus der App; die Icons der Statusleiste passen sich an
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, dark) {
        val window = (view.context as? android.app.Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = !dark
        ctrl?.isAppearanceLightNavigationBars = !dark
        onDispose {
            if (ctrl != null) {
                if (prevStatus != null) ctrl.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) ctrl.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
    CoverTheme(song, dark = dark) {
        NowPlayingSheetContent(song, isPlaying, shuffle, repeat, c, library, showAudioInfo, origin, motion, fingerDriven, onGoToAlbum, onAddToPlaylist, isFavorite, onToggleFavorite, onQueueRemoved, onDismiss)
    }
}

@Composable
private fun NowPlayingSheetContent(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    library: List<Song>,
    showAudioInfo: Boolean,
    origin: MiniOrigin?,
    motion: SheetMotion,
    fingerDriven: Boolean,
    onGoToAlbum: () -> Unit,
    onAddToPlaylist: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onQueueRemoved: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    // Farbe und Hintergrund von Vorgänger und Nachfolger vorab laden: der Wechsel startet dann ohne Wartezeit
    val prefetchCtx = LocalContext.current
    LaunchedEffect(song.id, library) {
        for (idx in listOf(c.nextMediaItemIndex, c.previousMediaItemIndex)) {
            if (idx < 0 || idx >= c.mediaItemCount) continue
            val id = c.getMediaItemAt(idx).mediaId.toLongOrNull() ?: continue
            library.firstOrNull { it.id == id }?.let { prefetchCoverColors(prefetchCtx, it) }
        }
    }
    // Öffnen/Schließen: der Miniplayer wächst zum Vollbild-Player und zurück (0 = Miniplayer, 1 = Vollbild).
    // Finger, Loslassen, Zurück-Taste und Zurück-Geste steuern alle denselben Fortschritt (SheetMotion).
    val open = motion.progress
    LaunchedEffect(Unit) {
        if (!fingerDriven && open.value < 1f) {
            // Das erste Layout des Players ist teuer. Zwei Frames abwarten, damit es nicht
            // in die Animation fällt und sie am Anfang nicht ruckelt.
            withFrameNanos { }
            withFrameNanos { }
            motion.settle(1f)
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

    // Anteil des Balkens: wird pro Frame aktualisiert, aber nur beim Zeichnen gelesen (PillProgress),
    // der Player wird dadurch nicht neu komponiert. Die Zeit-Texte ändern sich nur sekundenweise.
    val frac = remember {
        mutableFloatStateOf(
            if (c.duration > 0) (c.currentPosition.toFloat() / c.duration).coerceIn(0f, 1f) else 0f
        )
    }
    LaunchedEffect(c, isPlaying, song.id) {
        while (true) {
            val d = max(c.duration, 0L)
            val p = c.currentPosition
            frac.floatValue = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
            // während der Öffnen-Animation und beim Ziehen nicht neu komponieren
            if (open.value >= 1f && !dragging) {
                if (p / 1000 != position / 1000) position = p
                if (d != duration) duration = d
            }
            // Der Balken wandert nur wenige Pixel pro Sekunde: ca. 10 Aktualisierungen pro Sekunde sehen genauso
            // flüssig aus wie 60 oder 120, halten aber GPU und Prozessor im Leerlauf.
            // Das withFrameNanos danach hält die Schleife an, solange die App nicht sichtbar ist.
            // In der Pause genügt gelegentliches Nachsehen.
            if (isPlaying) { delay(PROGRESS_TICK_MS); withFrameNanos { } } else delay(250)
        }
    }
    val shownMs = if (dragging) (dragFraction * duration).toLong() else position

          var offsetY by remember { mutableFloatStateOf(0f) }
    // Warteschlange: von unten hochwischen (Start im Bereich unter den Tasten) öffnet sie
    // Auf 0..1 begrenzt: mit viel Anfangsschwung darf die Feder die Fläche nicht über die Endlage hinausschieben
    val queueProgress = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    var queueShown by remember { mutableStateOf(false) }
    var queueZoneTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var dragFromQueueZone by remember { mutableStateOf(false) }
    var queueFollowing by remember { mutableStateOf(false) }   // Warteschlange hängt gerade am Finger
    var upDrag by remember { mutableFloatStateOf(0f) }
    var rootHeightPx by remember { mutableFloatStateOf(1f) }
    val queueScope = rememberCoroutineScope()
    val queueAnchor = remember { QueueAnchor() }
    var closing by remember { mutableStateOf(false) }        // Schließen läuft bereits
    var rootWidthPx by remember { mutableFloatStateOf(1f) }
    // Der Player schrumpft beim Schließen immer in den Miniplayer (Umkehrung des Öffnens). Ist dessen Position nicht
    // bekannt (z. B. Start über die Benachrichtigung), dient ein Streifen am unteren Rand als Ziel.
    val miniColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val fallbackDensity = LocalDensity.current
    val navBottomPx = WindowInsets.navigationBars.getBottom(fallbackDensity)
    val fallbackOrigin = remember(rootWidthPx, rootHeightPx, navBottomPx, miniColor) {
        val m = with(fallbackDensity) { 12.dp.toPx() }
        val h = with(fallbackDensity) { 72.dp.toPx() }
        val bottom = rootHeightPx - navBottomPx - m
        MiniOrigin(androidx.compose.ui.geometry.Rect(m, bottom - h, rootWidthPx - m, bottom), 28.dp, miniColor, null)
    }
    val eo: MiniOrigin = origin ?: fallbackOrigin
    val queueZonePad = with(LocalDensity.current) { 24.dp.toPx() }
    // Das Theme wechselt bei geöffneter Warteschlange sofort (keine Welle)
    SideEffect { PlayerUiState.queueOpen = queueShown }
    DisposableEffect(Unit) { onDispose { PlayerUiState.queueOpen = false } }
    // velocity = Fingergeschwindigkeit beim Loslassen in Fortschritt pro Sekunde (positiv = Richtung offen)
    fun openQueue(velocity: Float = 0f) {
        queueShown = true
        queueScope.launch {
            queueProgress.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 320f), velocity)
        }
    }
    fun closeQueue(velocity: Float = 0f) {
        queueScope.launch {
            // dieselbe Feder wie beim Öffnen und beim Player: weich, ohne feste Dauer
            queueProgress.animateTo(0f, sheetSpring(), velocity) {
                // kurz vor dem Ende ausblenden, die Feder klingt sonst noch lange unsichtbar nach
                if (value < 0.02f) queueShown = false
            }
            queueShown = false
        }
    }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        var rest = delta
        if (dragFromQueueZone && !queueShown || queueFollowing) {
            if (delta < 0f && offsetY <= 0f) {
                // nach oben gewischt: die Warteschlange folgt dem Finger
                upDrag += delta
                rest = 0f
            } else if (delta > 0f && upDrag < 0f) {
                // wieder zurück nach unten: erst die Warteschlange zurückschieben, dann erst den Player ziehen
                val used = minOf(delta, -upDrag)
                upDrag += used
                rest = delta - used
            }
            if (upDrag < 0f) {
                queueFollowing = true
                queueShown = true
            }
            if (queueFollowing) {
                val p = (-upDrag / (rootHeightPx * QUEUE_HEIGHT_FRACTION)).coerceIn(0f, 1f)
                queueScope.launch { queueProgress.snapTo(p) }
            }
        }
        // Der offene Player lässt sich nur nach unten ziehen, nach oben bleibt er stehen
        offsetY = (offsetY + rest).coerceAtLeast(0f)
        if (!queueFollowing) {
            // Der Finger steuert den Fortschritt: der Player schrumpft unter dem Finger zum Miniplayer
            val dist = rootHeightPx * SHEET_DRAG_FRACTION
            motion.follow(1f - offsetY.coerceAtLeast(0f) / dist)
        }
    }

    fun requestClose() {
        if (closing) return
        closing = true
        // genau die Umkehrung des Öffnens: Karte schrumpft zurück in den Miniplayer
        motion.settle(0f) { onDismiss() }
    }

    // Zurück-Geste (Android 13+): der Player schrumpft schon während der Geste mit, Loslassen schließt,
    // Abbrechen federt zurück. Auf älteren Android-Versionen verhält es sich wie eine normale Zurück-Taste.
    PredictiveBackHandler { backEvents ->
        try {
            backEvents.collect { e -> motion.follow(1f - e.progress * 0.3f) }
            requestClose()
        } catch (ex: CancellationException) {
            motion.settle(1f)
        }
    }
    // Zurück-Taste schließt zuerst die Warteschlange
    androidx.activity.compose.BackHandler(enabled = queueShown) { closeQueue() }
    // Ecken werden beim Runterziehen nach und nach rund (bis 38.dp)
    val density = LocalDensity.current.density
    // Titel und Cover weichen der aufgehenden Warteschlange aus (Hochformat)
    val queueGeo = remember { QueueGeo() }
    val queueMotion = remember {
        QueueMotion(
            geo = queueGeo,
            progress = { queueProgress.value },
            anchorBottom = { queueAnchor.coords?.takeIf { it.isAttached }?.boundsInRoot()?.bottom },
            rootHeight = { rootHeightPx },
            density = { density }
        )
    }
    // Endposition des großen Covers (für die Flug-Animation) und ob geflogen wird
    var finalCover by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val hasFly = eo.coverBounds != null
    val dens = LocalDensity.current
    val sheetColor = MaterialTheme.colorScheme.surface
    // open.value wird nur in Lambdas der Zeichenphase gelesen; der Inhalt wird pro Frame nicht neu komponiert
    val flying by remember { derivedStateOf { open.value < 1f } }
    Box(
        Modifier.fillMaxSize().onSizeChanged {
            rootHeightPx = it.height.toFloat().coerceAtLeast(1f)
            rootWidthPx = it.width.toFloat().coerceAtLeast(1f)
        }
    ) {
        // Abdunkelung der Liste hinter dem aufgehenden Player
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = open.value * 0.55f }
                .background(Color.Black)
        )
        Surface(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStarted = { start ->
                        // laufende Animation abfangen: der Finger übernimmt genau an der aktuellen Stelle
                        closing = false
                        dragFromQueueZone = start.y >= queueZoneTop - queueZonePad
                        upDrag = 0f
                        val cur = open.value.coerceIn(0f, 1f)
                        motion.follow(cur)
                        offsetY = (1f - cur) * rootHeightPx * SHEET_DRAG_FRACTION
                    },
                    onDragStopped = { velocity ->
                        if (queueFollowing) {
                            // Loslassen: ab etwa einem Drittel oder bei schnellem Wischen ganz öffnen, sonst wieder schließen
                            queueFollowing = false
                            val shownFraction = -upDrag / (rootHeightPx * QUEUE_HEIGHT_FRACTION)
                            upDrag = 0f
                            // Fingergeschwindigkeit (px/s, nach oben negativ) als Fortschritt pro Sekunde an die Feder weitergeben
                            val qv = -velocity / (rootHeightPx * QUEUE_HEIGHT_FRACTION)
                            if (shownFraction > 0.3f || velocity < -900f) openQueue(qv) else closeQueue(qv)
                        } else {
                            // Fortschritt und Geschwindigkeit (pro Sekunde) aus dem Fingerweg ableiten,
                            // dann per Feder weiterlaufen: schnelles Wischen fühlt sich anders an als langsames
                            val dist = rootHeightPx * SHEET_DRAG_FRACTION
                            val p = (1f - offsetY.coerceAtLeast(0f) / dist).coerceIn(0f, 1f)
                            val v = -velocity / dist
                            offsetY = 0f
                            if (p + v * SHEET_PROJECTION_S < 0.5f) {
                                closing = true
                                motion.settle(0f, v, p) { onDismiss() }
                            } else {
                                motion.settle(1f, v, p)
                            }
                        }
                    }
                )
                .pointerInput(Unit) {}
                .graphicsLayer {
                    shape = SheetRevealShape(eo, open.value, 0f)
                    clip = true

                }
                .drawBehind {
                    val color = androidx.compose.ui.graphics.lerp(eo.color, sheetColor, open.value)
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
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                // Querformat: links das Cover, rechts Titel und Steuerung. Hochformat: wie bisher in einer Spalte.
                val landscape = maxWidth > maxHeight * 1.2f
                queueGeo.enabled = !landscape
                // Cover-Größe: Hochformat wie bisher (Breite oder 55 % der Höhe), Querformat durch die Höhe begrenzt
                val coverSize = if (landscape) minOf(maxHeight, maxWidth * 0.46f) else minOf(maxWidth, maxHeight * 0.55f)
                val columnWidth = coverSize

                val headerBlock: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { queueGeo.headerBottom = it.boundsInRoot().bottom }
                            .reveal({ open.value }, 0.30f, 0.75f, 0.dp)
                    ) {
                        // 0 = Player offen, 1 = Kopfzeilen-Symbole ganz flach (wird nur beim Zeichnen gelesen)
                        val headerFlatten: () -> Float = {
                            ((1f - open.value) / 0.25f).coerceIn(0f, 1f)
                        }
                        CloseSheetButton(
                            onClick = { requestClose() },
                            modifier = Modifier.align(Alignment.CenterStart).headerPop { open.value },
                            // Pfeil flacht beim Runterziehen zu einer Linie ab und knickt beim Öffnen wieder ein
                            flatten = headerFlatten
                        )
                        Text(
                            song.album.ifBlank { str(R.string.unknown_album) },
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
                            MoreButton(
                                onClick = { menuOpen = true },
                                // zieht sich oben und unten zusammen, genau im Takt mit dem Pfeil links
                                modifier = Modifier.padding(end = 4.dp).headerPop { open.value }.headerSquash(headerFlatten)
                            )
                            BounceMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(str(R.string.track_info)) },
                                    leadingIcon = { InfoIcon() },
                                    onClick = { menuOpen = false; showInfo = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.edit_tags)) },
                                    leadingIcon = { EditIcon() },
                                    onClick = { menuOpen = false; showTags = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.go_to_album)) },
                                    leadingIcon = { AlbumDiscIcon() },
                                    onClick = { menuOpen = false; onGoToAlbum() }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.add_to_playlist_np)) },
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
                }
                val coverBlock: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .onGloballyPositioned { finalCover = it.boundsInRoot(); queueGeo.cover = it.boundsInRoot() }
                            .graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                // beim Öffnen übernimmt das fliegende Cover; danach wird getauscht
                                alpha = if (hasFly) (if (open.value < 1f) 0f else 1f)
                                else ((open.value - 0.35f) / 0.45f).coerceIn(0f, 1f)
                            }
                            .graphicsLayer {
                                // Warteschlange öffnet: das Cover wird vom Text hochgeschoben und schrumpft nach links oben
                                val x = queueMotion.compute()
                                if (x != null) {
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                    scaleX = x.coverScale
                                    scaleY = x.coverScale
                                    translationY = x.coverDy
                                }
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
                                // Nur ein kurzes Stück gleiten (weniger als der Seitenrand von 24 dp): Das Cover bleibt
                                // ganz auf dem Bildschirm. Sonst wird es am Bildschirmrand abgeschnitten und wirkt dort eckig.
                                val slidePx = with(dens) { 18.dp.roundToPx() }
                                (slideInHorizontally(
                                    animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
                                    initialOffsetX = { dir * slidePx }
                                ) + fadeIn(tween(Motion.MEDIUM - 40))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
                                        targetOffsetX = { -dir * slidePx }
                                    ) + fadeOut(tween(Motion.FAST))) using SizeTransform(clip = false)
                            },
                            label = "cover"
                        ) { s ->
                            Cover(
                                s, coverSize, corner = 20.dp,
                                shadow = if (CornerSettings.coverShadow) COVER_SHADOW else 0.dp
                            )
                        }
                    }
                }
                val titleBlock: @Composable () -> Unit = {
                    Column(
                        Modifier
                            .onGloballyPositioned { queueGeo.title = it.boundsInRoot() }
                            .layout { measurable, constraints ->
                                // Beim Öffnen der Warteschlange wird der Text schmaler (Platz neben dem kleinen Cover).
                                // Die gemeldete Breite bleibt gleich, damit die Spalte nicht mittig rutscht.
                                val x = queueMotion.compute()
                                val w = if (x != null) x.titleWidth.roundToInt().coerceIn(0, constraints.maxWidth) else constraints.maxWidth
                                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = w))
                                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
                            }
                            .graphicsLayer {
                                val x = queueMotion.compute()
                                if (x != null) {
                                    translationX = x.titleDx
                                    translationY = x.titleDy
                                }
                            }
                    ) {
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
                    }
                }
                val progressBlock: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().reveal({ open.value }, 0.45f, 0.92f)) {
                        val progressValue = { if (dragging) dragFraction else frac.floatValue }
                        val progressChange: (Float) -> Unit = { dragging = true; dragFraction = it }
                        val progressDone = {
                            val target = (dragFraction * duration).toLong()
                            c.seekTo(target)
                            position = target
                            frac.floatValue = dragFraction
                            dragging = false
                        }
                        if (CornerSettings.waveProgress) {
                            WaveProgress(progress = progressValue, seed = song.id, onChange = progressChange, onFinished = progressDone)
                        } else {
                            PillProgress(progress = progressValue, onChange = progressChange, onFinished = progressDone)
                        }
                        // Optional mittig zwischen den Zeiten: das Ausgabegerät (bei Kopfhörern mit Symbol)
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(fmt(shownMs), style = MaterialTheme.typography.labelMedium)
                                Text(fmt(duration), style = MaterialTheme.typography.labelMedium)
                            }
                            if (CornerSettings.showOutputDevice) {
                                // Liegt über der Zeile und zählt nicht zu ihrer Höhe: der Fortschrittsbalken bleibt an seinem
                                // Platz, ob ein Gerät verbunden ist oder nicht. Rand links und rechts: Platz für die Zeiten.
                                Box(
                                    Modifier.matchParentSize().padding(horizontal = 56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    OutputDeviceLabel()
                                }
                            }
                        }
                    }
                }
                val controlsBlock: @Composable () -> Unit = {
                    Row(
                        // Lage der Tastenreihe merken: die Warteschlange wächst als Blase direkt darunter heraus
                        Modifier.fillMaxWidth().onGloballyPositioned { queueAnchor.coords = it }.reveal({ open.value }, 0.50f, 1.0f, 32.dp),
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
                }
                val infoBlock: @Composable (Modifier) -> Unit = { extra ->
                    Box(
                        Modifier.fillMaxWidth().then(extra)
                            .onGloballyPositioned { queueZoneTop = it.boundsInRoot().top }
                            .reveal({ open.value }, 0.60f, 1.0f, 16.dp),   // Abstand unten
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

                if (!landscape) {
                    Column(
                        Modifier.fillMaxHeight().width(columnWidth),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        headerBlock()
                        Spacer(Modifier.weight(1.25f))   // Abstand oben: kleiner = Cover weiter oben
                        coverBlock()
                        Spacer(Modifier.weight(1.7f))    // Abstand Cover -> Titel
                        titleBlock()
                        Spacer(Modifier.weight(0.8f))    // Abstand Künstler -> Fortschrittsbalken
                        progressBlock()
                        Spacer(Modifier.weight(1.0f))    // Abstand Balken -> Tasten
                        controlsBlock()
                        infoBlock(Modifier.weight(1.95f))
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // links: Cover, in der Höhe zentriert
                        Box(Modifier.size(coverSize), contentAlignment = Alignment.Center) { coverBlock() }
                        Spacer(Modifier.width(32.dp))
                        // rechts: Kopfzeile oben, darunter Titel, Fortschritt und Tasten
                        Column(
                            Modifier.weight(1f).fillMaxHeight().widthIn(max = 520.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            headerBlock()
                            Spacer(Modifier.weight(1.0f))
                            titleBlock()
                            Spacer(Modifier.weight(0.7f))
                            progressBlock()
                            Spacer(Modifier.weight(0.8f))
                            controlsBlock()
                            infoBlock(Modifier.weight(1.0f))
                        }
                    }
                }
            }
        }
        // Das Cover fliegt vom Miniplayer an seinen Platz im Player
        val fb = eo.coverBounds
        val fin = finalCover
        if (fb != null && fin != null && flying) {
            val finalDp = with(dens) { fin.width.toDp() }
            val coverStyle = LocalCoverStyle.current
            val r0 = coverRadius(coverStyle, 56.dp, 12.dp).value   // Rundung im Miniplayer
            val r1 = coverRadius(coverStyle, finalDp, 20.dp).value // Rundung im Player
            val flightScale = { e: Float ->
                androidx.compose.ui.util.lerp(fb.width / fin.width.coerceAtLeast(1f), 1f, e).coerceAtLeast(0.05f)
            }
            // Ecken: von der Rundung im Mini zu der im Player, vor der Skalierung umgerechnet
            val flightShape = { e: Float, d: Float ->
                RoundedCornerShape(androidx.compose.ui.util.lerp(r0, r1, e) * d / flightScale(e))
            }
            Box(
                Modifier
                    .size(finalDp)
                    .graphicsLayer {
                        val e = coverPath(open.value.coerceIn(0f, 1f))
                        val sc = flightScale(e)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        translationX = androidx.compose.ui.util.lerp(fb.left, fin.left, e)
                        translationY = androidx.compose.ui.util.lerp(fb.top, fin.top, e)
                    }
                    // Schatten (gleiche Lagen wie im Cover) wachsen mit dem Flug an, damit beim Tausch nichts springt
                    .then(
                        if (CornerSettings.coverShadow) {
                            var m: Modifier = Modifier
                            for ((factor, layerAlpha) in CoverShadowLayers) {
                                m = m.graphicsLayer {
                                    val e = coverPath(open.value.coerceIn(0f, 1f))
                                    shape = flightShape(e, density)
                                    clip = false
                                    shadowElevation = COVER_SHADOW.toPx() * factor * e.coerceIn(0f, 1f)
                                    ambientShadowColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = layerAlpha)
                                    spotShadowColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = layerAlpha)
                                }
                            }
                            m
                        } else Modifier
                    )
                    .graphicsLayer {
                        shape = flightShape(coverPath(open.value.coerceIn(0f, 1f)), density)
                        clip = true
                    }
            ) {
                Cover(song, finalDp, corner = 0.dp, fallbackSize = 56.dp)
            }
        }
        if (queueShown) {
            QueuePanel(c, library, queueProgress, queueAnchor, onRemoved = onQueueRemoved) { v -> closeQueue(v) }
        }
    }
}

/** Größe des optionalen Schattens unter dem Cover im Player */
private val COVER_SHADOW = 28.dp

/** Stärke des Überschwingers beim Cover-Flug (0 = keiner). 0.4 ≈ 3 % über das Ziel hinaus, dann zurück. */
private const val COVER_BOUNCE = 0.4f

/** Abstand der Fortschrittsbalken-Aktualisierung beim Abspielen (ms). Der Balken bewegt sich nur wenige Pixel pro Sekunde. */
private const val PROGRESS_TICK_MS = 100L

/**
 * Weg des fliegenden Covers in Abhängigkeit vom Öffnen-Fortschritt [t] (0 = Miniplayer, 1 = Platz im Player).
 * Bei 0 und 1 identisch zum Fortschritt, dazwischen kommt eine kleine Beule dazu: Das Cover schießt kurz
 * ein wenig über sein Ziel und setzt sich dann. Weil es eine reine Funktion des Fortschritts ist, wirkt es
 * beim Öffnen per Tippen und per Hochziehen und umgekehrt beim Schließen (dort leichtes Ausholen),
 * damit Öffnen und Schließen gleich aussehen. Nie negativ, deshalb bleiben Größe und Ecken gültig.
 */
private fun coverPath(t: Float): Float = t + COVER_BOUNCE * kotlin.math.sin(kotlin.math.PI.toFloat() * t)

/** Zieht ein Element von oben und unten zusammen: bei [flatten] = 1 ist es nur noch eine dünne Linie in seiner Mitte */
private fun Modifier.headerSquash(flatten: () -> Float): Modifier = graphicsLayer {
    scaleY = 1f - 0.88f * flatten()
}

/** Kopfzeilen-Tasten wachsen beim Öffnen mit dem Fortschritt auf (und schrumpfen beim Schließen wieder), statt nur einzublenden */
private fun Modifier.headerPop(progress: () -> Float): Modifier = graphicsLayer {
    val t = ((progress() - 0.35f) / 0.4f).coerceIn(0f, 1f)
    val s = 0.55f + 0.45f * t
    scaleX = s
    scaleY = s
}

/** Blendet ein Element gestaffelt ein (progress zwischen start und end) und schiebt es leicht nach oben */
private fun Modifier.reveal(progress: () -> Float, start: Float, end: Float, rise: Dp = 24.dp): Modifier =
    graphicsLayer {
        // Alpha direkt beim Zeichnen anwenden statt über einen Zwischenspeicher (spart GPU-Arbeit pro Frame)
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        val t = ((progress() - start) / (end - start)).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * rise.toPx()
    }ample.musikplayer

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.TransformOrigin
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException

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
        val e = progress.coerceIn(0f, 1f)
        val rect = androidx.compose.ui.geometry.Rect(
            androidx.compose.ui.util.lerp(b.left, 0f, e),
            androidx.compose.ui.util.lerp(b.top, 0f, e),
            androidx.compose.ui.util.lerp(b.right, size.width, e),
            androidx.compose.ui.util.lerp(b.bottom, size.height, e)
        )
        val mini = origin.radius.value * d
        // Sobald man am Player zieht, werden die Ecken innerhalb der ersten ~6 % des Wegs rund (statt erst
        // kurz vor dem Miniplayer), damit oben und unten keine harten Kanten sichtbar werden
        val rounded = 38f * d * ((1f - e) / 0.06f).coerceIn(0f, 1f)
        val top = androidx.compose.ui.geometry.CornerRadius(androidx.compose.ui.util.lerp(mini, maxOf(topEnd, rounded), e))
        val bottom = androidx.compose.ui.geometry.CornerRadius(androidx.compose.ui.util.lerp(mini, rounded, e))
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
    library: List<Song> = emptyList(),
    showAudioInfo: Boolean = false,
    origin: MiniOrigin? = null,
    motion: SheetMotion,
    // true, wenn der Finger den Player beim Hochziehen bereits aufgezogen hat (dann keine eigene Öffnen-Animation)
    fingerDriven: Boolean = false,
    onGoToAlbum: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onQueueRemoved: (Song) -> Unit = {},
    onDismiss: () -> Unit
) {
    // Der Player folgt dem hellen oder dunklen Modus der App; die Icons der Statusleiste passen sich an
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, dark) {
        val window = (view.context as? android.app.Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = !dark
        ctrl?.isAppearanceLightNavigationBars = !dark
        onDispose {
            if (ctrl != null) {
                if (prevStatus != null) ctrl.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) ctrl.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
    CoverTheme(song, dark = dark) {
        NowPlayingSheetContent(song, isPlaying, shuffle, repeat, c, library, showAudioInfo, origin, motion, fingerDriven, onGoToAlbum, onAddToPlaylist, isFavorite, onToggleFavorite, onQueueRemoved, onDismiss)
    }
}

@Composable
private fun NowPlayingSheetContent(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    library: List<Song>,
    showAudioInfo: Boolean,
    origin: MiniOrigin?,
    motion: SheetMotion,
    fingerDriven: Boolean,
    onGoToAlbum: () -> Unit,
    onAddToPlaylist: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onQueueRemoved: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    // Farbe und Hintergrund von Vorgänger und Nachfolger vorab laden: der Wechsel startet dann ohne Wartezeit
    val prefetchCtx = LocalContext.current
    LaunchedEffect(song.id, library) {
        for (idx in listOf(c.nextMediaItemIndex, c.previousMediaItemIndex)) {
            if (idx < 0 || idx >= c.mediaItemCount) continue
            val id = c.getMediaItemAt(idx).mediaId.toLongOrNull() ?: continue
            library.firstOrNull { it.id == id }?.let { prefetchCoverColors(prefetchCtx, it) }
        }
    }
    // Öffnen/Schließen: der Miniplayer wächst zum Vollbild-Player und zurück (0 = Miniplayer, 1 = Vollbild).
    // Finger, Loslassen, Zurück-Taste und Zurück-Geste steuern alle denselben Fortschritt (SheetMotion).
    val open = motion.progress
    LaunchedEffect(Unit) {
        if (!fingerDriven && open.value < 1f) {
            // Das erste Layout des Players ist teuer. Zwei Frames abwarten, damit es nicht
            // in die Animation fällt und sie am Anfang nicht ruckelt.
            withFrameNanos { }
            withFrameNanos { }
            motion.settle(1f)
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

    // Anteil des Balkens: wird pro Frame aktualisiert, aber nur beim Zeichnen gelesen (PillProgress),
    // der Player wird dadurch nicht neu komponiert. Die Zeit-Texte ändern sich nur sekundenweise.
    val frac = remember {
        mutableFloatStateOf(
            if (c.duration > 0) (c.currentPosition.toFloat() / c.duration).coerceIn(0f, 1f) else 0f
        )
    }
    LaunchedEffect(c, isPlaying, song.id) {
        while (true) {
            val d = max(c.duration, 0L)
            val p = c.currentPosition
            frac.floatValue = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
            // während der Öffnen-Animation und beim Ziehen nicht neu komponieren
            if (open.value >= 1f && !dragging) {
                if (p / 1000 != position / 1000) position = p
                if (d != duration) duration = d
            }
            // Beim Abspielen jeden Frame (flüssiger Balken), in der Pause genügt gelegentliches Nachsehen
            if (isPlaying) withFrameNanos { } else delay(250)
        }
    }
    val shownMs = if (dragging) (dragFraction * duration).toLong() else position

          var offsetY by remember { mutableFloatStateOf(0f) }
    // Warteschlange: von unten hochwischen (Start im Bereich unter den Tasten) öffnet sie
    // Auf 0..1 begrenzt: mit viel Anfangsschwung darf die Feder die Fläche nicht über die Endlage hinausschieben
    val queueProgress = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    var queueShown by remember { mutableStateOf(false) }
    var queueZoneTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var dragFromQueueZone by remember { mutableStateOf(false) }
    var queueFollowing by remember { mutableStateOf(false) }   // Warteschlange hängt gerade am Finger
    var upDrag by remember { mutableFloatStateOf(0f) }
    var rootHeightPx by remember { mutableFloatStateOf(1f) }
    val queueScope = rememberCoroutineScope()
    val queueAnchor = remember { QueueAnchor() }
    var closing by remember { mutableStateOf(false) }        // Schließen läuft bereits
    var rootWidthPx by remember { mutableFloatStateOf(1f) }
    // Der Player schrumpft beim Schließen immer in den Miniplayer (Umkehrung des Öffnens). Ist dessen Position nicht
    // bekannt (z. B. Start über die Benachrichtigung), dient ein Streifen am unteren Rand als Ziel.
    val miniColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val fallbackDensity = LocalDensity.current
    val navBottomPx = WindowInsets.navigationBars.getBottom(fallbackDensity)
    val fallbackOrigin = remember(rootWidthPx, rootHeightPx, navBottomPx, miniColor) {
        val m = with(fallbackDensity) { 12.dp.toPx() }
        val h = with(fallbackDensity) { 72.dp.toPx() }
        val bottom = rootHeightPx - navBottomPx - m
        MiniOrigin(androidx.compose.ui.geometry.Rect(m, bottom - h, rootWidthPx - m, bottom), 28.dp, miniColor, null)
    }
    val eo: MiniOrigin = origin ?: fallbackOrigin
    val queueZonePad = with(LocalDensity.current) { 24.dp.toPx() }
    // Das Theme wechselt bei geöffneter Warteschlange sofort (keine Welle)
    SideEffect { PlayerUiState.queueOpen = queueShown }
    DisposableEffect(Unit) { onDispose { PlayerUiState.queueOpen = false } }
    // velocity = Fingergeschwindigkeit beim Loslassen in Fortschritt pro Sekunde (positiv = Richtung offen)
    fun openQueue(velocity: Float = 0f) {
        queueShown = true
        queueScope.launch {
            queueProgress.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 320f), velocity)
        }
    }
    fun closeQueue(velocity: Float = 0f) {
        queueScope.launch {
            // dieselbe Feder wie beim Öffnen und beim Player: weich, ohne feste Dauer
            queueProgress.animateTo(0f, sheetSpring(), velocity) {
                // kurz vor dem Ende ausblenden, die Feder klingt sonst noch lange unsichtbar nach
                if (value < 0.02f) queueShown = false
            }
            queueShown = false
        }
    }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        var rest = delta
        if (dragFromQueueZone && !queueShown || queueFollowing) {
            if (delta < 0f && offsetY <= 0f) {
                // nach oben gewischt: die Warteschlange folgt dem Finger
                upDrag += delta
                rest = 0f
            } else if (delta > 0f && upDrag < 0f) {
                // wieder zurück nach unten: erst die Warteschlange zurückschieben, dann erst den Player ziehen
                val used = minOf(delta, -upDrag)
                upDrag += used
                rest = delta - used
            }
            if (upDrag < 0f) {
                queueFollowing = true
                queueShown = true
            }
            if (queueFollowing) {
                val p = (-upDrag / (rootHeightPx * QUEUE_HEIGHT_FRACTION)).coerceIn(0f, 1f)
                queueScope.launch { queueProgress.snapTo(p) }
            }
        }
        // Der offene Player lässt sich nur nach unten ziehen, nach oben bleibt er stehen
        offsetY = (offsetY + rest).coerceAtLeast(0f)
        if (!queueFollowing) {
            // Der Finger steuert den Fortschritt: der Player schrumpft unter dem Finger zum Miniplayer
            val dist = rootHeightPx * SHEET_DRAG_FRACTION
            motion.follow(1f - offsetY.coerceAtLeast(0f) / dist)
        }
    }

    fun requestClose() {
        if (closing) return
        closing = true
        // genau die Umkehrung des Öffnens: Karte schrumpft zurück in den Miniplayer
        motion.settle(0f) { onDismiss() }
    }

    // Zurück-Geste (Android 13+): der Player schrumpft schon während der Geste mit, Loslassen schließt,
    // Abbrechen federt zurück. Auf älteren Android-Versionen verhält es sich wie eine normale Zurück-Taste.
    PredictiveBackHandler { backEvents ->
        try {
            backEvents.collect { e -> motion.follow(1f - e.progress * 0.3f) }
            requestClose()
        } catch (ex: CancellationException) {
            motion.settle(1f)
        }
    }
    // Zurück-Taste schließt zuerst die Warteschlange
    androidx.activity.compose.BackHandler(enabled = queueShown) { closeQueue() }
    // Ecken werden beim Runterziehen nach und nach rund (bis 38.dp)
    val density = LocalDensity.current.density
    // Titel und Cover weichen der aufgehenden Warteschlange aus (Hochformat)
    val queueGeo = remember { QueueGeo() }
    val queueMotion = remember {
        QueueMotion(
            geo = queueGeo,
            progress = { queueProgress.value },
            anchorBottom = { queueAnchor.coords?.takeIf { it.isAttached }?.boundsInRoot()?.bottom },
            rootHeight = { rootHeightPx },
            density = { density }
        )
    }
    // Endposition des großen Covers (für die Flug-Animation) und ob geflogen wird
    var finalCover by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val hasFly = eo.coverBounds != null
    val dens = LocalDensity.current
    val sheetColor = MaterialTheme.colorScheme.surface
    // open.value wird nur in Lambdas der Zeichenphase gelesen; der Inhalt wird pro Frame nicht neu komponiert
    val flying by remember { derivedStateOf { open.value < 1f } }
    Box(
        Modifier.fillMaxSize().onSizeChanged {
            rootHeightPx = it.height.toFloat().coerceAtLeast(1f)
            rootWidthPx = it.width.toFloat().coerceAtLeast(1f)
        }
    ) {
        // Abdunkelung der Liste hinter dem aufgehenden Player
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = open.value * 0.55f }
                .background(Color.Black)
        )
        Surface(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStarted = { start ->
                        // laufende Animation abfangen: der Finger übernimmt genau an der aktuellen Stelle
                        closing = false
                        dragFromQueueZone = start.y >= queueZoneTop - queueZonePad
                        upDrag = 0f
                        val cur = open.value.coerceIn(0f, 1f)
                        motion.follow(cur)
                        offsetY = (1f - cur) * rootHeightPx * SHEET_DRAG_FRACTION
                    },
                    onDragStopped = { velocity ->
                        if (queueFollowing) {
                            // Loslassen: ab etwa einem Drittel oder bei schnellem Wischen ganz öffnen, sonst wieder schließen
                            queueFollowing = false
                            val shownFraction = -upDrag / (rootHeightPx * QUEUE_HEIGHT_FRACTION)
                            upDrag = 0f
                            // Fingergeschwindigkeit (px/s, nach oben negativ) als Fortschritt pro Sekunde an die Feder weitergeben
                            val qv = -velocity / (rootHeightPx * QUEUE_HEIGHT_FRACTION)
                            if (shownFraction > 0.3f || velocity < -900f) openQueue(qv) else closeQueue(qv)
                        } else {
                            // Fortschritt und Geschwindigkeit (pro Sekunde) aus dem Fingerweg ableiten,
                            // dann per Feder weiterlaufen: schnelles Wischen fühlt sich anders an als langsames
                            val dist = rootHeightPx * SHEET_DRAG_FRACTION
                            val p = (1f - offsetY.coerceAtLeast(0f) / dist).coerceIn(0f, 1f)
                            val v = -velocity / dist
                            offsetY = 0f
                            if (p + v * SHEET_PROJECTION_S < 0.5f) {
                                closing = true
                                motion.settle(0f, v, p) { onDismiss() }
                            } else {
                                motion.settle(1f, v, p)
                            }
                        }
                    }
                )
                .pointerInput(Unit) {}
                .graphicsLayer {
                    shape = SheetRevealShape(eo, open.value, 0f)
                    clip = true

                }
                .drawBehind {
                    val color = androidx.compose.ui.graphics.lerp(eo.color, sheetColor, open.value)
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
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                // Querformat: links das Cover, rechts Titel und Steuerung. Hochformat: wie bisher in einer Spalte.
                val landscape = maxWidth > maxHeight * 1.2f
                queueGeo.enabled = !landscape
                // Cover-Größe: Hochformat wie bisher (Breite oder 55 % der Höhe), Querformat durch die Höhe begrenzt
                val coverSize = if (landscape) minOf(maxHeight, maxWidth * 0.46f) else minOf(maxWidth, maxHeight * 0.55f)
                val columnWidth = coverSize

                val headerBlock: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { queueGeo.headerBottom = it.boundsInRoot().bottom }
                            .reveal({ open.value }, 0.30f, 0.75f, 0.dp)
                    ) {
                        // 0 = Player offen, 1 = Kopfzeilen-Symbole ganz flach (wird nur beim Zeichnen gelesen)
                        val headerFlatten: () -> Float = {
                            ((1f - open.value) / 0.25f).coerceIn(0f, 1f)
                        }
                        CloseSheetButton(
                            onClick = { requestClose() },
                            modifier = Modifier.align(Alignment.CenterStart).headerPop { open.value },
                            // Pfeil flacht beim Runterziehen zu einer Linie ab und knickt beim Öffnen wieder ein
                            flatten = headerFlatten
                        )
                        Text(
                            song.album.ifBlank { str(R.string.unknown_album) },
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
                            MoreButton(
                                onClick = { menuOpen = true },
                                // zieht sich oben und unten zusammen, genau im Takt mit dem Pfeil links
                                modifier = Modifier.padding(end = 4.dp).headerPop { open.value }.headerSquash(headerFlatten)
                            )
                            BounceMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(str(R.string.track_info)) },
                                    leadingIcon = { InfoIcon() },
                                    onClick = { menuOpen = false; showInfo = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.edit_tags)) },
                                    leadingIcon = { EditIcon() },
                                    onClick = { menuOpen = false; showTags = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.go_to_album)) },
                                    leadingIcon = { AlbumDiscIcon() },
                                    onClick = { menuOpen = false; onGoToAlbum() }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.add_to_playlist_np)) },
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
                }
                val coverBlock: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .onGloballyPositioned { finalCover = it.boundsInRoot(); queueGeo.cover = it.boundsInRoot() }
                            .graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                // beim Öffnen übernimmt das fliegende Cover; danach wird getauscht
                                alpha = if (hasFly) (if (open.value < 1f) 0f else 1f)
                                else ((open.value - 0.35f) / 0.45f).coerceIn(0f, 1f)
                            }
                            .graphicsLayer {
                                // Warteschlange öffnet: das Cover wird vom Text hochgeschoben und schrumpft nach links oben
                                val x = queueMotion.compute()
                                if (x != null) {
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                    scaleX = x.coverScale
                                    scaleY = x.coverScale
                                    translationY = x.coverDy
                                }
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
                                // Nur ein kurzes Stück gleiten (weniger als der Seitenrand von 24 dp): Das Cover bleibt
                                // ganz auf dem Bildschirm. Sonst wird es am Bildschirmrand abgeschnitten und wirkt dort eckig.
                                val slidePx = with(dens) { 18.dp.roundToPx() }
                                (slideInHorizontally(
                                    animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
                                    initialOffsetX = { dir * slidePx }
                                ) + fadeIn(tween(Motion.MEDIUM - 40))) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
                                        targetOffsetX = { -dir * slidePx }
                                    ) + fadeOut(tween(Motion.FAST))) using SizeTransform(clip = false)
                            },
                            label = "cover"
                        ) { s ->
                            Cover(
                                s, coverSize, corner = 20.dp,
                                shadow = if (CornerSettings.coverShadow) COVER_SHADOW else 0.dp
                            )
                        }
                    }
                }
                val titleBlock: @Composable () -> Unit = {
                    Column(
                        Modifier
                            .onGloballyPositioned { queueGeo.title = it.boundsInRoot() }
                            .layout { measurable, constraints ->
                                // Beim Öffnen der Warteschlange wird der Text schmaler (Platz neben dem kleinen Cover).
                                // Die gemeldete Breite bleibt gleich, damit die Spalte nicht mittig rutscht.
                                val x = queueMotion.compute()
                                val w = if (x != null) x.titleWidth.roundToInt().coerceIn(0, constraints.maxWidth) else constraints.maxWidth
                                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = w))
                                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
                            }
                            .graphicsLayer {
                                val x = queueMotion.compute()
                                if (x != null) {
                                    translationX = x.titleDx
                                    translationY = x.titleDy
                                }
                            }
                    ) {
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
                    }
                }
                val progressBlock: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().reveal({ open.value }, 0.45f, 0.92f)) {
                        val progressValue = { if (dragging) dragFraction else frac.floatValue }
                        val progressChange: (Float) -> Unit = { dragging = true; dragFraction = it }
                        val progressDone = {
                            val target = (dragFraction * duration).toLong()
                            c.seekTo(target)
                            position = target
                            frac.floatValue = dragFraction
                            dragging = false
                        }
                        if (CornerSettings.waveProgress) {
                            WaveProgress(progress = progressValue, seed = song.id, onChange = progressChange, onFinished = progressDone)
                        } else {
                            PillProgress(progress = progressValue, onChange = progressChange, onFinished = progressDone)
                        }
                        // Optional mittig zwischen den Zeiten: das Ausgabegerät (bei Kopfhörern mit Symbol)
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(fmt(shownMs), style = MaterialTheme.typography.labelMedium)
                                Text(fmt(duration), style = MaterialTheme.typography.labelMedium)
                            }
                            if (CornerSettings.showOutputDevice) {
                                // Liegt über der Zeile und zählt nicht zu ihrer Höhe: der Fortschrittsbalken bleibt an seinem
                                // Platz, ob ein Gerät verbunden ist oder nicht. Rand links und rechts: Platz für die Zeiten.
                                Box(
                                    Modifier.matchParentSize().padding(horizontal = 56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    OutputDeviceLabel()
                                }
                            }
                        }
                    }
                }
                val controlsBlock: @Composable () -> Unit = {
                    Row(
                        // Lage der Tastenreihe merken: die Warteschlange wächst als Blase direkt darunter heraus
                        Modifier.fillMaxWidth().onGloballyPositioned { queueAnchor.coords = it }.reveal({ open.value }, 0.50f, 1.0f, 32.dp),
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
                }
                val infoBlock: @Composable (Modifier) -> Unit = { extra ->
                    Box(
                        Modifier.fillMaxWidth().then(extra)
                            .onGloballyPositioned { queueZoneTop = it.boundsInRoot().top }
                            .reveal({ open.value }, 0.60f, 1.0f, 16.dp),   // Abstand unten
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

                if (!landscape) {
                    Column(
                        Modifier.fillMaxHeight().width(columnWidth),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        headerBlock()
                        Spacer(Modifier.weight(1.25f))   // Abstand oben: kleiner = Cover weiter oben
                        coverBlock()
                        Spacer(Modifier.weight(1.7f))    // Abstand Cover -> Titel
                        titleBlock()
                        Spacer(Modifier.weight(0.8f))    // Abstand Künstler -> Fortschrittsbalken
                        progressBlock()
                        Spacer(Modifier.weight(1.0f))    // Abstand Balken -> Tasten
                        controlsBlock()
                        infoBlock(Modifier.weight(1.95f))
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // links: Cover, in der Höhe zentriert
                        Box(Modifier.size(coverSize), contentAlignment = Alignment.Center) { coverBlock() }
                        Spacer(Modifier.width(32.dp))
                        // rechts: Kopfzeile oben, darunter Titel, Fortschritt und Tasten
                        Column(
                            Modifier.weight(1f).fillMaxHeight().widthIn(max = 520.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            headerBlock()
                            Spacer(Modifier.weight(1.0f))
                            titleBlock()
                            Spacer(Modifier.weight(0.7f))
                            progressBlock()
                            Spacer(Modifier.weight(0.8f))
                            controlsBlock()
                            infoBlock(Modifier.weight(1.0f))
                        }
                    }
                }
            }
        }
        // Das Cover fliegt vom Miniplayer an seinen Platz im Player
        val fb = eo.coverBounds
        val fin = finalCover
        if (fb != null && fin != null && flying) {
            val finalDp = with(dens) { fin.width.toDp() }
            val coverStyle = LocalCoverStyle.current
            val r0 = coverRadius(coverStyle, 56.dp, 12.dp).value   // Rundung im Miniplayer
            val r1 = coverRadius(coverStyle, finalDp, 20.dp).value // Rundung im Player
            val flightScale = { e: Float ->
                androidx.compose.ui.util.lerp(fb.width / fin.width.coerceAtLeast(1f), 1f, e).coerceAtLeast(0.05f)
            }
            // Ecken: von der Rundung im Mini zu der im Player, vor der Skalierung umgerechnet
            val flightShape = { e: Float, d: Float ->
                RoundedCornerShape(androidx.compose.ui.util.lerp(r0, r1, e) * d / flightScale(e))
            }
            Box(
                Modifier
                    .size(finalDp)
                    .graphicsLayer {
                        val e = coverPath(open.value.coerceIn(0f, 1f))
                        val sc = flightScale(e)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = sc
                        scaleY = sc
                        translationX = androidx.compose.ui.util.lerp(fb.left, fin.left, e)
                        translationY = androidx.compose.ui.util.lerp(fb.top, fin.top, e)
                    }
                    // Schatten (gleiche Lagen wie im Cover) wachsen mit dem Flug an, damit beim Tausch nichts springt
                    .then(
                        if (CornerSettings.coverShadow) {
                            var m: Modifier = Modifier
                            for ((factor, layerAlpha) in CoverShadowLayers) {
                                m = m.graphicsLayer {
                                    val e = coverPath(open.value.coerceIn(0f, 1f))
                                    shape = flightShape(e, density)
                                    clip = false
                                    shadowElevation = COVER_SHADOW.toPx() * factor * e.coerceIn(0f, 1f)
                                    ambientShadowColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = layerAlpha)
                                    spotShadowColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = layerAlpha)
                                }
                            }
                            m
                        } else Modifier
                    )
                    .graphicsLayer {
                        shape = flightShape(coverPath(open.value.coerceIn(0f, 1f)), density)
                        clip = true
                    }
            ) {
                Cover(song, finalDp, corner = 0.dp, fallbackSize = 56.dp)
            }
        }
        if (queueShown) {
            QueuePanel(c, library, queueProgress, queueAnchor, onRemoved = onQueueRemoved) { v -> closeQueue(v) }
        }
    }
}

/** Größe des optionalen Schattens unter dem Cover im Player */
private val COVER_SHADOW = 28.dp

/** Stärke des Überschwingers beim Cover-Flug (0 = keiner). 0.4 ≈ 3 % über das Ziel hinaus, dann zurück. */
private const val COVER_BOUNCE = 0.4f

/**
 * Weg des fliegenden Covers in Abhängigkeit vom Öffnen-Fortschritt [t] (0 = Miniplayer, 1 = Platz im Player).
 * Bei 0 und 1 identisch zum Fortschritt, dazwischen kommt eine kleine Beule dazu: Das Cover schießt kurz
 * ein wenig über sein Ziel und setzt sich dann. Weil es eine reine Funktion des Fortschritts ist, wirkt es
 * beim Öffnen per Tippen und per Hochziehen und umgekehrt beim Schließen (dort leichtes Ausholen),
 * damit Öffnen und Schließen gleich aussehen. Nie negativ, deshalb bleiben Größe und Ecken gültig.
 */
private fun coverPath(t: Float): Float = t + COVER_BOUNCE * kotlin.math.sin(kotlin.math.PI.toFloat() * t)

/** Zieht ein Element von oben und unten zusammen: bei [flatten] = 1 ist es nur noch eine dünne Linie in seiner Mitte */
private fun Modifier.headerSquash(flatten: () -> Float): Modifier = graphicsLayer {
    scaleY = 1f - 0.88f * flatten()
}

/** Kopfzeilen-Tasten wachsen beim Öffnen mit dem Fortschritt auf (und schrumpfen beim Schließen wieder), statt nur einzublenden */
private fun Modifier.headerPop(progress: () -> Float): Modifier = graphicsLayer {
    val t = ((progress() - 0.35f) / 0.4f).coerceIn(0f, 1f)
    val s = 0.55f + 0.45f * t
    scaleX = s
    scaleY = s
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
