package com.example.musikplayer

import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun clearColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Playlist-Cover: 2x2-Raster aus den Covern der ersten 4 Titel. */
@Composable
fun PlaylistCover(songs: List<Song>, size: Dp, corner: Dp = 12.dp, custom: String? = null) {
    val first = songs.take(4)
    val own by rememberPlaylistCoverImage(custom)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(coverRadius(LocalCoverStyle.current, size, corner)))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            // eigenes Bild gewählt: füllt das ganze Cover
            own != null -> Image(
                bitmap = own!!, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            first.isEmpty() -> MusicNoteIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            first.size == 1 -> Cover(first[0], size, corner = 0.dp)
            else -> {
                val half = size / 2
                Column {
                    for (row in 0..1) {
                        Row {
                            for (col in 0..1) {
                                val s = first.getOrNull(row * 2 + col)
                                if (s != null) {
                                    Cover(s, half, corner = 0.dp)
                                } else {
                                    Box(Modifier.size(half), contentAlignment = Alignment.Center) {
                                        MusicNoteIcon(
                                            iconSize = half * 0.5f,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bewegung für Playlists in Liste und Raster: Eine neue Playlist poppt mit Überschwinger auf,
 * eine gelöschte schrumpft leicht und blendet aus. [appear] wird nur beim ersten Anzeigen gelesen.
 */
@Composable
internal fun Modifier.playlistFx(appear: Boolean, removing: Boolean): Modifier {
    val pop = remember { Animatable(if (appear) 0.6f else 1f) }
    LaunchedEffect(Unit) {
        if (appear) pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))
    }
    val exit by animateFloatAsState(
        targetValue = if (removing) 0f else 1f,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
        label = "playlistExit"
    )
    return this.graphicsLayer {
        val sc = pop.value * (0.8f + 0.2f * exit)
        scaleX = sc
        scaleY = sc
        alpha = (((pop.value - 0.6f) / 0.4f) * 2f).coerceIn(0f, 1f) * exit
    }
}

/**
 * Titelzahl und Gesamtlaufzeit, z. B. "12 Titel • 45 min" oder "12 Titel • 1 h 5 min"
 * (unter 60 Minuten nur Minuten, ab 60 Minuten Stunden und Minuten). Ohne bekannte Laufzeit nur die Titelzahl.
 * Gleich bei Alben (Tab und Suche) und Playlists.
 */
internal fun trackMeta(count: Int, totalMs: Long): String {
    val tracks = plural(R.plurals.n_tracks, count)
    if (totalMs <= 0L) return tracks
    val minutes = maxOf(1, Math.round(totalMs / 60000.0).toInt())
    val duration = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    return "$tracks • $duration"
}

internal fun trackMetaOf(songs: List<Song>): String = trackMeta(songs.size, songs.sumOf { it.durationMs })

/** Playlist: Titelzahl ist die Zahl der Einträge, die Laufzeit zählt nur Titel, die noch in der Bibliothek sind */
internal fun playlistMeta(songIds: List<Long>, byId: Map<Long, Song>): String =
    trackMeta(songIds.size, songIds.sumOf { byId[it]?.durationMs ?: 0L })

/** Kachel für die Rasteransicht: Cover in voller Kachelbreite, darunter Titel und Untertitel. */
@Composable
internal fun GridTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    detail: String? = null,
    menu: (@Composable () -> Unit)? = null,   // z. B. das Drei-Punkte-Menü einer Playlist
    modifier: Modifier = Modifier,
    cover: @Composable (Dp) -> Unit
) {
    BoxWithConstraints(
        // Kein Clip und kein Ripple: Der Clip schnitt unten die Ecken von Titel und Untertitel ab,
        // und der Ripple färbte die Kachel beim Drücken weiß. Stattdessen drückt sich die Kachel leicht ein.
        modifier.fillMaxWidth()
            .bounceClick(pressedScale = 0.95f, hapticSnap = true) { onClick() }
    ) {
        val w = maxWidth
        Column(Modifier.fillMaxWidth()) {
            cover(w)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                menu?.invoke()
            }
        }
    }
}

/* ---------- Suche: Albenzeile, Karten, Überschriften ---------- */

/** Albumkarte der Suche: Cover mit Jahr oben rechts und Abspielen-Taste unten rechts, darunter Titel, Künstler, Titelzahl und Laufzeit */
@Composable
internal fun SearchAlbumCard(
    songs: List<Song>,
    name: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = songs.first()
    val year = remember(songs) { songs.map { it.year }.filter { it > 0 }.minOrNull() ?: 0 }
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val side = 128.dp
    Column(
        modifier
            .width(side)
            // Eindrücken zuerst: Es gilt dann für die ganze Karte samt Hintergrund und Rundung, nicht nur für den Inhalt
            .bounceClick(pressedScale = 0.95f, hapticSnap = true) { onOpen() }
            .clip(RoundedCornerShape(20.dp))
            .background(card)
    ) {
        Box(Modifier.size(side)) {
            Cover(first, side, corner = 0.dp)
            if (year > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(bottomStart = 12.dp))
                        .background(card)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .bounceClick(pressedScale = 0.88f) { onPlay() }
                    .clip(RoundedCornerShape(12.dp))
                    .background(card.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                PlayTriangleIcon(
                    iconSize = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    description = str(R.string.play_album)
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                first.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                trackMetaOf(songs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Überschrift eines Suchabschnitts: Symbol, Titel mit Anzahl und optional die Taste "Alle anzeigen" */
@Composable
internal fun SearchSectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    onShowAll: (() -> Unit)? = null,
    topPadding: Dp = 8.dp
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onShowAll != null) {
            Row(
                Modifier
                    .bounceClick(pressedScale = 0.95f) { onShowAll() }
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GridIcon(iconSize = 18.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    str(R.string.search_show_all),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

/** Alle Alben einer Liste in der Darstellung der Albenansicht (Liste oder Raster mit eingestellter Spaltenzahl) */
@Composable
internal fun AlbumCollection(
    albums: List<Pair<String, List<Song>>>,
    style: ViewStyle,
    bottomSpace: Dp,
    modifier: Modifier = Modifier,
    // Scroll-Zustände kommen von außen, damit die Position beim Öffnen eines Albums und Zurückgehen erhalten bleibt
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    onOpen: (String) -> Unit
) {
    if (style.grid) {
        HapticLazyGrid(
            columns = GridCells.Fixed(style.cols),
            modifier = modifier,
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomSpace),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            gridItems(albums, key = { it.first }) { (albumName, list) ->
                GridTile(
                    title = albumName,
                    subtitle = list.first().artist,
                    detail = trackMetaOf(list),
                    onClick = { onOpen(albumName) },
                    cover = { w -> Cover(list.first(), w) }
                )
            }
        }
    } else {
        HapticLazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomSpace), state = listState) {
            items(albums, key = { it.first }) { (albumName, list) ->
                ListItem(
                    headlineContent = { Text(albumName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(list.first().artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                trackMetaOf(list),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    leadingContent = { Cover(list.first(), 56.dp) },
                    colors = clearColors(),
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, hapticSnap = true) { onOpen(albumName) }
                )
            }
        }
    }
}

@Composable
fun DetailHeader(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Ohne Pfeil (onBack = null) rückt der Titel an den linken Rand, zurück geht es per Zurück-Geste
        if (onBack != null) {
            BounceIconButton(onClick = onBack) {
                BackIcon(description = str(R.string.back))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 8.dp else 0.dp)
        )
        // optionale Taste rechts auf Höhe des Titels
        action?.invoke()
    }
}

/** Tracknummer für die Anzeige: Das Tag kann die CD-Nummer enthalten (CD 2, Titel 3 = 2003), die wird abgetrennt. Ohne Tag zählt die Position. */
internal fun trackNumberOf(song: Song, position: Int): Int {
    val n = song.track % 1000
    return if (n > 0) n else position
}

/**
 * Drei-Punkte-Menü einer Playlist: oben Bild, Name und eine Taste zum Ändern des Bildes,
 * darunter Umbenennen, Alle abspielen, Mischen, Als M3U exportieren und Löschen.
 */
@Composable
internal fun PlaylistMenu(
    playlist: Playlist,
    covers: List<Song>,
    compact: Boolean,
    onPickCover: () -> Unit,
    onRename: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BounceIconButton(
            onClick = { expanded = true },
            modifier = if (compact) Modifier.size(32.dp) else Modifier
        ) {
            MoreVertIcon(iconSize = if (compact) 20.dp else 24.dp, description = str(R.string.more))
        }
        BounceMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(
                Modifier.width(280.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaylistCover(covers, 56.dp, custom = playlist.cover)
                Spacer(Modifier.width(14.dp))
                // Name, darunter "xx Titel • Monat Jahr" (Erstellungsdatum steckt in der Playlist-ID)
                val subtitle = remember(playlist.id, playlist.songIds.size) {
                    val tracks = plural(R.plurals.n_tracks, playlist.songIds.size)
                    val created = playlist.id.toLongOrNull()?.takeIf { it > 946684800000L }
                    if (created == null) tracks else {
                        val locale = strCtx?.resources?.configuration?.locales?.get(0) ?: java.util.Locale.getDefault()
                        val month = java.text.SimpleDateFormat("LLL yyyy", locale).format(java.util.Date(created))
                        "$tracks • $month"
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                BounceIconButton(onClick = { expanded = false; onPickCover() }) {
                    ImageEditIcon(description = str(R.string.playlist_change_image))
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            DropdownMenuItem(
                text = { Text(str(R.string.playlist_rename)) },
                leadingIcon = { EditIcon() },
                onClick = { expanded = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(str(R.string.playlist_play_all)) },
                leadingIcon = { PlayTriangleIcon() },
                onClick = { expanded = false; onPlay() }
            )
            DropdownMenuItem(
                text = { Text(str(R.string.playlist_shuffle)) },
                leadingIcon = { ShuffleIcon(iconSize = 24.dp) },
                onClick = { expanded = false; onShuffle() }
            )
            DropdownMenuItem(
                text = { Text(str(R.string.playlist_export_m3u)) },
                leadingIcon = { BackupIcon() },
                onClick = { expanded = false; onExport() }
            )
            DropdownMenuItem(
                text = { Text(str(R.string.delete)) },
                leadingIcon = { TrashIcon() },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}
