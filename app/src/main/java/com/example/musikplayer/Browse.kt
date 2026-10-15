package com.example.musikplayer

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
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
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            first.isEmpty() -> Icon(
                Icons.Filled.MusicNote, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                                        Icon(
                                            Icons.Filled.MusicNote, contentDescription = null,
                                            modifier = Modifier.size(half * 0.5f),
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }
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
                        Icon(
                            Icons.Filled.Delete, contentDescription = "Löschen",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Cover(song, 48.dp) },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Titel-Infos") },
                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        onClick = { expanded = false; showInfo = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Tags bearbeiten") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { expanded = false; showTags = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Zur Playlist hinzufügen") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                        },
                        onClick = { expanded = false; onAddToPlaylist() }
                    )
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("Aus Playlist entfernen") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { expanded = false; onRemove() }
                        )
                    }
                }
                if (showInfo) SongInfoDialog(song) { showInfo = false }
                if (showTags) TagEditDialog(song) { showTags = false }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
            else Color.Transparent
        ),
        modifier = Modifier.clickable { onClick() }
    )
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
    headerLogo: Boolean = false,
    update: UpdateInfo? = null,
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
                Toast.makeText(ctx, "Keine passenden Titel in der Playlist gefunden", Toast.LENGTH_LONG).show()
            } else {
                onPlaylistsChange(
                    playlists + Playlist(System.currentTimeMillis().toString(), r.name, r.ids)
                )
                Toast.makeText(
                    ctx, "${r.name}: ${r.ids.size} von ${r.total} Titeln importiert", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val sorted = remember(songs, sort) { sortSongs(songs, sort) }
    val albums = remember(songs) {
        songs.groupBy { it.album.ifBlank { "Unbekanntes Album" } }
            .toList().sortedBy { it.first.lowercase() }
    }
    val byId = remember(songs) { songs.associateBy { it.id } }

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
            Toast.makeText(ctx, "Album nicht gefunden", Toast.LENGTH_SHORT).show()
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Suche schließen")
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                    placeholder = { Text("Titel, Künstler oder Album") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Leeren")
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
                if (headerLogo) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Image(
                            painter = painterResource(R.drawable.title_logo),
                            contentDescription = "ChibiNeko",
                            // Höhe des Logos hier ändern; die Breite folgt dem Seitenverhältnis (362:68)
                            modifier = Modifier.height(36.dp).aspectRatio(362f / 68f),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
                        )
                    }
                } else {
                    Text(
                        "Musik",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (tab == 2) {
                    Box {
                        IconButton(onClick = { plusMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Playlist hinzufügen")
                        }
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Neue Playlist") },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = {
                                    plusMenu = false
                                    pendingSong = null
                                    creating = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("M3U-Playlist importieren") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = {
                                    plusMenu = false
                                    m3uLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                }
                if (update != null && tab != 2) {
                    IconButton(onClick = { showUpdate = true }) {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = "Update verfügbar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = { searching = true }) {
                    Icon(Icons.Filled.Search, contentDescription = "Suchen")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                }
            }
        }
        if (searching) {
            if (query.isBlank()) {
                Text(
                    "Suche nach Titel, Künstler oder Album.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (results.isEmpty()) {
                Text(
                    "Keine Treffer.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                itemsIndexed(results, key = { _, s -> s.id }) { i, s ->
                    SongRow(
                        s, s.id.toString() == currentId,
                        { focusManager.clearFocus(); onPlay(results, i) },
                        { addSong = s }
                    )
                }
            }
        } else {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Titel", "Album", "Playlists").forEachIndexed { i, label ->
                FilterChip(
                    selected = tab == i,
                    onClick = { tab = i; openAlbum = null; openPlaylist = null },
                    label = { Text(label) }
                )
            }
        }

        when (tab) {
            0 -> {
                if (sorted.isEmpty()) {
                    Text(
                        "Keine Songs gefunden. Prüfe die Musikordner in den Einstellungen.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                    itemsIndexed(sorted, key = { _, s -> s.id }) { i, s ->
                        SongRow(s, s.id.toString() == currentId, { onPlay(sorted, i) }, { addSong = s })
                    }
                }
            }
            1 -> {
                val name = openAlbum
                if (name == null && albumStyle.grid) {
                    LazyVerticalGrid(
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
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        items(albums, key = { it.first }) { (albumName, list) ->
                            ListItem(
                                headlineContent = {
                                    Text(albumName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        "${list.first().artist} · ${list.size} Titel",
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
                    DetailHeader(name) { openAlbum = null }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        itemsIndexed(ordered, key = { _, s -> s.id }) { i, s ->
                            SongRow(s, s.id.toString() == currentId, { onPlay(ordered, i) }, { addSong = s })
                        }
                    }
                }
            }
            else -> {
                val pl = playlists.firstOrNull { it.id == openPlaylist }
                if (pl == null && playlists.isEmpty()) {
                    Text(
                        "Noch keine Playlists. Tippe oben auf +, um eine zu erstellen oder zu importieren.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pl == null && playlistStyle.grid) {
                    LazyVerticalGrid(
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
                                subtitle = "${p.songIds.size} Titel",
                                onClick = { openPlaylist = p.id },
                                onDelete = { deleteTarget = p },
                                cover = { w -> PlaylistCover(covers, w) }
                            )
                        }
                    }
                } else if (pl == null) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        items(playlists, key = { it.id }) { p ->
                            ListItem(
                                headlineContent = {
                                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = { Text("${p.songIds.size} Titel") },
                                leadingContent = {
                                    val covers = remember(p.songIds, byId) {
                                        p.songIds.asSequence().mapNotNull { byId[it] }.take(4).toList()
                                    }
                                    PlaylistCover(covers, 56.dp)
                                },
                                trailingContent = {
                                    IconButton(onClick = { deleteTarget = p }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                                    }
                                },
                                colors = clearColors(),
                                modifier = Modifier.clickable { openPlaylist = p.id }
                            )
                        }
                    }
                } else {
                    val list = pl.songIds.mapNotNull { byId[it] }
                    DetailHeader(pl.name) { openPlaylist = null }
                    if (list.isEmpty()) {
                        Text(
                            "Noch keine Titel. Füge Songs über das Menü (⋮) hinzu.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomSpace)) {
                        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                            SongRow(
                                s, s.id.toString() == currentId,
                                onClick = { onPlay(list, i) },
                                onAddToPlaylist = { addSong = s },
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
    }

    if (showUpdate && update != null) {
        AlertDialog(
            onDismissRequest = { showUpdate = false },
            title = { Text("Update verfügbar") },
            text = { Text("Version ${update.version} ist auf GitHub veröffentlicht.") },
            confirmButton = {
                TextButton(onClick = {
                    showUpdate = false
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Link konnte nicht geöffnet werden", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Herunterladen") }
            },
            dismissButton = { TextButton(onClick = { showUpdate = false }) { Text("Später") } }
        )
    }

    addSong?.let { s ->
        AlertDialog(
            onDismissRequest = { addSong = null },
            title = { Text("Zur Playlist hinzufügen") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text("Neue Playlist…") },
                        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
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
            dismissButton = { TextButton(onClick = { addSong = null }) { Text("Abbrechen") } }
        )
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingSong = null; creating = false },
            title = { Text("Neue Playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") }
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
                ) { Text("Erstellen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSong = null; creating = false }) { Text("Abbrechen") }
            }
        )
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Playlist löschen?") },
            text = { Text("${t.name} wird gelöscht. Die Songs bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    onPlaylistsChange(playlists.filter { it.id != t.id })
                    deleteTarget = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Abbrechen") } }
        )
    }
}
