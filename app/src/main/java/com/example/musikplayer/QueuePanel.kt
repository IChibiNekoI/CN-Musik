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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
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

/**
 * Merkt sich die Tastenreihe des Players. Die Warteschlange wächst aus einer kleinen Blase direkt darunter heraus.
 * Die Lage wird erst beim Zeichnen abgefragt (kein Zustand, also keine Neu-Komposition bei jeder Bewegung).
 */
class QueueAnchor {
    var coords: LayoutCoordinates? = null
}

/**
 * Form der Warteschlange: eine Blase, die aus der kleinen Startform [origin] (Lage im Panel) zur ganzen Fläche wächst.
 * [p] = 0 ist die Blase, 1 die volle Fläche. Seitlich geht es am Anfang schneller, die Ecken wandern von der Pille
 * (ganz rund) zu den oberen Ecken der Fläche; unten läuft sie gerade aus.
 */
private class BubbleShape(private val p: Float, private val origin: Rect, private val topRadius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = p.coerceIn(0f, 1f)
        val wide = 1f - (1f - t) * (1f - t)
        val left = lerp(origin.left, 0f, wide)
        val right = lerp(origin.right, size.width, wide)
        val top = lerp(origin.top, 0f, t)
        val bottom = lerp(origin.bottom, size.height, t)
        val start = origin.height / 2f
        // Weiche Blase statt Kasten: Zwischen Pille und voller Fläche wölbt sich der Eckenradius zusätzlich auf (am stärksten
        // in der Mitte der Bewegung) und fällt zum Ende hin auf den Radius der fertigen Fläche zurück.
        val hump = with(density) { 22.dp.toPx() } * 4f * t * (1f - t)
        val base = lerp(start, topRadius, t) + hump
        // Die unteren Ecken bleiben lange rund und laufen erst im letzten Stück gerade aus (die Fläche endet am Bildschirmrand)
        val flatT = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val flat = flatT * flatT * (3f - 2f * flatT)
        val rt = CornerRadius(base)
        val rb = CornerRadius(base * (1f - flat))
        return Outline.Rounded(RoundRect(left, top, right, bottom, rt, rt, rb, rb))
    }
}

/** Ein Eintrag der Warteschlange: stabiler Schlüssel (bleibt beim Verschieben gleich), aktuelle Position im Player und der Titel */
private data class QueueEntry(val key: Long, val queueIndex: Int, val song: Song)

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
 * [onClose] bekommt die Geschwindigkeit des Fingers beim Loslassen (Fortschritt pro Sekunde, negativ = Richtung zu),
 * damit die Feder sie übernimmt: schnelles Wischen fliegt zu, langsames sinkt ab.
 * Die Fläche wächst wie eine Blase aus einer kleinen Pille direkt unter der Tastenreihe ([anchor]).
 */
