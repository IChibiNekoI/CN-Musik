package com.example.musikplayer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** So lange muss ein Titel gedrückt gehalten werden, bis er verschoben werden kann. */
const val REORDER_HOLD_MS = 1000L

/**
 * Liste mit Titeln, die sich per langem Drücken (siehe REORDER_HOLD_MS) und Ziehen umsortieren lässt.
 * Normales Tippen und Scrollen bleiben unverändert. Nach dem Loslassen wird die neue Reihenfolge über [onReorder] gemeldet.
 * [row] zeichnet eine Zeile und muss den übergebenen Modifier an die Zeile weitergeben.
 *
 * Funktionsweise: Die Geste wird einmal für die ganze Liste ausgewertet. Während des Ziehens zählt nur die
 * absolute Fingerposition in der Liste. Die Zeile wird immer genau so weit verschoben, dass sie am Finger hängt,
 * egal ob die Liste gescrollt wurde oder sich die Reihenfolge geändert hat. Dadurch kann sie nicht wegdriften.
 */
@Composable
fun ReorderableSongList(
    songs: List<Song>,
    onReorder: (List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    row: @Composable (index: Int, song: Song, itemModifier: Modifier) -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var order by remember(songs) { mutableStateOf(songs) }
    var dragKey by remember { mutableStateOf<Long?>(null) }
    // Fingerposition in der Liste (Pixel von der Oberkante der Liste) und Abstand des Fingers zur Oberkante der Zeile
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grabY by remember { mutableFloatStateOf(0f) }
    // 1 = Zeile hängt am Finger, 0 = Zeile sitzt in ihrem Platz (beim Loslassen gleitet der Wert auf 0)
    var follow by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentSongs by rememberUpdatedState(songs)

    fun itemInfo(key: Long) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** Setzt den gezogenen Titel um, sobald seine Mitte die Mitte eines Nachbarn überschritten hat. */
    fun checkSwap() {
        val key = dragKey ?: return
        if (settleJob?.isActive == true) return
        val infos = listState.layoutInfo.visibleItemsInfo
        val dragged = infos.firstOrNull { it.key == key } ?: return
        val from = order.indexOfFirst { it.id == key }
        // Layout noch nicht aktuell (gerade erst umgesetzt): beim nächsten Durchlauf prüfen
        if (from < 0 || dragged.index != from) return
        val center = fingerY - grabY + dragged.size / 2f
        val below = infos.lastOrNull { it.index > from && center > it.offset + it.size / 2f }
        val above = infos.firstOrNull { it.index < from && center < it.offset + it.size / 2f }
        val to = (below ?: above)?.index ?: return
        if (to == from || to !in order.indices) return
        // LazyColumn hält die Scrollposition am ersten sichtbaren Eintrag fest (über dessen Schlüssel).
        // Wird dieser Eintrag verschoben, würde die Liste ihm hinterherspringen und der Titel bei jedem
        // Tausch eine Zeile weiter rutschen (Sprung nach ganz unten). Darum Position festhalten.
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        if (firstIndex in minOf(from, to)..maxOf(from, to)) {
            listState.requestScrollToItem(firstIndex, firstOffset)
        }
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun startDrag(key: Long, y: Float): Boolean {
        if (dragKey != null && settleJob?.isActive != true) return false
        settleJob?.cancel()
        val info = itemInfo(key) ?: return false
        fingerY = y
        grabY = y - info.offset
        follow = 1f
        dragKey = key
        return true
    }

    fun endDrag() {
        if (dragKey == null || settleJob?.isActive == true) return
        val result = order
        settleJob = scope.launch {
            // Die Zeile gleitet sanft an ihren Platz
            animate(follow, 0f, animationSpec = tween(160)) { v, _ -> follow = v }
            dragKey = null
        }
        if (result.map { it.id } != currentSongs.map { it.id }) currentOnReorder(result)
    }

    // Am oberen/unteren Rand scrollt die Liste weiter, solange der Finger dort liegt.
    // Die Geschwindigkeit hängt vom Abstand zum Rand ab und steigt, je länger der Finger dort bleibt.
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val zone = with(density) { 96.dp.toPx() }
        val maxSpeed = with(density) { 2200.dp.toPx() }   // Pixel pro Sekunde bei vollem Tempo
        val maxStep = with(density) { 56.dp.toPx() }      // nie mehr als eine Zeile pro Bild
        var edgeSince = 0L
        var lastNanos = withFrameNanos { it }
        while (isActive) {
            if (dragKey == null) break
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = now
            if (settleJob?.isActive != true) {
                val info = listState.layoutInfo
                val top = info.beforeContentPadding.toFloat()
                // Unten zählt nur der sichtbare Bereich über dem Miniplayer
                val bottom = (info.viewportSize.height - info.afterContentPadding).toFloat()
                val intensity = when {
                    fingerY < top + zone -> -((top + zone - fingerY) / zone).coerceIn(0f, 1f)
                    fingerY > bottom - zone -> ((fingerY - (bottom - zone)) / zone).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (intensity != 0f) {
                    if (edgeSince == 0L) edgeSince = now
                    val held = (now - edgeSince) / 1_000_000_000f
                    val ramp = (0.3f + 0.7f * held / 1.0f).coerceAtMost(1f)   // nach ca. 1 s volles Tempo
                    val step = (intensity * maxSpeed * ramp * dt).coerceIn(-maxStep, maxStep)
                    if (step != 0f) listState.scrollBy(step)
                } else {
                    edgeSince = 0L
                }
                checkSwap()
            }
        }
    }

    LazyColumn(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                // Bis zum langen Drücken wird nur beobachtet (Initial, nichts verbraucht): Tippen und Scrollen laufen normal
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val startItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { down.position.y >= it.offset && down.position.y < it.offset + it.size }
                    ?: return@awaitEachGesture
                val key = startItem.key as? Long ?: return@awaitEachGesture
                var last = down.position
                var cancelled = false
                val timedOut = withTimeoutOrNull(REORDER_HOLD_MS) {
                    while (!cancelled) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) {
                            cancelled = true
                        } else {
                            last = c.position
                        }
                    }
                }
                // Vorher losgelassen oder weggewischt (Scrollen): normales Verhalten
                if (timedOut != null) return@awaitEachGesture
                if (!startDrag(key, last.y)) return@awaitEachGesture

                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        // Ab jetzt gehört die Geste der Sortierung: kein Klick, kein Scrollen
                        c.consume()
                        if (!c.pressed) break
                        fingerY = c.position.y
                        checkSwap()
                    }
                } finally {
                    endDrag()
                }
            }
        },
        state = listState,
        contentPadding = contentPadding
    ) {
        itemsIndexed(order, key = { _, s -> s.id }) { i, s ->
            val itemModifier = if (dragKey == s.id) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        // Zeile genau so weit verschieben, dass sie am Finger hängt
                        val layoutOffset = itemInfo(s.id)?.offset ?: 0
                        translationY = (fingerY - grabY - layoutOffset) * follow
                        shadowElevation = 12.dp.toPx() * follow
                        shape = RoundedCornerShape(16.dp)
                        clip = false
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            } else {
                Modifier.animateItem()
            }
            row(i, s, itemModifier)
        }
    }
}
