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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** So lange muss ein Titel gedrückt gehalten werden, bis er verschoben werden kann. */
const val REORDER_HOLD_MS = 1500L

/**
 * Liste mit Titeln, die sich per langem Drücken (1,5 s) und Ziehen umsortieren lässt.
 * Normales Tippen und Scrollen bleiben unverändert. Nach dem Loslassen wird die neue Reihenfolge über [onReorder] gemeldet.
 * [row] zeichnet eine Zeile und muss den übergebenen Modifier an die Zeile weitergeben.
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
    var order by remember(songs) { mutableStateOf(songs) }
    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnReorder by rememberUpdatedState(onReorder)

    /** Tauscht den gezogenen Titel mit dem Nachbarn, über dem er gerade liegt. */
    fun checkSwap() {
        val key = dragKey ?: return
        if (settling) return
        val infos = listState.layoutInfo.visibleItemsInfo
        val dragged = infos.firstOrNull { it.key == key } ?: return
        val from = order.indexOfFirst { it.id == key }
        // Layout noch nicht aktuell (gerade erst getauscht): erst beim nächsten Durchlauf prüfen
        if (from < 0 || dragged.index != from) return
        val center = dragged.offset + dragged.size / 2f + dragOffset
        val target = infos.firstOrNull {
            it.key != key && center >= it.offset && center <= it.offset + it.size
        } ?: return
        val to = order.indexOfFirst { it.id == target.key }
        if (to < 0 || to == from) return
        // Der Finger bleibt an derselben Stelle, auch wenn die Zeile ihren Platz in der Liste wechselt
        val newOffset = if (to > from) target.offset + target.size - dragged.size else target.offset
        dragOffset += dragged.offset - newOffset
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun startDrag(key: Long) {
        if (dragKey != null) return
        settling = false
        dragOffset = 0f
        dragKey = key
    }

    fun endDrag() {
        if (dragKey == null || settling) return
        settling = true
        val result = order
        scope.launch {
            // Die Zeile gleitet sanft an ihren neuen Platz
            animate(dragOffset, 0f, animationSpec = tween(150)) { v, _ -> dragOffset = v }
            dragKey = null
            settling = false
        }
        if (result.map { it.id } != songs.map { it.id }) currentOnReorder(result)
    }

    // Am oberen/unteren Rand scrollt die Liste von selbst weiter, solange man dort zieht
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        while (isActive) {
            val key = dragKey ?: break
            val info = listState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == key }
            if (dragged != null && !settling) {
                val top = dragged.offset + dragOffset
                val bottom = top + dragged.size
                val edge = 140f
                val delta = when {
                    top < info.viewportStartOffset + edge ->
                        -((info.viewportStartOffset + edge - top).coerceAtMost(edge) / edge) * 24f
                    bottom > info.viewportEndOffset - edge ->
                        ((bottom - (info.viewportEndOffset - edge)).coerceAtMost(edge) / edge) * 24f
                    else -> 0f
                }
                if (delta != 0f) dragOffset += listState.scrollBy(delta)
                checkSwap()
            }
            delay(16)
        }
    }

    LazyColumn(modifier, state = listState, contentPadding = contentPadding) {
        itemsIndexed(order, key = { _, s -> s.id }) { i, s ->
            val dragging = dragKey == s.id
            val itemModifier = Modifier
                .pointerInput(s.id) {
                    awaitEachGesture {
                        // Nur beobachten (Initial): Tippen und Scrollen funktionieren bis zum langen Drücken normal
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val slop = viewConfiguration.touchSlop
                        var cancelled = false
                        val timedOut = withTimeoutOrNull(REORDER_HOLD_MS) {
                            while (!cancelled) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) {
                                    cancelled = true
                                }
                            }
                        }
                        // Vorher losgelassen oder weggewischt: normales Verhalten
                        if (timedOut != null) return@awaitEachGesture

                        startDrag(s.id)
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                // Ab jetzt gehört die Geste der Sortierung: kein Klick, kein Scrollen
                                val dy = c.position.y - c.previousPosition.y
                                c.consume()
                                if (!c.pressed) break
                                dragOffset += dy
                                checkSwap()
                            }
                        } finally {
                            endDrag()
                        }
                    }
                }
                .then(
                    if (dragging) Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = dragOffset
                            shadowElevation = 12.dp.toPx()
                            shape = RoundedCornerShape(16.dp)
                            clip = false
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    else Modifier.animateItem()
                )
            row(i, s, itemModifier)
        }
    }
}