@Composable
fun QueuePanel(
    c: MediaController,
    library: List<Song>,
    progress: Animatable<Float, AnimationVector1D>,
    anchor: QueueAnchor,
    onRemoved: (Song) -> Unit = {},   // Titel wurde per X aus der Warteschlange entfernt
    onClose: (velocity: Float) -> Unit
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
    // Jeder Eintrag bekommt einen stabilen Schlüssel: Titel-ID plus Nummer des Vorkommens (ein Titel kann mehrfach
    // in der Warteschlange stehen). Die Position darf NICHT als Schlüssel dienen: sie ändert sich bei jedem Verschieben,
    // dann verliert die Liste die Zuordnung der Zeilen (Sprünge, kein sanftes Verschieben wie in der Playlist).
    val entries = remember(version, byId) {
        val seen = HashMap<Long, Int>()
        order.map { qi ->
            val item = c.getMediaItemAt(qi)
            val id = item.mediaId.toLongOrNull() ?: -1L
            val occurrence = seen.getOrDefault(id, 0)
            seen[id] = occurrence + 1
            val song = byId[id] ?: Song(
                id = id,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                uri = item.localConfiguration?.uri ?: Uri.EMPTY,
                folder = ""
            )
            QueueEntry(key = id * 100_000L + occurrence, queueIndex = qi, song = song)
        }
    }
    val display = remember(entries) { entries.map { it.song.copy(id = it.key) } }
    val entryByKey = remember(entries) { entries.associateBy { it.key } }
    val noRipple = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    // Erster Eintrag, der beim Öffnen zu sehen ist (die Liste springt zum aktuellen Titel)
    val firstShown = max(0, order.indexOf(current) - 1)

    // Eigene Lage im Fenster, um die Tastenreihe (Fensterkoordinaten) in dieses Feld umzurechnen
    val self = remember { QueueAnchor() }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { self.coords = it }) {
        val panelH = maxHeight * QUEUE_HEIGHT_FRACTION
        val panelHpx = with(density) { panelH.toPx() }
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        // Abdunkelung hinter der Fläche, Tipp darauf schließt
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value * 0.5f }
                .background(Color.Black)
                .clickable(interactionSource = noRipple, indication = null) { onClose(0f) }
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelH)
                .graphicsLayer {
                    // Startform: kleine Pille mittig direkt unter der Tastenreihe, im Feld des Panels gerechnet
                    val pillW = 136.dp.toPx()
                    val pillH = 26.dp.toPx()
                    val a = anchor.coords?.takeIf { it.isAttached }?.boundsInRoot()
                    val me = self.coords?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                    val cx = if (a != null) a.center.x - me.x else boxW / 2f
                    val topInBox = if (a != null) a.bottom - me.y + 6.dp.toPx() else boxH - 120.dp.toPx()
                    val top = (topInBox - (boxH - panelHpx)).coerceAtMost(panelHpx - pillH)
                    val origin = Rect(cx - pillW / 2f, top, cx + pillW / 2f, top + pillH)
                    shape = BubbleShape(progress.value, origin, 28.dp.toPx())
                    clip = true
                    // Von der Pille bis etwa zur Größe eines Kastens (ca. 40 % der Bewegung) von ganz durchsichtig zu deckend
                    alpha = (progress.value / 0.4f).coerceIn(0f, 1f)
                }
                .pointerInput(Unit) {},
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            // Der Inhalt blendet erst ein, wenn die Blase schon ein Stück gewachsen ist
            Column(
                Modifier.fillMaxSize().graphicsLayer { alpha = ((progress.value - 0.3f) / 0.5f).coerceIn(0f, 1f) }
            ) {
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
                                // Finger-Geschwindigkeit (px/s, nach unten positiv) in Fortschritt pro Sekunde umrechnen
                                val pv = -v / panelHpx
                                if (progress.value < 0.75f || v > 1000f) onClose(pv)
                                else scope.launch {
                                    progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f), pv)
                                }
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
                        // Ein Ziehen verschiebt genau einen Titel: diesen finden und als eine einzige Verschiebung melden
                        val oldKeys = entries.map { it.key }
                        val newKeys = newOrder.map { it.id }
                        if (oldKeys.size == newKeys.size) {
                            val i = oldKeys.indices.firstOrNull { oldKeys[it] != newKeys[it] }
                            if (i != null) {
                                val fwd = newKeys.indexOf(oldKeys[i])    // Titel von i nach hinten verschoben
                                val bwd = oldKeys.indexOf(newKeys[i])    // Titel von bwd nach i nach vorn verschoben
                                if (fwd >= 0 && oldKeys.toMutableList().apply { add(fwd, removeAt(i)) } == newKeys) {
                                    c.moveMediaItem(entries[i].queueIndex, entries[fwd].queueIndex)
                                } else if (bwd >= 0 && oldKeys.toMutableList().apply { add(i, removeAt(bwd)) } == newKeys) {
                                    c.moveMediaItem(entries[bwd].queueIndex, entries[i].queueIndex)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                    enabled = !shuffle,
                    initialIndex = max(0, order.indexOf(current) - 1)
                ) { i, s, itemModifier ->
                    val entry = entryByKey[s.id] ?: return@ReorderableSongList
                    val qi = entry.queueIndex
                    val song = entry.song
                    val isCurrent = qi == current
                    ListItem(
                        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Cover(song, 48.dp) },
                        trailingContent = {
                            if (!isCurrent) {
                                BounceIconButton(onClick = { onRemoved(song); c.removeMediaItem(qi) }) {
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
