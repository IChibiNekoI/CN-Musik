package com.example.musikplayer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
private fun clearColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Playlist-Cover: 2x2-Raster aus den Covern der ersten 4 Titel. */
@Composable
fun PlaylistCover(songs: List<Song>, size: Dp, corner: Dp = 12.dp) {
    val first = songs.take(4)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(coverRadius(LocalCoverStyle.current, size, corner)))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
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

/** Kachel für die Rasteransicht: Cover in voller Kachelbreite, darunter Titel und Untertitel. */
@Composable
private fun GridTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    cover: @Composable (Dp) -> Unit
) {
    BoxWithConstraints(
        // Kein Clip und keine Klick-Animation: Der Clip schnitt unten die Ecken von Titel und Untertitel ab,
        // und der Ripple färbte die Kachel beim Drücken weiß.
        Modifier.fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
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
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        TrashIcon(iconSize = 18.dp, description = str(R.string.delete))
                    }
                }
            }
        }
    }
}

@Composable
fun DetailHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            BackIcon(description = str(R.string.back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // optionale Taste rechts auf Höhe des Titels
        action?.invoke()
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Die Hervorhebung des laufenden Titels blendet weich ein und aus
    val rowColor by animateColorAsState(
        if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f),
        animationSpec = tween(Motion.MEDIUM), label = "rowColor"
    )
    var expanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    val rounded = LocalCornerStyle.current == CornerStyle.UNIFORM
    // Gemeinsame Quelle für Drücken: der Klick liegt auf dem ListItem, die Welle wird dahinter
    // im eingerückten Rechteck gezeichnet (gleiche Breite wie die Hervorhebung des laufenden Titels)
    val pressSource = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth()) {
    if (rounded) {
        // Einheitlich: abgerundete, leicht eingerückte Hervorhebung. Der Inhalt bleibt an seinem gewohnten Platz.
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(rowColor)
                .indication(pressSource, LocalIndication.current)
        )
    }
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Cover(song, 48.dp) },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    MoreVertIcon(description = str(R.string.more))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(str(R.string.track_info)) },
                        leadingIcon = { InfoIcon() },
                        onClick = { expanded = false; showInfo = true }
                    )
                    DropdownMenuItem(
                        text = { Text(str(R.string.edit_tags)) },
                        leadingIcon = { EditIcon() },
                        onClick = { expanded = false; showTags = true }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (isFavorite) str(R.string.fav_remove) else str(R.string.fav_add))
                        },
                        leadingIcon = { HeartIcon(filled = isFavorite) },
                        onClick = { expanded = false; onToggleFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text(str(R.string.add_to_playlist)) },
                        leadingIcon = {
                            PlaylistAddIcon()
                        },
                        onClick = { expanded = false; onAddToPlaylist() }
                    )
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text(str(R.string.remove_from_playlist)) },
                            leadingIcon = { TrashIcon() },
                            onClick = { expanded = false; onRemove() }
                        )
                    }
                }
                if (showInfo) SongInfoDialog(song) { showInfo = false }
                if (showTags) TagEditDialog(song) { showTags = false }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (rounded) Color.Transparent else rowColor),
        // Der Klick muss auf dem ListItem selbst liegen: dessen Surface fängt alle Berührungen ab,
        // ein Klick auf der Box dahinter käme nie an.
        modifier = if (rounded) {
            Modifier.clip(RoundedCornerShape(20.dp))
                .clickable(interactionSource = pressSource, indication = null) { onClick() }
        } else Modifier.clickable { onClick() }
    )
    }
}

