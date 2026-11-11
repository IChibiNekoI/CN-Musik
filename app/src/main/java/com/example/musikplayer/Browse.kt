package com.example.musikplayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import kotlinx.coroutines.delay
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Stand aller Daten, die den Neko Mix beeinflussen (Favoriten, Wiedergaben, Skips). Gleicher Stand ergibt denselben Mix.
 * Verglichen wird billig: Anzahl und jüngster Zeitpunkt reichen, weil Einträge nur hinten angehängt werden.
 */
private data class MixTaste(
    val favorites: Set<Long>,
    val plays: Int,
    val lastPlay: Long,
    val skips: Int,
    val lastSkip: Long
)

@Composable
fun LibraryScreen(
    loaded: Boolean = true,
    pad: PaddingValues,
    songs: List<Song>,
    sort: SortMode,
    currentId: String?,
    playlists: List<Playlist>,
    albumStyle: ViewStyle = ViewStyle(),
    playlistStyle: ViewStyle = ViewStyle(),
    headerStyle: HeaderStyle = HeaderStyle.TEXT,
    onHeaderTap: () -> Unit = {},
    update: UpdateInfo? = null,
    favorites: List<Long> = emptyList(),
    history: List<Long> = emptyList(),               // zuletzt gehörte Titel, neueste zuerst (Startseite)
    playLog: List<PlayEvent> = emptyList(),          // Hörprotokoll der letzten 30 Tage (Startseite: Dein Neko Mix)
    mixSkips: List<MixSkip> = emptyList(),           // Skips im Neko Mix der letzten 30 Tage
    mixExcluded: Set<Long> = emptySet(),             // aus der Warteschlange entfernt: bleiben vorerst aus dem Mix
    onMixPlayed: (List<Song>) -> Unit = {},          // der Mix wurde gestartet (für das Merken von Entfernungen)
    onShufflePlay: (List<Song>) -> Unit = {},        // Zufallswiedergabe einer Playlist (Zufall und Alle wiederholen nur vorübergehend)
    onToggleFavorite: (Song) -> Unit = {},
    onHideSong: ((Song) -> Unit)? = null,
    hiddenIds: Set<Long> = emptySet(),   // nur im Titel-Tab ausgeblendet, überall sonst weiter sichtbar
    onReorderFavorites: (List<Long>) -> Unit = {},
    onPlaylistsChange: (List<Playlist>) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onSettings: () -> Unit,
    albumRequest: String? = null,
    onAlbumRequestHandled: () -> Unit = {},
    playlistRequest: Song? = null,
    onPlaylistRequestHandled: () -> Unit = {},
    bottomSpace: Dp = 0.dp   // Platz unten, damit die Listen unter dem Miniplayer durchscrollen und das Ende trotzdem erreichbar bleibt
) {
    var tab by remember { mutableIntStateOf(if (CornerSettings.showHome) HOME_TAB else 0) }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }   // Playlist, zu der Songs ausgewählt werden
    var creating by remember { mutableStateOf(false) }
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    // Neue Playlists poppen auf, gelöschte schrumpfen weg, bevor sie wirklich entfernt werden
    var knownPlaylistIds by remember { mutableStateOf(playlists.map { it.id }.toSet()) }
    var removingIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(playlists) { knownPlaylistIds = playlists.map { it.id }.toSet() }
    var newName by remember { mutableStateOf("") }
    val latestPlaylists by rememberUpdatedState(playlists)
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchAll by remember { mutableStateOf(false) }          // Suche: alle passenden Alben als Liste oder Raster
    var searchAlbum by remember { mutableStateOf<String?>(null) } // Suche: ein geöffnetes Album
    // Scrollstände der Trefferliste und der Albenzeile bleiben beim Öffnen eines Albums und beim Zurückkehren erhalten
    val searchListState = rememberLazyListState()
    val searchRowState = rememberLazyListState()
    // "Alle anzeigen" in der Suche: Scroll-Position bleibt erhalten, wenn man ein Album öffnet und zurückgeht
    val searchAllGridState = rememberLazyGridState()
    val searchAllListState = rememberLazyListState()
    // Scrollstände der Hauptlisten: Sie liegen hier oben, damit sie beim Öffnen eines Albums oder einer Playlist
    // und beim Wechsel der Tabs nicht verloren gehen (die Listen selbst verlassen dabei kurz die Komposition)
    val songsState = rememberLazyListState()
    val albumsGridState = rememberLazyGridState()
    val albumsListState = rememberLazyListState()
    val playlistsGridState = rememberLazyGridState()
    val playlistsListState = rememberLazyListState()
    // Scrollstände der Favoriten und der geöffneten Album-/Playlist-Ansicht (für die Wischgesten der Kopfzeile)
    val favoritesState = rememberLazyListState()
    val albumDetailState = rememberLazyListState()
    val playlistDetailState = rememberLazyListState()
    var plusMenu by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { importM3u(ctx, uri, songs) }
            if (r == null || r.ids.isEmpty()) {
                Toast.makeText(ctx, str(R.string.toast_no_match), Toast.LENGTH_LONG).show()
            } else {
                onPlaylistsChange(
                    playlists + Playlist(System.currentTimeMillis().toString(), r.name, r.ids)
                )
                Toast.makeText(
                    ctx, str(R.string.toast_import_done, r.name, r.ids.size, r.total), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val sorted = remember(songs, sort) { sortSongs(songs, sort) }
    // Der Titel-Tab lässt ausgeblendete Titel weg. Alben, Playlists, Favoriten und Suche nutzen die volle Liste.
    val titleList = remember(sorted, hiddenIds) { if (hiddenIds.isEmpty()) sorted else sorted.filter { it.id !in hiddenIds } }
    // Alben mit Identität aus Name + Albuminterpret (bzw. Ordner), siehe Albums.kt
    val albums = remember(songs) { groupAlbums(songs) }
    val byId = remember(songs) { songs.associateBy { it.id } }

    // Playlist-Menü: Umbenennen, eigenes Bild, M3U-Export
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }
    var coverTargetId by remember { mutableStateOf<String?>(null) }
    var coverChoice by remember { mutableStateOf<Playlist?>(null) }   // Frage: vom Speicher wählen oder Bild löschen
    var exportTarget by remember { mutableStateOf<Playlist?>(null) }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = coverTargetId
        coverTargetId = null
        if (uri != null && id != null) scope.launch {
            val name = savePlaylistCover(ctx, uri, id)
            if (name == null) {
                Toast.makeText(ctx, str(R.string.tag_image_failed), Toast.LENGTH_LONG).show()
            } else {
                val old = latestPlaylists.firstOrNull { it.id == id }?.cover
                onPlaylistsChange(latestPlaylists.map { if (it.id == id) it.copy(cover = name) else it })
                withContext(Dispatchers.IO) { deletePlaylistCover(ctx, old) }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) scope.launch {
            val list = target.songIds.mapNotNull { byId[it] }
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(buildM3u(list).toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                ctx, str(if (ok) R.string.playlist_exported else R.string.playlist_export_failed), Toast.LENGTH_LONG
            ).show()
        }
    }
    val playlistMenu: @Composable (Playlist, List<Song>, Boolean) -> Unit = { p, covers, compact ->
        PlaylistMenu(
            playlist = p, covers = covers, compact = compact,
            onPickCover = { coverChoice = p },
            onRename = { renameText = p.name; renameTarget = p },
            onPlay = {
                val l = p.songIds.mapNotNull { byId[it] }
                if (l.isNotEmpty()) onPlay(l, 0)
            },
            onShuffle = {
                val l = p.songIds.mapNotNull { byId[it] }
                if (l.isNotEmpty()) onShufflePlay(l)
            },
            onExport = { exportTarget = p; exportLauncher.launch("${p.name}.m3u") },
            onDelete = { deleteTarget = p }
        )
    }
    val favSet = remember(favorites) { favorites.toSet() }
    // Der Favoriten-Tab gibt es nur, solange mindestens ein Titel favorisiert ist
    val showHome = CornerSettings.showHome
    val effTab = when {
        tab == HOME_TAB && !showHome -> 0
        tab == 3 && favorites.isEmpty() -> 0
        else -> tab
    }
    LaunchedEffect(favorites.isEmpty()) { if (favorites.isEmpty() && tab == 3) tab = 0 }
    LaunchedEffect(showHome) { if (!showHome && tab == HOME_TAB) tab = 0 }

    // Startseite: Die Auswahl des Mixes hängt nur von deinem Hören ab (fester Startwert). Der Würfel rechnet sie mit den
    // aktuellen Daten neu und bringt die Titel in eine andere Reihenfolge.
    val mixSeed = remember { readMixSeed(ctx) }
    var mixOrderSeed by remember { mutableLongStateOf(readMixOrderSeed(ctx)) }
    // Neue Wiedergaben, Favoriten und Skips fließen in den Mix ein, aber nicht live: Der Mix soll sich nicht unter den Augen
    // ändern, während man ihn ansieht. [appliedTaste] ist der Stand, mit dem der angezeigte Mix gebaut wurde.
    val mixTaste = remember(favorites, playLog, mixSkips) {
        MixTaste(
            favorites.toSet(), playLog.size, playLog.lastOrNull()?.time ?: 0L,
            mixSkips.size, mixSkips.lastOrNull()?.time ?: 0L
        )
    }
    var appliedTaste by remember { mutableStateOf(mixTaste) }
    val latestTaste by rememberUpdatedState(mixTaste)
    // Die App kommt zurück in den Vordergrund: aktuellen Stand übernehmen
    DisposableEffect(ctx) {
        val owner = ctx as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && appliedTaste != latestTaste) appliedTaste = latestTaste
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    // Man verlässt die Startseite: neuen Stand übernehmen, sobald die Tab-Animation durch ist (sonst ruckelt sie).
    // Die Startseite zeigt beim Zurückkommen also schon den aktualisierten Mix.
    LaunchedEffect(tab, mixTaste) {
        if (tab != HOME_TAB && appliedTaste != mixTaste) {
            delay(Motion.MEDIUM + 100L)
            appliedTaste = mixTaste
        }
    }
    // Album wurde von der Startseite aus geöffnet: Zurück geht dorthin statt in den Album-Tab
    var albumFromHome by remember { mutableStateOf(false) }
    var mostPlayedOpen by remember { mutableStateOf(false) }   // "Meistens gespielt" auf dem Start-Tab
    fun closeAlbum() {
        openAlbum = null
        if (albumFromHome) { albumFromHome = false; tab = HOME_TAB }
    }
    val homeState = rememberLazyListState()
    val homeAlbumsRowState = rememberLazyListState()
    // "Meistens gespielt": Liste und Position des laufenden Titels liegen hier, damit die Wischgesten der Kopfzeile darauf wirken
    val mostPlayedState = rememberLazyListState()
    var mostPlayedCurrentIdx by remember { mutableIntStateOf(-1) }
    // Ausgeblendete Titel sind komplett draußen: weder im Mix noch als Gewicht für Künstler
    val mixPool = remember(songs, hiddenIds, mixExcluded) {
        if (hiddenIds.isEmpty() && mixExcluded.isEmpty()) songs
        else songs.filter { it.id !in hiddenIds && it.id !in mixExcluded }
    }
    val mix = remember(mixPool, mixOrderSeed, showHome, appliedTaste) {
        if (!showHome) emptyList() else buildMix(
            mixPool, favorites.toSet(), playLog, mixSeed, mixOrderSeed,
            skips = mixSkips,
            totals = readPlayTotals(ctx),
            features = { id -> TrackFeatureCache.get(ctx, id) }
        )
    }

    // Vom Player aus: zum Album springen
    LaunchedEffect(albumRequest) {
        val name = albumRequest ?: return@LaunchedEffect
        if (albums.any { it.first == name }) {
            searching = false
            query = ""
            openPlaylist = null
            tab = 1
            albumFromHome = false
            openAlbum = name
        } else {
            Toast.makeText(ctx, str(R.string.toast_album_missing), Toast.LENGTH_SHORT).show()
        }
        onAlbumRequestHandled()
    }
    // Vom Player aus: "Zu Playlist hinzufügen" öffnet den bekannten Dialog
    LaunchedEffect(playlistRequest) {
        val s = playlistRequest ?: return@LaunchedEffect
        addSong = s
        onPlaylistRequestHandled()
    }

    BackHandler(enabled = openAlbum != null || openPlaylist != null) {
        openPlaylist = null
        closeAlbum()
    }
    BackHandler(enabled = mostPlayedOpen) { mostPlayedOpen = false }
    // Beim Wechsel zu einem anderen Tab schließt sich "Meistens gespielt"
    LaunchedEffect(tab) { if (tab != HOME_TAB) mostPlayedOpen = false }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    // Zurück: erst aus dem Album, dann aus der Albenübersicht, dann aus der Suche
    LaunchedEffect(query) {
        searchAllGridState.scrollToItem(0)
        searchAllListState.scrollToItem(0)
    }
    LaunchedEffect(searchAll) {
        if (!searchAll) {
            searchAllGridState.scrollToItem(0)
            searchAllListState.scrollToItem(0)
        }
    }
    BackHandler(enabled = searching && (searchAlbum != null || searchAll)) {
        if (searchAlbum != null) searchAlbum = null else searchAll = false
    }
    LaunchedEffect(searching) {
        if (searching) runCatching { searchFocus.requestFocus() }
        else { searchAll = false; searchAlbum = null }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) { searchAll = false; searchAlbum = null }
        runCatching { searchListState.scrollToItem(0); searchRowState.scrollToItem(0) }
    }
    val results = remember(sorted, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList<Song>()
        else sorted.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    // Passende Alben: Name oder Künstler enthält den Suchtext
    val matchedAlbums = remember(albums, query) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else albums.filter { (name, list) ->
            name.contains(q, ignoreCase = true) ||
                list.any { it.artist.contains(q, ignoreCase = true) || it.albumArtist.contains(q, ignoreCase = true) }
        }
    }

    // Wischgesten Kopfzeile: 0 = Tippen (zum aktuellen Titel/Album), 1 = zum Anfang, 2 = zum Ende
    LaunchedEffect(openAlbum) { if (openAlbum != null) albumDetailState.scrollToItem(0) }
    LaunchedEffect(openPlaylist) { if (openPlaylist != null) playlistDetailState.scrollToItem(0) }
    val headerAction: (Int) -> Unit = { kind ->
        val curId = currentId
        val curSong = songs.firstOrNull { it.id.toString() == curId }
        scope.launch {
            // Während des Sprungs keine Ein-/Ausblend-Animation der Zeilen, sonst blinkt die Liste kurz
            ItemAnimation.suspended = true
            try {
            when (effTab) {
                HOME_TAB ->
                    if (mostPlayedOpen) mostPlayedState.headerJump(kind, mostPlayedCurrentIdx)
                    else homeState.headerJump(kind, -1)
                0 -> songsState.headerJump(kind, titleList.indexOfFirst { it.id.toString() == curId })
                1 -> {
                    val name = openAlbum
                    if (name != null) {
                        val ordered = (albums.firstOrNull { it.first == name }?.second ?: emptyList())
                            .sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase() }))
                        albumDetailState.headerJump(kind, ordered.indexOfFirst { it.id.toString() == curId })
                    } else {
                        val idx = if (curSong == null) -1
                            else albums.indexOfFirst { (_, l) -> l.any { it.id == curSong.id } }
                        if (albumStyle.grid) albumsGridState.headerJump(kind, idx) else albumsListState.headerJump(kind, idx)
                    }
                }
                2 -> {
                    val pl = openPlaylist?.let { id -> playlists.firstOrNull { it.id == id } }
                    if (pl != null) {
                        val list = pl.songIds.mapNotNull { byId[it] }
                        playlistDetailState.headerJump(kind, list.indexOfFirst { it.id.toString() == curId })
                    } else if (playlistStyle.grid) playlistsGridState.headerJump(kind, -1)
                    else playlistsListState.headerJump(kind, -1)
                }
                else -> {
                    // Favoriten: nur springen, wenn der laufende Titel darin steht
                    val favSongs = favorites.mapNotNull { byId[it] }
                    favoritesState.headerJump(kind, favSongs.indexOfFirst { it.id.toString() == curId })
                }
            }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.delay(200)
                    ItemAnimation.suspended = false
                }
            }
        }
    }
    val tapHeader: () -> Unit = {
        onHeaderTap()
        if (CornerSettings.swipeHeader) { Haptics.snap(ctx); headerAction(0) }
    }
    val swipeHeaderMod = if (CornerSettings.swipeHeader) Modifier.headerSwipe(
        onUp = { Haptics.snap(ctx); headerAction(1) },
        onDown = { Haptics.snap(ctx); headerAction(2) }
    ) else Modifier

    Column(Modifier.fillMaxSize().padding(pad)) {
        // Die Kopfzeile wandelt sich in das Suchfeld: Es wächst mit leichtem Überschwinger aus der Such-Taste heraus
        AnimatedContent(
            targetState = searching,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = { searchHeader() },
            label = "searchHeader"
        ) { isSearching ->
        Row(
            Modifier.fillMaxWidth()
                .then(if (isSearching) Modifier else swipeHeaderMod)
                .padding(start = if (isSearching) 8.dp else 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearching) {
                BounceIconButton(onClick = { searching = false; query = "" }) {
                    BackIcon(description = str(R.string.search_close))
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(searchFocus),
                    placeholder = { Text(str(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    leadingIcon = { SearchIcon(description = str(R.string.search)) },
                    trailingIcon = {
                        // Das Kreuz poppt auf, sobald etwas getippt ist, und schrumpft beim Leeren wieder weg
                        AnimatedVisibility(
                            visible = query.isNotEmpty(),
                            enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), initialScale = 0.4f) + fadeIn(tween(Motion.FAST)),
                            exit = scaleOut(tween(Motion.FAST), targetScale = 0.4f) + fadeOut(tween(Motion.FAST))
                        ) {
                            BounceIconButton(onClick = { query = "" }) {
                                CloseIcon(description = str(R.string.clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            } else {
                // Jeder Tipp auf die Kopfzeile wird gezählt (Easter Egg), ohne sichtbare Welle
                val headerTap = remember { MutableInteractionSource() }
                when (headerStyle) {
                    HeaderStyle.LOGO -> Box(
                        Modifier.weight(1f).then(if (CornerSettings.swipeHeader) Modifier.clickable(interactionSource = headerTap, indication = null) { tapHeader() } else Modifier),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Image(
                            painter = painterResource(R.drawable.title_logo),
                            contentDescription = "ChibiNeko",
                            // Höhe des Logos hier ändern; die Breite folgt dem Seitenverhältnis (362:68)
                            modifier = Modifier.height(36.dp).aspectRatio(362f / 68f)
                                .clickable(interactionSource = headerTap, indication = null) { tapHeader() },
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
                        )
                    }
                    HeaderStyle.FOX -> Box(
                        Modifier.weight(1f).then(if (CornerSettings.swipeHeader) Modifier.clickable(interactionSource = headerTap, indication = null) { tapHeader() } else Modifier),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Image(
                            painter = painterResource(R.drawable.header_fox),
                            contentDescription = null,
                            // Höhe des Fuchses hier ändern; die Breite folgt dem Seitenverhältnis (640:390)
                            modifier = Modifier.height(52.dp).aspectRatio(640f / 390f)
                                .clickable(interactionSource = headerTap, indication = null) { tapHeader() }
                        )
                    }
                    HeaderStyle.TEXT -> {
                        Text(
                            str(R.string.app_header_text),
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.clickable(interactionSource = headerTap, indication = null) { tapHeader() }
                        )
                        Spacer(
                            Modifier.weight(1f).height(48.dp)   // gleiche Höhe wie die Symbol-Tasten, füllt nicht die ganze Höhe
                                .then(if (CornerSettings.swipeHeader) Modifier.clickable(interactionSource = headerTap, indication = null) { tapHeader() } else Modifier)
                        )
                    }
                }
                if (tab == 2) {
                    // In einer geöffneten Playlist blendet sich das Plus aus (dort gibt es ein eigenes Plus für Lieder)
                    AnimatedVisibility(
                        visible = openPlaylist == null,
                        enter = fadeIn(tween(Motion.MEDIUM)) + expandHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease)),
                        exit = fadeOut(tween(Motion.FAST)) + shrinkHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease))
                    ) {
                    Box {
                        BounceIconButton(onClick = { plusMenu = true }, rotate = 40f) {
                            PlusIcon(description = str(R.string.playlist_add_cd))
                        }
                        BounceMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(str(R.string.playlist_new)) },
                                leadingIcon = { PlusIcon() },
                                onClick = {
                                    plusMenu = false
                                    pendingSong = null
                                    newName = ""
                                    creating = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(str(R.string.playlist_import)) },
                                leadingIcon = { FolderIcon() },
                                onClick = {
                                    plusMenu = false
                                    m3uLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                    }
                }
                if (update != null && tab != 2) {
                    UpdateButton(onClick = { showUpdate = true })
                }
                BounceIconButton(onClick = { searching = true }, rotate = 40f) {
                    SearchIcon(description = str(R.string.search))
                }
                BounceIconButton(onClick = onSettings, rotate = 40f) {
                    SettingsGearIcon(description = str(R.string.settings))
                }
            }
        }
        }   // Ende AnimatedContent (Kopfzeile)
        AnimatedContent(
            targetState = searching,
            modifier = Modifier.weight(1f),
            transitionSpec = { searchBody() },
            label = "searchBody"
        ) { isSearching ->
        Column(Modifier.fillMaxSize()) {
        if (isSearching) {
            // Hinweistext wechselt weich zwischen "Suche nach ...", "Keine Treffer" und nichts
            val hint = if (query.isBlank()) str(R.string.search_prompt)
                else if (results.isEmpty()) str(R.string.no_results) else null
            AnimatedContent(
                targetState = hint,
                transitionSpec = {
                    (fadeIn(tween(Motion.MEDIUM, delayMillis = 60)) + slideInVertically(tween(Motion.MEDIUM, easing = Motion.Ease)) { it / 3 }) togetherWith
                        fadeOut(tween(Motion.FAST)) using SizeTransform(clip = false)
                },
                label = "searchHint"
            ) { h ->
                if (h != null) {
                    Text(
                        h,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(0.dp))
                }
            }
            AnimatedContent(
                targetState = SearchView(searchAll && matchedAlbums.isNotEmpty(), searchAlbum),
                modifier = Modifier.weight(1f),
                transitionSpec = { searchPushPop() },
                label = "searchView"
            ) { view ->
            Column(Modifier.fillMaxSize()) {
            val openName = view.album
            if (openName != null) {
                // Ein Album aus der Suche: Titelliste mit Zurück-Pfeil
                val ordered = remember(albums, openName) {
                    (albums.firstOrNull { it.first == openName }?.second ?: emptyList())
                        .sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase() }))
                }
                DetailHeader(openName, onBack = { searchAlbum = null })
                HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                    itemsIndexed(ordered, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            s, s.id.toString() == currentId, { onPlay(ordered, i) }, { addSong = s },
                            isFavorite = s.id in favSet,
                            trackNumber = trackNumberOf(s, i + 1),
                            modifier = listItemAnim(),
                            onToggleFavorite = { onToggleFavorite(s) }
                        )
                    }
                }
            } else if (view.all && matchedAlbums.isNotEmpty()) {
                // Alle passenden Alben, so dargestellt wie in der Albenansicht eingestellt
                DetailHeader(str(R.string.search_albums_title, matchedAlbums.size), onBack = { searchAll = false })
                AlbumCollection(
                    albums = matchedAlbums,
                    style = albumStyle,
                    bottomSpace = bottomSpace,
                    modifier = Modifier.weight(1f),
                    gridState = searchAllGridState,
                    listState = searchAllListState
                ) { name -> focusManager.clearFocus(); searchAlbum = name }
            } else {
                HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace), state = searchListState) {
                    if (matchedAlbums.isNotEmpty()) {
                        item(key = "albumsHeader") {
                            SearchSectionHeader(
                                icon = { AlbumDiscIcon(iconSize = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                title = str(R.string.search_albums_title, matchedAlbums.size),
                                onShowAll = if (matchedAlbums.size > 2) ({ focusManager.clearFocus(); searchAll = true }) else null
                            )
                        }
                        item(key = "albumsRow") {
                            LazyRow(
                                state = searchRowState,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(matchedAlbums, key = { it.first }) { (albumName, list) ->
                                    SearchAlbumCard(
                                        songs = list,
                                        name = albumName,
                                        onOpen = { focusManager.clearFocus(); searchAlbum = albumName },
                                        onPlay = {
                                            focusManager.clearFocus()
                                            val ordered = list.sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase() }))
                                            onPlay(ordered, 0)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (matchedAlbums.isNotEmpty() && results.isNotEmpty()) {
                        item(key = "tracksHeader") {
                            SearchSectionHeader(
                                icon = { MusicNoteIcon(iconSize = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                title = str(R.string.search_tracks_title, results.size),
                                topPadding = 28.dp   // Luft zwischen der Albenzeile und den Titeln
                            )
                        }
                    }
                    itemsIndexed(results, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            s, s.id.toString() == currentId,
                            { focusManager.clearFocus(); onPlay(results, i) },
                            { addSong = s },
                            isFavorite = s.id in favSet,
                            modifier = listItemAnim(),
                            onToggleFavorite = { onToggleFavorite(s) }
                        )
                    }
                }
            }
            }
            }
        } else {
        // Passen nicht alle Chips auf ein sehr schmales Display, lässt sich die Reihe seitlich wischen
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Start, Titel, Album, Playlists und Favoriten (nur sobald es welche gibt): alle nur mit Symbol, alle gleich breit
            val tabIds = buildList {
                if (showHome) add(HOME_TAB)
                add(0); add(1); add(2)
                if (favorites.isNotEmpty()) add(3)
            }
            val chipCtx = LocalContext.current
            tabIds.forEach { i ->
                val label = when (i) {
                    HOME_TAB -> str(R.string.tab_home)
                    0 -> str(R.string.tab_tracks)
                    1 -> str(R.string.tab_albums)
                    2 -> str(R.string.tab_playlists)
                    else -> str(R.string.tab_favorites)
                }
                // Gedrückt: Der Chip drückt sich ein und federt beim Loslassen mit kleinem Überschwinger zurück
                val chipSource = remember { MutableInteractionSource() }
                val chipPress = rememberPressProgress(chipSource)
                FilterChip(
                    modifier = Modifier
                        .width(60.dp)
                        .graphicsLayer {
                            val s = 1f - 0.1f * chipPress.value
                            scaleX = s
                            scaleY = s
                        },
                    interactionSource = chipSource,
                    selected = effTab == i,
                    onClick = {
                        Haptics.click(chipCtx)
                        tab = i; openAlbum = null; openPlaylist = null; albumFromHome = false
                    },
                    label = {
                        // Nur Symbole, mittig im Chip; der Text steht als Beschreibung für Screenreader dabei
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            when (i) {
                                HOME_TAB -> HomeIcon(iconSize = 20.dp, description = label)
                                0 -> MusicNoteIcon(iconSize = 20.dp, description = label)
                                1 -> AlbumDiscIcon(iconSize = 20.dp, description = label)
                                2 -> PlaylistIcon(iconSize = 20.dp, description = label)
                                else -> HeartIcon(iconSize = 20.dp, description = label)
                            }
                        }
                    }
                )
            }
        }

        // Beim Tabwechsel gleitet der Inhalt weich in Richtung des neuen Tabs
        AnimatedContent(
            targetState = effTab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease)) { w -> dir * w / 6 } +
                    fadeIn(tween(Motion.MEDIUM - 60, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease)) { w -> -dir * w / 6 } +
                        fadeOut(tween(Motion.FAST))) using SizeTransform(clip = false)
            },
            label = "tabs"
        ) { t ->
        Column(Modifier.fillMaxSize()) {
        when (t) {
            // Wie Album und Playlist: "Meistens gespielt" schiebt sich weich herein und beim Zurückgehen wieder hinaus
            HOME_TAB -> AnimatedContent(
                targetState = mostPlayedOpen,
                modifier = Modifier.weight(1f),
                transitionSpec = { pushPopFlag() },
                label = "mostPlayed"
            ) { statsPage ->
            if (statsPage) {
                MostPlayedScreen(
                    songs = songs,
                    currentId = currentId,
                    favSet = favSet,
                    bottomSpace = bottomSpace,
                    onPlay = onPlay,
                    onShufflePlay = onShufflePlay,
                    onAddToPlaylist = { addSong = it },
                    onToggleFavorite = onToggleFavorite,
                    listState = mostPlayedState,
                    onCurrentIndexChanged = { mostPlayedCurrentIdx = it },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
              HomeScreen(
                songs = songs,
                loaded = loaded,
                mix = mix,
                history = history,
                currentId = currentId,
                favSet = favSet,
                state = homeState,
                albumsRowState = homeAlbumsRowState,
                bottomSpace = bottomSpace,
                onPlay = onPlay,
                onPlayMix = { list -> onMixPlayed(list); onPlay(list, 0) },   // erst als Mix markieren, dann starten
                onOpenAlbum = { name -> openPlaylist = null; openAlbum = name; albumFromHome = true; tab = 1 },
                onReshuffle = { mixOrderSeed = newMixOrderSeed(ctx); appliedTaste = mixTaste },   // Würfel: auch den neuesten Hörstand einrechnen
                onOpenMostPlayed = { mostPlayedOpen = true },
                onAddToPlaylist = { addSong = it },
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.fillMaxSize()
            )
            }
            }
            0 -> {
                // Beim Start ist die Liste kurz leer, weil die Bibliothek noch lädt: dann keine Fehlermeldung zeigen
                if (loaded && titleList.isEmpty()) {
                    Text(
                        str(R.string.no_songs),
                        modifier = Modifier.padding(16.dp)
                    )
                }
                HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace), state = songsState) {
                    itemsIndexed(titleList, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            s, s.id.toString() == currentId, { onPlay(titleList, i) }, { addSong = s },
                            onHide = onHideSong?.let { hide -> { hide(s) } },
                            isFavorite = s.id in favSet,
                            modifier = listItemAnim(),
                            onToggleFavorite = { onToggleFavorite(s) }
                        )
                    }
                }
            }
            1 -> {
                AnimatedContent(
                    targetState = openAlbum,
                    modifier = Modifier.weight(1f),
                    transitionSpec = { pushPop() },
                    label = "album"
                ) { name ->
                Column(Modifier.fillMaxSize()) {
                if (name == null && albumStyle.grid) {
                    HapticLazyGrid(
                        columns = GridCells.Fixed(albumStyle.cols),
                        modifier = Modifier.weight(1f),
                        state = albumsGridState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomSpace),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        gridItems(albums, key = { it.first }) { (albumName, list) ->
                            GridTile(
                                title = albumName,
                                subtitle = albumArtistOf(list),
                                detail = trackMetaOf(list),
                                onClick = { openAlbum = albumName },
                                cover = { w -> Cover(list.first(), w) }
                            )
                        }
                    }
                } else if (name == null) {
                    HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace), state = albumsListState) {
                        items(albums, key = { it.first }) { (albumName, list) ->
                            ListItem(
                                headlineContent = {
                                    Text(albumName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Column {
                                        Text(albumArtistOf(list), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            trackMetaOf(list),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                leadingContent = { Cover(list.first(), 56.dp) },
                                colors = clearColors(),
                                modifier = Modifier.bounceClick(pressedScale = 0.97f, hapticSnap = true) { openAlbum = albumName }
                            )
                        }
                    }
                } else {
                    val ordered = remember(albums, name) {
                        (albums.firstOrNull { it.first == name }?.second ?: emptyList())
                            .sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase() }))
                    }
                    DetailHeader(name, onBack = { closeAlbum() })
                    HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace), state = albumDetailState) {
                        itemsIndexed(ordered, key = { _, s -> s.id }) { i, s ->
                            SongRow(
                                s, s.id.toString() == currentId, { onPlay(ordered, i) }, { addSong = s },
                                isFavorite = s.id in favSet,
                                trackNumber = trackNumberOf(s, i + 1),
                                modifier = listItemAnim(),
                                onToggleFavorite = { onToggleFavorite(s) }
                            )
                        }
                    }
                }
                }
                }
            }
            3 -> {
                // neueste Favoriten zuerst
                val favSongs = remember(favorites, byId) { favorites.mapNotNull { byId[it] } }
                // Titel 1,5 s gedrückt halten und ziehen, um ihn zu verschieben
                ReorderableSongList(
                    songs = favSongs,
                    onReorder = { new ->
                        // Titel, die gerade nicht in der Bibliothek sind, bleiben am Ende erhalten
                        onReorderFavorites(new.map { it.id } + favorites.filter { id -> byId[id] == null })
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = bottomSpace),
                    listState = favoritesState
                ) { i, s, itemMod ->
                    SongRow(
                        s, s.id.toString() == currentId, { onPlay(favSongs, i) }, { addSong = s },
                        isFavorite = true,
                        modifier = itemMod,
                        onToggleFavorite = { onToggleFavorite(s) }
                    )
                }
            }
            else -> {
                AnimatedContent(
                    targetState = openPlaylist,
                    modifier = Modifier.weight(1f),
                    transitionSpec = { pushPop() },
                    label = "playlist"
                ) { plId ->
                Column(Modifier.fillMaxSize()) {
                val pl = playlists.firstOrNull { it.id == plId }
                if (pl == null && playlists.isEmpty()) {
                    Text(
                        str(R.string.no_playlists),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pl == null && playlistStyle.grid) {
                    // Playlist 1 s gedrückt halten und ziehen, um sie zu verschieben (wie Titel in einer Playlist)
                    ReorderableGrid(
                        items = playlists,
                        itemKey = { it.id },
                        onReorder = { onPlaylistsChange(it) },
                        columns = GridCells.Fixed(playlistStyle.cols),
                        modifier = Modifier.weight(1f),
                        gridState = playlistsGridState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomSpace),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        plainItemModifier = {
                            Modifier.animateItem(
                                fadeInSpec = null, fadeOutSpec = null,
                                // während eines Kopfzeilen-Sprungs sofort statt animiert; NIE null, sonst hängt sich der Modifier ab und die Liste blendet neu ein
                                placementSpec = if (ItemAnimation.suspended) snap<IntOffset>() else spring(
                                    dampingRatio = 0.75f, stiffness = 400f,
                                    visibilityThreshold = IntOffset.VisibilityThreshold
                                )
                            )
                        }
                    ) { _, p, itemMod ->
                        val covers = remember(p.songIds, byId) {
                            p.songIds.asSequence().mapNotNull { byId[it] }.take(4).toList()
                        }
                        GridTile(
                            title = p.name,
                            subtitle = playlistMeta(p.songIds, byId),
                            onClick = { openPlaylist = p.id },
                            menu = { playlistMenu(p, covers, true) },
                            modifier = itemMod
                                .playlistFx(appear = p.id !in knownPlaylistIds, removing = p.id in removingIds),
                            cover = { w -> PlaylistCover(covers, w, custom = p.cover) }
                        )
                    }
                } else if (pl == null) {
                    // Playlist 1 s gedrückt halten und ziehen, um sie zu verschieben (wie Titel in einer Playlist)
                    ReorderableList(
                        items = playlists,
                        itemKey = { it.id },
                        onReorder = { onPlaylistsChange(it) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = bottomSpace),
                        listState = playlistsListState,
                        plainItemModifier = {
                            Modifier.animateItem(
                                fadeInSpec = null, fadeOutSpec = null,
                                // während eines Kopfzeilen-Sprungs sofort statt animiert; NIE null, sonst hängt sich der Modifier ab und die Liste blendet neu ein
                                placementSpec = if (ItemAnimation.suspended) snap<IntOffset>() else spring(
                                    dampingRatio = 0.75f, stiffness = 400f,
                                    visibilityThreshold = IntOffset.VisibilityThreshold
                                )
                            )
                        }
                    ) { _, p, itemMod ->
                        val listCovers = remember(p.songIds, byId) {
                            p.songIds.asSequence().mapNotNull { byId[it] }.take(4).toList()
                        }
                        ListItem(
                            headlineContent = {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(playlistMeta(p.songIds, byId)) },
                            leadingContent = {
                                PlaylistCover(listCovers, 56.dp, custom = p.cover)
                            },
                            trailingContent = { playlistMenu(p, listCovers, false) },
                            colors = clearColors(),
                            modifier = itemMod
                                .playlistFx(appear = p.id !in knownPlaylistIds, removing = p.id in removingIds)
                                .bounceClick(pressedScale = 0.97f, hapticSnap = true) { openPlaylist = p.id }
                        )
                    }
                } else {
                    val list = pl.songIds.mapNotNull { byId[it] }
                    DetailHeader(
                        pl.name,
                        onBack = null,   // zurück per Zurück-Geste (BackHandler oben)
                        // Rechts: Lieder hinzufügen und Zufallswiedergabe (bei leerer Playlist übernimmt das Hinzufügen die Taste unter dem Bild)
                        action = if (list.isNotEmpty()) {
                            {
                                BounceIconButton(onClick = { pickFor = pl.id }) {
                                    PlusIcon(description = str(R.string.add_songs))
                                }
                                BounceIconButton(onClick = { onShufflePlay(list) }) {
                                    ShuffleIcon(description = str(R.string.cd_shuffle))
                                }
                            }
                        } else null
                    )
                    if (list.isEmpty()) {
                        // Leere Playlist: Bild und darunter eine Taste zum Hinzufügen von Liedern
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.playlist_empty),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(0.8f),
                                contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            )
                            Spacer(Modifier.height(24.dp))
                            FilledTonalButton(onClick = { pickFor = pl.id }) {
                                PlusIcon(iconSize = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(str(R.string.add_songs))
                            }
                            Spacer(Modifier.height(bottomSpace))
                        }
                    } else
                    // Titel 1,5 s gedrückt halten und ziehen, um ihn zu verschieben
                    ReorderableSongList(
                        songs = list,
                        onReorder = { new ->
                            // Titel, die gerade nicht in der Bibliothek sind, bleiben am Ende erhalten
                            val ids = new.map { it.id } + pl.songIds.filter { id -> byId[id] == null }
                            onPlaylistsChange(
                                playlists.map { if (it.id == pl.id) it.copy(songIds = ids) else it }
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = bottomSpace),
                        listState = playlistDetailState
                    ) { i, s, itemMod ->
                        SongRow(
                            s, s.id.toString() == currentId,
                            onClick = { onPlay(list, i) },
                            onAddToPlaylist = { addSong = s },
                            isFavorite = s.id in favSet,
                            modifier = itemMod,
                            onToggleFavorite = { onToggleFavorite(s) },
                            onRemove = {
                                onPlaylistsChange(
                                    playlists.map {
                                        if (it.id == pl.id) it.copy(songIds = it.songIds - s.id) else it
                                    }
                                )
                            }
                        )
                    }
                }
                }
                }
            }
        }
        }   // Ende Column (Tab-Inhalt)
        }   // Ende AnimatedContent
        }
        }
        }
    }

    if (showUpdate && update != null) {
        UpdateDialog(update) { showUpdate = false }
    }

    pickFor?.let { plId ->
        val target = playlists.firstOrNull { it.id == plId }
        if (target == null) { pickFor = null } else {
            SongPickerDialog(
                songs = songs,
                target = target,
                onDismiss = { pickFor = null },
                onAdd = { add ->
                    onPlaylistsChange(
                        playlists.map { if (it.id == plId) it.copy(songIds = it.songIds + add) else it }
                    )
                    pickFor = null
                }
            )
        }
    }

    AddToPlaylistDialog(
        song = addSong,
        playlists = playlists,
        onDismiss = { addSong = null },
        onCreateNew = { s ->
            pendingSong = s; addSong = null; newName = ""; creating = true
        },
        onAdd = { s, p ->
            if (s.id !in p.songIds) {
                onPlaylistsChange(
                    playlists.map {
                        if (it.id == p.id) it.copy(songIds = it.songIds + s.id) else it
                    }
                )
            }
            addSong = null
        }
    )

    NewPlaylistDialog(
        visible = creating,
        name = newName,
        onNameChange = { newName = it },
        onDismiss = { pendingSong = null; creating = false },
        onCreate = {
            val p = Playlist(
                System.currentTimeMillis().toString(),
                newName.trim(),
                listOfNotNull(pendingSong?.id)
            )
            onPlaylistsChange(playlists + p)
            pendingSong = null
            creating = false
        }
    )

    PlaylistImageDialog(
        target = coverChoice,
        onDismiss = { coverChoice = null },
        onPick = { t ->
            coverChoice = null
            coverTargetId = t.id
            coverLauncher.launch("image/*")
        },
        onDelete = { t ->
            coverChoice = null
            scope.launch {
                val old = latestPlaylists.firstOrNull { it.id == t.id }?.cover
                onPlaylistsChange(latestPlaylists.map { if (it.id == t.id) it.copy(cover = null) else it })
                withContext(Dispatchers.IO) { deletePlaylistCover(ctx, old) }
            }
        }
    )

    RenamePlaylistDialog(
        target = renameTarget,
        text = renameText,
        onTextChange = { renameText = it },
        onDismiss = { renameTarget = null },
        onSave = { t, n ->
            onPlaylistsChange(latestPlaylists.map { if (it.id == t.id) it.copy(name = n) else it })
        }
    )

    DeletePlaylistDialog(
        target = deleteTarget,
        onDismiss = { deleteTarget = null },
        onConfirm = { t ->
            // Erst schrumpft die Kachel weg, dann wird sie entfernt und die übrigen rücken mit Feder nach
            removingIds = removingIds + t.id
            scope.launch {
                delay(Motion.MEDIUM.toLong())
                onPlaylistsChange(latestPlaylists.filter { it.id != t.id })
                withContext(Dispatchers.IO) { deletePlaylistCover(ctx, t.cover) }
                removingIds = removingIds - t.id
            }
        }
    )
}
