package com.example.musikplayer

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kennung des Start-Tabs. Die übrigen Tabs behalten ihre Nummern (0 Titel, 1 Alben, 2 Playlists, 3 Favoriten). */
const val HOME_TAB = -1

/** So viele Alben und Titel zeigt die Startseite höchstens. */
private const val HOME_ALBUMS = 12
private const val HOME_TRACKS = 10

/**
 * Startseite: Dein Mix, zuletzt hinzugefügte Alben, zuletzt gehörte Titel und ganz unten "Meistens gespielt".
 * Alles entsteht aus der Bibliothek und dem Verlauf; ohne Verlauf bleibt der Titel-Abschnitt mit einem Hinweis leer.
 */
@Composable
fun HomeScreen(
    songs: List<Song>,
    loaded: Boolean,   // erst wenn die Bibliothek einmal geladen ist, erscheinen Inhalte und Leer-Hinweise (nicht schon beim Start)
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
    onOpenMostPlayed: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    // Alben nach dem jüngsten Titel: so taucht ein neu hinzugefügtes Album ganz vorn auf
    val recentAlbums = remember(songs) {
        groupAlbums(songs)
            .sortedByDescending { (_, list) -> list.maxOf { it.dateAdded } }
            .take(HOME_ALBUMS)
    }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val recentTracks = remember(history, byId) { history.mapNotNull { byId[it] }.take(HOME_TRACKS) }

    // Meistgehörte Titel der letzten 30 Tage für die Karte unten. Der zuletzt berechnete Stand liegt im Zwischenspeicher,
    // damit die Karte beim Start sofort steht; im Hintergrund wird er dann neu gelesen. Null: noch nie berechnet.
    val ctx = LocalContext.current
    val initialTop = remember { readTopCache(ctx) }
    val topInfo by produceState<TopInfo?>(initialTop, history.firstOrNull(), songs.isEmpty(), PlayHistory.version) {
        if (songs.isEmpty()) return@produceState   // Bibliothek lädt noch: der Zwischenspeicher gilt weiter
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000L
            val present = PlayHistory.ranking(ctx, now - 30L * 86_400L, now + 1L).filter { byId.containsKey(it.id) }
            TopInfo(present.take(TOP_CACHE_SIZE), present.size).also { saveTopCache(ctx, it) }
        }
    }
    // Der laufende Titel zählt schon ab dem Start mit (vorläufig, bis er nach 3 Sekunden gespeichert wird)
    val pending = PlayHistory.pending
    val topSongs = remember(topInfo, pending, byId) {
        val info = topInfo
        if (info == null) emptyList()
        else PlayHistory.withPending(info.top, pending, System.currentTimeMillis() / 1000L).mapNotNull { byId[it.id] }
    }

    val topShown = topInfo != null          // erst zeigen, wenn die Rangliste einmal berechnet ist
    val topTotal = topInfo?.total ?: 0

    // Bewusst ohne Item-Schlüssel: Beim Start laden Bibliothek und Verlauf nach. Mit Schlüsseln hält die Liste den
    // gerade sichtbaren Abschnitt fest, und Mix und Alben, die oberhalb dazukommen, schieben die Ansicht nach unten.
    HapticLazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomSpace), state = state) {
        // Beim Start ist alles noch leer, weil die Bibliothek lädt: dann nichts zeigen, auch keine Leer-Hinweise
        if (!loaded) return@HapticLazyColumn
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
        // Auch ohne Wiedergaben der letzten 30 Tage führt die Karte in die Ansicht (dort gibt es weitere Zeiträume);
        // erst zeigen, wenn die Rangliste einmal berechnet ist, sonst stünde beim Start kurz "nichts gespielt" da
        if (topShown) {
            item {
                SearchSectionHeader(
                    icon = { HeadphonesIcon(iconSize = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = str(R.string.most_played_title),
                    topPadding = 24.dp
                )
            }
            item {
                MostPlayedCard(top = topSongs, total = topTotal, onClick = onOpenMostPlayed)
            }
        }
    }
}

/** Stand der Karte "Meistens gespielt": die vordersten Titel mit Anzahl und wie viele Titel es insgesamt sind. */
private class TopInfo(val top: List<PlayHistory.Ranked>, val total: Int)

private const val TOP_CACHE_KEY = "home_top_cache"
private const val TOP_CACHE_SIZE = 8

/** Zuletzt berechneter Stand aus den Einstellungen (Form "Gesamtzahl|id:anzahl,id:anzahl"); null, wenn es noch keinen gibt. */
private fun readTopCache(ctx: Context): TopInfo? {
    val raw = prefs(ctx).getString(TOP_CACHE_KEY, null) ?: return null
    val total = raw.substringBefore('|', "").toIntOrNull() ?: return null
    val top = raw.substringAfter('|', "").split(',').mapNotNull { part ->
        val i = part.indexOf(':')
        if (i <= 0) return@mapNotNull null
        val id = part.substring(0, i).toLongOrNull() ?: return@mapNotNull null
        val count = part.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
        PlayHistory.Ranked(id, count, 0L)
    }
    return TopInfo(top, total)
}

private fun saveTopCache(ctx: Context, info: TopInfo) {
    val raw = info.total.toString() + "|" + info.top.joinToString(",") { "${it.id}:${it.count}" }
    val p = prefs(ctx)
    if (p.getString(TOP_CACHE_KEY, null) != raw) p.edit().putString(TOP_CACHE_KEY, raw).apply()
}

/** Karte der intelligenten Playlist: Cover-Raster, Titelzahl, Neu mischen und Abspielen. */
@Composable
private fun MixCard(mix: List<Song>, onPlay: () -> Unit, onReshuffle: () -> Unit) {
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.97f, hapticSnap = true) { onPlay() }
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
