package com.example.musikplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Kennung des Start-Tabs. Die übrigen Tabs behalten ihre Nummern (0 Titel, 1 Alben, 2 Playlists, 3 Favoriten). */
const val HOME_TAB = -1

/** So viele Alben und Titel zeigt die Startseite höchstens. */
private const val HOME_ALBUMS = 12
private const val HOME_TRACKS = 20

/**
 * Startseite: Dein Mix, zuletzt hinzugefügte Alben, zuletzt gehörte Titel.
 * Alles entsteht aus der Bibliothek und dem Verlauf; ohne Verlauf bleibt der Titel-Abschnitt mit einem Hinweis leer.
 */
@Composable
fun HomeScreen(
    songs: List<Song>,
    mix: List<Song>,
    history: List<Long>,
    currentId: String?,
    favSet: Set<Long>,
    state: LazyListState,
    albumsRowState: LazyListState,   // Scrollstand der Albenreihe, bleibt beim Öffnen eines Albums erhalten
    bottomSpace: Dp,
    onPlay: (List<Song>, Int) -> Unit,
    onPlayMix: (List<Song>) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onReshuffle: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val unknown = str(R.string.unknown_album)
    // Alben nach dem jüngsten Titel: so taucht ein neu hinzugefügtes Album ganz vorn auf
    val recentAlbums = remember(songs, unknown) {
        songs.groupBy { it.album.ifBlank { unknown } }
            .toList()
            .sortedByDescending { (_, list) -> list.maxOf { it.dateAdded } }
            .take(HOME_ALBUMS)
    }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val recentTracks = remember(history, byId) { history.mapNotNull { byId[it] }.take(HOME_TRACKS) }

    // Bewusst ohne Item-Schlüssel: Beim Start laden Bibliothek und Verlauf nach. Mit Schlüsseln hält die Liste den
    // gerade sichtbaren Abschnitt fest, und Mix und Alben, die oberhalb dazukommen, schieben die Ansicht nach unten.
    HapticLazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomSpace), state = state) {
        if (mix.isNotEmpty()) {
            item {
                SearchSectionHeader(
                    icon = { SparkleIcon(iconSize = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = str(R.string.home_mix_title)
                )
            }
            item {
                MixCard(mix = mix, onPlay = { onPlayMix(mix) }, onReshuffle = onReshuffle)
            }
        }
        if (recentAlbums.isNotEmpty()) {
            item {
                SearchSectionHeader(
                    icon = { AlbumDiscIcon(iconSize = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = str(R.string.home_recent_albums),
                    topPadding = 24.dp
                )
            }
            item {
                LazyRow(
                    state = albumsRowState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentAlbums, key = { it.first }) { (name, list) ->
                        SearchAlbumCard(
                            songs = list,
                            name = name,
                            onOpen = { onOpenAlbum(name) },
                            onPlay = {
                                val ordered = list.sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase() }))
                                onPlay(ordered, 0)
                            }
                        )
                    }
                }
            }
        }
        item {
            SearchSectionHeader(
                icon = { MusicNoteIcon(iconSize = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = str(R.string.home_recent_tracks),
                topPadding = 24.dp
            )
        }
        if (recentTracks.isEmpty()) {
            item {
                Text(
                    str(R.string.home_recent_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            itemsIndexed(recentTracks) { i, s ->
                SongRow(
                    s, s.id.toString() == currentId, { onPlay(recentTracks, i) }, { onAddToPlaylist(s) },
                    isFavorite = s.id in favSet,
                    onToggleFavorite = { onToggleFavorite(s) }
                )
            }
        }
    }
}

/** Karte der intelligenten Playlist: Cover-Raster, Titelzahl, Neu mischen und Abspielen. */
@Composable
private fun MixCard(mix: List<Song>, onPlay: () -> Unit, onReshuffle: () -> Unit) {
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.97f, haptic = false) { onPlay() }
            .clip(RoundedCornerShape(24.dp))
            .background(card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistCover(mix, 96.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                str(R.string.home_mix_subtitle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                trackMetaOf(mix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        // Würfel statt Pfeil: neu mischen ist ein Wurf, er rollt beim Drücken ein Stück weiter
        BounceIconButton(onClick = onReshuffle, rotate = 90f) {
            DiceIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant, description = str(R.string.home_mix_reshuffle))
        }
    }
}