@Composable
fun LibraryScreen(
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
    onToggleFavorite: (Song) -> Unit = {},
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
    var tab by remember { mutableIntStateOf(0) }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }   // Playlist, zu der Songs ausgewählt werden
    var creating by remember { mutableStateOf(false) }
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
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
    val albums = remember(songs) {
        songs.groupBy { it.album.ifBlank { str(R.string.unknown_album) } }
            .toList().sortedBy { it.first.lowercase() }
    }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val favSet = remember(favorites) { favorites.toSet() }
    // Der Favoriten-Tab gibt es nur, solange mindestens ein Titel favorisiert ist
    val effTab = if (tab == 3 && favorites.isEmpty()) 0 else tab
    LaunchedEffect(favorites.isEmpty()) { if (favorites.isEmpty() && tab == 3) tab = 0 }

    // Vom Player aus: zum Album springen
    LaunchedEffect(albumRequest) {
        val name = albumRequest ?: return@LaunchedEffect
        if (albums.any { it.first == name }) {
            searching = false
            query = ""
            openPlaylist = null
            tab = 1
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
        openAlbum = null
        openPlaylist = null
    }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
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

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = if (searching) 8.dp else 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searching) {
                IconButton(onClick = { searching = false; query = "" }) {
                    BackIcon(description = str(R.string.search_close))
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                    placeholder = { Text(str(R.string.search_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                CloseIcon(description = str(R.string.clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            } else {
                // Jeder Tipp auf die Kopfzeile wird gezählt (Easter Egg), ohne sichtbare Welle
                val headerTap = remember { MutableInteractionSource() }
                when (headerStyle) {
                    HeaderStyle.LOGO -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Image(
                            painter = painterResource(R.drawable.title_logo),
                            contentDescription = "ChibiNeko",
                            // Höhe des Logos hier ändern; die Breite folgt dem Seitenverhältnis (362:68)
                            modifier = Modifier.height(36.dp).aspectRatio(362f / 68f)
                                .clickable(interactionSource = headerTap, indication = null) { onHeaderTap() },
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
                        )
                    }
                    HeaderStyle.FOX -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Image(
                            painter = painterResource(R.drawable.header_fox),
                            contentDescription = null,
                            // Höhe des Fuchses hier ändern; die Breite folgt dem Seitenverhältnis (640:390)
                            modifier = Modifier.height(52.dp).aspectRatio(640f / 390f)
                                .clickable(interactionSource = headerTap, indication = null) { onHeaderTap() }
                        )
                    }
                    HeaderStyle.TEXT -> {
                        Text(
                            str(R.string.app_header_text),
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.clickable(interactionSource = headerTap, indication = null) { onHeaderTap() }
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (tab == 2) {
                    Box {
                        IconButton(onClick = { plusMenu = true }) {
                            PlusIcon(description = str(R.string.playlist_add_cd))
                        }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(str(R.string.playlist_new)) },
                                leadingIcon = { PlusIcon() },
                                onClick = {
                                    plusMenu = false
                                    pendingSong = null
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
                if (update != null && tab != 2) {
                    UpdateButton(onClick = { showUpdate = true })
                }
                IconButton(onClick = { searching = true }) {
                    SearchIcon(description = str(R.string.search))
                }
                IconButton(onClick = onSettings) {
                    SettingsGearIcon(description = str(R.string.settings))
                }
            }
        }
        if (searching) {
            if (query.isBlank()) {
                Text(
                    str(R.string.search_prompt),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (results.isEmpty()) {
                Text(
                    str(R.string.no_results),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                itemsIndexed(results, key = { _, s -> s.id }) { i, s ->
                    SongRow(
                        s, s.id.toString() == currentId,
                        { focusManager.clearFocus(); onPlay(results, i) },
                        { addSong = s },
                        isFavorite = s.id in favSet,
                        modifier = Modifier.animateItem(),
                        onToggleFavorite = { onToggleFavorite(s) }
                    )
                }
            }
        } else {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tabLabels = if (favorites.isNotEmpty()) listOf(str(R.string.tab_tracks), str(R.string.tab_albums), str(R.string.tab_playlists), str(R.string.tab_favorites))
            else listOf(str(R.string.tab_tracks), str(R.string.tab_albums), str(R.string.tab_playlists))
            val chipCtx = LocalContext.current
            tabLabels.forEachIndexed { i, label ->
                // Gedrückt: Der Chip drückt sich ein und federt beim Loslassen mit kleinem Überschwinger zurück
                val chipSource = remember { MutableInteractionSource() }
                val chipPressed by chipSource.collectIsPressedAsState()
                val chipScale = animateFloatAsState(
                    targetValue = if (chipPressed) 0.9f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
                    label = "tabPress"
                )
                FilterChip(
                    modifier = Modifier.graphicsLayer {
                        scaleX = chipScale.value
                        scaleY = chipScale.value
                    },
                    interactionSource = chipSource,
                    selected = effTab == i,
                    onClick = {
                        Haptics.tick(chipCtx)
                        tab = i; openAlbum = null; openPlaylist = null
                    },
                    label = {
                        // Der Favoriten-Tab zeigt nur ein Herz
                        if (i == 3) HeartIcon(iconSize = 20.dp, description = label)
                        else Text(label)
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
            0 -> {
                if (sorted.isEmpty()) {
                    Text(
                        str(R.string.no_songs),
                        modifier = Modifier.padding(16.dp)
                    )
                }
                HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                    itemsIndexed(sorted, key = { _, s -> s.id }) { i, s ->
                        SongRow(
                            s, s.id.toString() == currentId, { onPlay(sorted, i) }, { addSong = s },
                            isFavorite = s.id in favSet,
                            modifier = Modifier.animateItem(),
                            onToggleFavorite = { onToggleFavorite(s) }
                        )
                    }
                }
            }
            1 -> {
                val name = openAlbum
                if (name == null && albumStyle.grid) {
                    HapticLazyGrid(
                        columns = GridCells.Fixed(albumStyle.cols),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomSpace),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        gridItems(albums, key = { it.first }) { (albumName, list) ->
                            GridTile(
                                title = albumName,
                                subtitle = list.first().artist,
                                onClick = { openAlbum = albumName },
                                cover = { w -> Cover(list.first(), w) }
                            )
                        }
                    }
                } else if (name == null) {
                    HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        items(albums, key = { it.first }) { (albumName, list) ->
                            ListItem(
                                headlineContent = {
                                    Text(albumName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        "${list.first().artist} · ${plural(R.plurals.n_tracks, list.size)}",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingContent = { Cover(list.first(), 56.dp) },
                                colors = clearColors(),
                                modifier = Modifier.clickable { openAlbum = albumName }
                            )
                        }
                    }
                } else {
                    val ordered = remember(albums, name) {
                        (albums.firstOrNull { it.first == name }?.second ?: emptyList())
                            .sortedWith(compareBy<Song>({ it.track }, { it.title.lowercase() }))
                    }
                    DetailHeader(name, onBack = { openAlbum = null })
                    HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        itemsIndexed(ordered, key = { _, s -> s.id }) { i, s ->
                            SongRow(
                                s, s.id.toString() == currentId, { onPlay(ordered, i) }, { addSong = s },
                                isFavorite = s.id in favSet,
                                modifier = Modifier.animateItem(),
                                onToggleFavorite = { onToggleFavorite(s) }
                            )
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
                    contentPadding = PaddingValues(bottom = bottomSpace)
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
                val pl = playlists.firstOrNull { it.id == openPlaylist }
                if (pl == null && playlists.isEmpty()) {
                    Text(
                        str(R.string.no_playlists),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pl == null && playlistStyle.grid) {
                    HapticLazyGrid(
                        columns = GridCells.Fixed(playlistStyle.cols),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomSpace),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        gridItems(playlists, key = { it.id }) { p ->
                            val covers = remember(p.songIds, byId) {
                                p.songIds.asSequence().mapNotNull { byId[it] }.take(4).toList()
                            }
                            GridTile(
                                title = p.name,
                                subtitle = plural(R.plurals.n_tracks, p.songIds.size),
                                onClick = { openPlaylist = p.id },
                                onDelete = { deleteTarget = p },
                                cover = { w -> PlaylistCover(covers, w) }
                            )
                        }
                    }
                } else if (pl == null) {
                    HapticLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        items(playlists, key = { it.id }) { p ->
                            ListItem(
                                headlineContent = {
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = { Text(plural(R.plurals.n_tracks, p.songIds.size)) },
                                leadingContent = {
                                    val covers = remember(p.songIds, byId) {
                                        p.songIds.asSequence().mapNotNull { byId[it] }.take(4).toList()
                                    }
                                    PlaylistCover(covers, 56.dp)
                                },
                                trailingContent = {
                                    IconButton(onClick = { deleteTarget = p }) {
                                        TrashIcon(description = str(R.string.delete))
                                    }
                                },
                                colors = clearColors(),
                                modifier = Modifier.clickable { openPlaylist = p.id }
                            )
                        }
                    }
                } else {
                    val list = pl.songIds.mapNotNull { byId[it] }
                    DetailHeader(
                        pl.name,
                        onBack = { openPlaylist = null },
                        // Plus rechts: Lieder hinzufügen (bei leerer Playlist übernimmt das die Taste unter dem Bild)
                        action = if (list.isNotEmpty()) {
                            {
                                IconButton(onClick = { pickFor = pl.id }) {
                                    PlusIcon(description = str(R.string.add_songs))
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
                        contentPadding = PaddingValues(bottom = bottomSpace)
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
        }   // Ende Column (Tab-Inhalt)
        }   // Ende AnimatedContent
        }
    }

    if (showUpdate && update != null) {
        AlertDialog(
            onDismissRequest = { showUpdate = false },
            title = { Text(str(R.string.update_available)) },
            text = { Text(str(R.string.update_text, update.version)) },
            confirmButton = {
                TextButton(onClick = {
                    showUpdate = false
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, str(R.string.toast_link_failed), Toast.LENGTH_LONG).show()
                    }
                }) { Text(str(R.string.download)) }
            },
            dismissButton = { TextButton(onClick = { showUpdate = false }) { Text(str(R.string.later)) } }
        )
    }

    pickFor?.let { plId ->
        val target = playlists.firstOrNull { it.id == plId }
        if (target == null) { pickFor = null } else {
            var chosen by remember(plId) { mutableStateOf(setOf<Long>()) }
            var filter by remember(plId) { mutableStateOf("") }
            val candidates = remember(songs, target.songIds) { songs.filter { it.id !in target.songIds } }
            val shown = remember(candidates, filter) {
                val q = filter.trim()
                if (q.isEmpty()) candidates
                else candidates.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true) ||
                        it.album.contains(q, ignoreCase = true)
                }
            }
            // Eigenes Fenster: gleich viel Rand links und rechts, oben und unten bleibt Platz
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { pickFor = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                // Abstand zum Rand (links/rechts so groß wie unten) und Ecken, die zum Gerät passen:
                // Display-Rundung minus Abstand ergibt gleichmäßig verlaufende (konzentrische) Ecken
                val sideGap = 10.dp
                val ctxForCorner = LocalContext.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val dialogCorner = remember(ctxForCorner) {
                    val px = if (android.os.Build.VERSION.SDK_INT >= 31) {
                        val insets = ctxForCorner.findActivity()?.window?.decorView?.rootWindowInsets
                        listOf(
                            android.view.RoundedCorner.POSITION_TOP_LEFT,
                            android.view.RoundedCorner.POSITION_TOP_RIGHT,
                            android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
                            android.view.RoundedCorner.POSITION_BOTTOM_RIGHT
                        ).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull()
                    } else null
                    // Gerät ohne Angabe: normale Dialog-Rundung
                    val dp = px?.let { with(density) { it.toDp() } - sideGap } ?: 28.dp
                    dp.coerceIn(16.dp, 56.dp)
                }
                val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { pickFor = null },
                    contentAlignment = Alignment.Center
                ) {
                Surface(
                    // fängt Tipps im Fenster selbst ab, damit nur Tipps daneben den Dialog schließen
                    modifier = Modifier.fillMaxWidth().padding(horizontal = sideGap, vertical = 56.dp)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
                    shape = RoundedCornerShape(dialogCorner),
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation
                ) {
                    Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                        Text(
                            str(R.string.add_songs),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        if (shown.isEmpty()) {
                            Text(
                                str(R.string.no_results),
                                modifier = Modifier.weight(1f, fill = false).padding(24.dp)
                            )
                        } else {
                            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                                items(shown, key = { it.id }) { s ->
                                    val on = s.id in chosen
                                    // Gleiche Optik wie die Titelübersicht: bei "Einheitlich" abgerundete, eingerückte Berührungswelle
                                    val rounded = LocalCornerStyle.current == CornerStyle.UNIFORM
                                    val pressSource = remember { MutableInteractionSource() }
                                    val toggle = { chosen = if (on) chosen - s.id else chosen + s.id }
                                    Box(Modifier.fillMaxWidth()) {
                                        if (rounded) {
                                            Box(
                                                Modifier
                                                    .matchParentSize()
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .indication(pressSource, LocalIndication.current)
                                            )
                                        }
                                        ListItem(
                                            headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingContent = { Cover(s, 48.dp) },
                                            trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                                            colors = clearColors(),
                                            modifier = if (rounded) {
                                                Modifier.clip(RoundedCornerShape(20.dp))
                                                    .clickable(interactionSource = pressSource, indication = null) { toggle() }
                                            } else Modifier.clickable { toggle() }
                                        )
                                    }
                                }
                            }
                        }
                        // Suche direkt über den Tasten
                        TextField(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text(str(R.string.search_hint)) },
                            leadingIcon = { SearchIcon() },
                            trailingIcon = {
                                if (filter.isNotEmpty()) {
                                    IconButton(onClick = { filter = "" }) {
                                        CloseIcon(description = str(R.string.clear))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { pickFor = null }) { Text(str(R.string.cancel)) }
                            TextButton(
                                enabled = chosen.isNotEmpty(),
                                onClick = {
                                    // in der Reihenfolge der Bibliothek hinzufügen
                                    val add = candidates.filter { it.id in chosen }.map { it.id }
                                    onPlaylistsChange(
                                        playlists.map { if (it.id == plId) it.copy(songIds = it.songIds + add) else it }
                                    )
                                    pickFor = null
                                }
                            ) { Text(str(R.string.add_n, chosen.size)) }
                        }
                    }
                }
                }   // Ende Box
            }
        }
    }

    addSong?.let { s ->
        AlertDialog(
            onDismissRequest = { addSong = null },
            title = { Text(str(R.string.add_to_playlist)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text(str(R.string.playlist_new_dots)) },
                        leadingContent = { PlusIcon() },
                        colors = clearColors(),
                        modifier = Modifier.clickable {
                            pendingSong = s; addSong = null; creating = true
                        }
                    )
                    playlists.forEach { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            colors = clearColors(),
                            modifier = Modifier.clickable {
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
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addSong = null }) { Text(str(R.string.cancel)) } }
        )
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingSong = null; creating = false },
            title = { Text(str(R.string.playlist_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(str(R.string.name)) }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val p = Playlist(
                            System.currentTimeMillis().toString(),
                            name.trim(),
                            listOfNotNull(pendingSong?.id)
                        )
                        onPlaylistsChange(playlists + p)
                        pendingSong = null
                        creating = false
                    }
                ) { Text(str(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSong = null; creating = false }) { Text(str(R.string.cancel)) }
            }
        )
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(str(R.string.playlist_delete_title)) },
            text = { Text(str(R.string.playlist_delete_text, t.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onPlaylistsChange(playlists.filter { it.id != t.id })
                    deleteTarget = null
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(str(R.string.cancel)) } }
        )
    }
}
