package com.example.musikplayer

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch
import kotlin.math.max

/** Anteil der Bildschirmhöhe, den die Warteschlange einnimmt */
const val QUEUE_HEIGHT_FRACTION = 0.78f

/** Reihenfolge der Warteschlange: bereits gespielte Titel, der aktuelle und die folgenden (auch bei Zufallswiedergabe) */
private fun queueIndices(c: MediaController): List<Int> {
    val tl = c.currentTimeline
    if (tl.isEmpty) return emptyList()
    val cur = c.currentMediaItemIndex
    val shuffle = c.shuffleModeEnabled
    val before = ArrayList<Int>()
    var i = cur
    while (before.size < 5000) {
        i = tl.getPreviousWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        if (i == C.INDEX_UNSET) break
        before.add(0, i)
    }
    val after = ArrayList<Int>()
    i = cur
    while (after.size < 5000) {
        i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        if (i == C.INDEX_UNSET) break
        after.add(i)
    }
    return before + cur + after
}

/**
 * Warteschlange als Fläche, die von unten in den Player hochfährt.
 * [progress]: 0 = geschlossen, 1 = offen. Nach unten ziehen oder neben die Fläche tippen schließt sie.
 */
@Composable
fun QueuePanel(
    c: MediaController,
    library: List<Song>,
    progress: Animatable<Float, AnimationVector1D>,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(c) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { version++ }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { version++ }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { version++ }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }
    val byId = remember(library) { library.associateBy { it.id } }
    val shuffle = remember(version) { c.shuffleModeEnabled }
    val order = remember(version) { queueIndices(c) }
    val current = remember(version) { c.currentMediaItemIndex }
    // Jeder Eintrag bekommt seine Position in der Warteschlange als Schlüssel (id), so kann ein Titel auch doppelt vorkommen.
    val real = remember(version, byId) {
        order.associateWith { qi ->
            val item = c.getMediaItemAt(qi)
            val id = item.mediaId.toLongOrNull()
            byId[id] ?: Song(
                id = id ?: -1L,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                uri = item.localConfiguration?.uri ?: Uri.EMPTY,
                folder = ""
            )
        }
    }
    val display = remember(real) { order.map { qi -> real.getValue(qi).copy(id = qi.toLong()) } }
    val noRipple = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    // Erster Eintrag, der beim Öffnen zu sehen ist (die Liste springt zum aktuellen Titel)
    val firstShown = max(0, order.indexOf(current) - 1)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelH = maxHeight * QUEUE_HEIGHT_FRACTION
        val panelHpx = with(density) { panelH.toPx() }
        // Abdunkelung hinter der Fläche, Tipp darauf schließt
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value * 0.5f }
                .background(Color.Black)
                .clickable(interactionSource = noRipple, indication = null) { onClose() }
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelH)
                .graphicsLayer { translationY = (1f - progress.value) * panelHpx }
                .pointerInput(Unit) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(Modifier.fillMaxSize()) {
                // Kopf: Griff + Titel. Hier nach unten ziehen schließt die Fläche.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = rememberDraggableState { d ->
                                scope.launch { progress.snapTo((progress.value - d / panelHpx).coerceIn(0f, 1f)) }
                            },
                            orientation = Orientation.Vertical,
                            onDragStopped = { v ->
                                if (progress.value < 0.75f || v > 1000f) onClose()
                                else scope.launch { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                            }
                        )
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp, bottom = 6.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            str(R.string.queue),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            plural(R.plurals.n_tracks, order.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                // Titel 1 s gedrückt halten und ziehen ändert die Reihenfolge (wie in der Playlist).
                // Bei Zufallswiedergabe ist die Reihenfolge nicht frei änderbar.
                ReorderableSongList(
                    songs = display,
                    onReorder = { newOrder ->
                        // Neue Reihenfolge als einzelne Verschiebungen an den Player melden
                        val cur = order.toMutableList()
                        newOrder.forEachIndexed { p, s ->
                            val qi = s.id.toInt()
                            if (cur[p] != qi) {
                                val from = cur.indexOf(qi)
                                c.moveMediaItem(from, p)
                                cur.add(p, cur.removeAt(from))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                    enabled = !shuffle,
                    initialIndex = max(0, order.indexOf(current) - 1)
                ) { i, s, itemModifier ->
                    val qi = s.id.toInt()
                    val song = real[qi] ?: return@ReorderableSongList
                    val isCurrent = qi == current
                    ListItem(
                        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Cover(song, 48.dp) },
                        trailingContent = {
                            if (!isCurrent) {
                                BounceIconButton(onClick = { c.removeMediaItem(qi) }) {
                                    CloseIcon(description = str(R.string.queue_remove))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                        ),
                        modifier = itemModifier
                            // Beim Öffnen gleiten die Einträge nacheinander hoch und ein (an den Fortschritt gekoppelt:
                            // zieht man die Fläche mit dem Finger auf oder zu, laufen sie mit)
                            .graphicsLayer {
                                val rel = (i - firstShown).coerceIn(0, 7)
                                val t = ((progress.value - 0.2f - 0.045f * rel) / 0.5f).coerceIn(0f, 1f)
                                alpha = t
                                translationY = (1f - t) * 28.dp.toPx()
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                SkipDirection.dir = if (qi >= current) 1 else -1
                                c.seekToDefaultPosition(qi)
                                c.play()
                            }
                    )
                }
            }
        }
    }
}
