package com.example.musikplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MusikApp(openPlayerSignal: Int = 0) {
    val ctx = LocalContext.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[audioPerm] == true) granted = true
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!granted) add(audioPerm)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
    }

    var allSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selected by remember { mutableStateOf(readFolderSet(ctx)) }
    var sort by remember { mutableStateOf(readSort(ctx)) }
    var playlists by remember { mutableStateOf(readPlaylists(ctx)) }
    var albumStyle by remember { mutableStateOf(readAlbumStyle(ctx)) }
    var playlistStyle by remember { mutableStateOf(readPlaylistStyle(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    var albumRequest by remember { mutableStateOf<String?>(null) }
    var playlistRequest by remember { mutableStateOf<Song?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var rescanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Beim Zurückkehren in die App prüfen, ob sich Dateien (z. B. Tags) geändert haben
    val lifecycleOwner = ctx as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        var skipFirst = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skipFirst) skipFirst = false else refreshTick++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(granted, refreshTick) {
        if (!granted) return@LaunchedEffect
        // erst sofort anzeigen, dann geänderte Dateien neu einlesen lassen und erneut laden
        allSongs = withContext(Dispatchers.IO) { loadLibrary(ctx) }
        if (rescanChanged(ctx, force = false) > 0) {
            allSongs = withContext(Dispatchers.IO) { loadLibrary(ctx) }
        }
    }
    val songs = remember(allSongs, selected) {
        selected?.let { sel -> allSongs.filter { it.folder in sel } } ?: allSongs
    }

    val controller = rememberController()
    var isPlaying by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showNow by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf<MiniOrigin?>(null) }
    var showInfo by remember { mutableStateOf(readShowAudioInfo(ctx)) }
    LaunchedEffect(openPlayerSignal) {
        if (openPlayerSignal > 0) showNow = true
    }

    DisposableEffect(controller) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose {}
        isPlaying = c.isPlaying
        currentId = c.currentMediaItem?.mediaId
        shuffle = c.shuffleModeEnabled
        // gespeicherten Wiederholmodus wiederherstellen
        val savedRepeat = readRepeat(ctx)
        if (c.repeatMode != savedRepeat) c.repeatMode = savedRepeat
        repeat = savedRepeat
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                currentId = item?.mediaId
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode; saveRepeat(ctx, mode) }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }
    val current = allSongs.find { it.id.toString() == currentId }

    CoverTheme(current, isSystemInDarkTheme()) {
        Scaffold(
            bottomBar = {
                if (current != null && controller != null) {
                    MiniPlayer(current, isPlaying, controller) { o -> origin = o; showNow = true }
                }
            }
        ) { pad ->
            if (!granted) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("Bitte erlaube den Zugriff auf deine Musik.")
                }
            } else {
                LibraryScreen(
                    pad = pad,
                    songs = songs,
                    sort = sort,
                    currentId = currentId,
                    playlists = playlists,
                    albumStyle = albumStyle,
                    playlistStyle = playlistStyle,
                    onPlaylistsChange = { playlists = it; savePlaylists(ctx, it) },
                    onPlay = { list, index ->
                        controller?.let { c ->
                            c.setMediaItems(toMediaItems(list), index, 0L)
                            c.prepare()
                            c.play()
                        }
                    },
                    onSettings = { showSettings = true },
                    albumRequest = albumRequest,
                    onAlbumRequestHandled = { albumRequest = null },
                    playlistRequest = playlistRequest,
                    onPlaylistRequestHandled = { playlistRequest = null }
                )
            }
        }

        if (showNow && current != null && controller != null) {
            NowPlayingSheet(
                current, isPlaying, shuffle, repeat, controller,
                showAudioInfo = showInfo,
                origin = origin,
                onGoToAlbum = {
                    albumRequest = current.album.ifBlank { "Unbekanntes Album" }
                    showNow = false
                },
                onAddToPlaylist = { playlistRequest = current }
            ) { showNow = false }
        }
        if (showSettings) {
            AppSettingsSheet(
                allSongs = allSongs,
                selected = selected,
                sort = sort,
                onFolders = { selected = it; saveFolderSet(ctx, it) },
                onSort = { sort = it; saveSort(ctx, it) },
                showAudioInfo = showInfo,
                onShowAudioInfo = { showInfo = it; saveShowAudioInfo(ctx, it) },
                albumStyle = albumStyle,
                onAlbumStyle = { albumStyle = it; saveAlbumStyle(ctx, it) },
                playlistStyle = playlistStyle,
                onPlaylistStyle = { playlistStyle = it; savePlaylistStyle(ctx, it) },
                rescanning = rescanning,
                onRescan = {
                    if (!rescanning) scope.launch {
                        rescanning = true
                        val n = rescanChanged(ctx, force = true)
                        allSongs = withContext(Dispatchers.IO) { loadLibrary(ctx) }
                        rescanning = false
                        Toast.makeText(ctx, "Bibliothek aktualisiert ($n Dateien geprüft)", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SortOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun ViewStyleOptions(style: ViewStyle, onChange: (ViewStyle) -> Unit) {
    SortOption("Liste", "Cover links, Titel daneben", !style.grid) {
        onChange(style.copy(grid = false))
    }
    SortOption("Kacheln", "2 nebeneinander, große Cover", style.grid && style.cols == 2) {
        onChange(ViewStyle(grid = true, cols = 2))
    }
    SortOption("Kacheln", "3 nebeneinander, kompakter", style.grid && style.cols == 3) {
        onChange(ViewStyle(grid = true, cols = 3))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(
    allSongs: List<Song>,
    selected: Set<String>?,
    sort: SortMode,
    onFolders: (Set<String>) -> Unit,
    onSort: (SortMode) -> Unit,
    showAudioInfo: Boolean,
    onShowAudioInfo: (Boolean) -> Unit,
    albumStyle: ViewStyle,
    onAlbumStyle: (ViewStyle) -> Unit,
    playlistStyle: ViewStyle,
    onPlaylistStyle: (ViewStyle) -> Unit,
    rescanning: Boolean,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf("main") }
    val counts = remember(allSongs) {
        allSongs.groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
    }
    val all = counts.map { it.first }.toSet()
    val current = selected ?: all
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        when (page) {
            "main" -> {
                Text(
                    "Einstellungen",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Sortierung") },
                    supportingContent = {
                        Text(if (sort == SortMode.NAME) "Name (A–Z)" else "Datum hinzugefügt (neueste zuerst)")
                    },
                    leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "sort" }
                )
                ListItem(
                    headlineContent = { Text("Musikordner") },
                    supportingContent = {
                        Text(
                            if (selected == null) "Alle Ordner"
                            else "${all.count { it in current }} von ${all.size} Ordnern"
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "folders" }
                )
                ListItem(
                    headlineContent = { Text("Alben-Ansicht") },
                    supportingContent = { Text(albumStyle.label()) },
                    leadingContent = { Icon(Icons.Filled.Album, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "albumView" }
                )
                ListItem(
                    headlineContent = { Text("Playlist-Ansicht") },
                    supportingContent = { Text(playlistStyle.label()) },
                    leadingContent = { Icon(Icons.Filled.GridView, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "playlistView" }
                )
                ListItem(
                    headlineContent = { Text("Bibliothek neu einlesen") },
                    supportingContent = {
                        Text(if (rescanning) "Wird eingelesen …" else "Geänderte Tags und Cover übernehmen")
                    },
                    leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    trailingContent = {
                        if (rescanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { onRescan() }
                )
                ListItem(
                    headlineContent = { Text("Audioinfos im Player") },
                    supportingContent = { Text("Format, Bitrate und Abtastrate unten anzeigen") },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    trailingContent = {
                        Switch(checked = showAudioInfo, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { onShowAudioInfo(!showAudioInfo) }
                )
                Spacer(Modifier.height(32.dp))
            }
            "sort" -> {
                SheetHeader("Sortierung") { page = "main" }
                SortOption("Datum hinzugefügt", "Neueste zuerst", sort == SortMode.DATE) {
                    onSort(SortMode.DATE)
                }
                SortOption("Name", "A bis Z", sort == SortMode.NAME) {
                    onSort(SortMode.NAME)
                }
                Spacer(Modifier.height(32.dp))
            }
            "albumView" -> {
                SheetHeader("Alben-Ansicht") { page = "main" }
                ViewStyleOptions(albumStyle, onAlbumStyle)
                Spacer(Modifier.height(32.dp))
            }
            "playlistView" -> {
                SheetHeader("Playlist-Ansicht") { page = "main" }
                ViewStyleOptions(playlistStyle, onPlaylistStyle)
                Spacer(Modifier.height(32.dp))
            }
            else -> {
                SheetHeader("Musikordner") { page = "main" }
                Text(
                    "Nur Songs aus angehakten Ordnern werden angezeigt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Row(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { onFolders(all) }) { Text("Alle") }
                    TextButton(onClick = { onFolders(emptySet()) }) { Text("Keine") }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 32.dp)
                ) {
                    counts.forEach { (folder, n) ->
                        ListItem(
                            headlineContent = {
                                Text(folder.trimEnd('/').ifEmpty { "Hauptverzeichnis" })
                            },
                            supportingContent = { Text("$n Songs") },
                            trailingContent = {
                                Checkbox(checked = folder in current, onCheckedChange = null)
                            },
                            colors = clear,
                            modifier = Modifier.clickable {
                                onFolders(if (folder in current) current - folder else current + folder)
                            }
                        )
                    }
                }
            }
        }
    }
}
