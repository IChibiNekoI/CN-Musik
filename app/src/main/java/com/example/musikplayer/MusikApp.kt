package com.example.musikplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
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
    var favorites by remember { mutableStateOf(readFavorites(ctx)) }
    val toggleFavorite: (Song) -> Unit = { s ->
        favorites = if (s.id in favorites) favorites - s.id else listOf(s.id) + favorites
        saveFavorites(ctx, favorites)
    }
    var headerLogo by remember { mutableStateOf(readHeaderLogo(ctx)) }
    Haptics.load(ctx)
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
    var startupDone by remember { mutableStateOf(false) }
    LaunchedEffect(granted, refreshTick, TagEvents.tick) {
        if (!granted) return@LaunchedEffect
        // Die rohen Tags aus der Android-Datenbank werden nie angezeigt, sonst springen sie später um:
        // erst sofort mit den gespeicherten Datei-Tags anzeigen (liest keine Dateien),
        // dann geänderte Dateien nachlesen.
        suspend fun showWithFileTags(b: List<Song>) {
            allSongs = refreshTagsFromFiles(ctx, b, force = false, cacheOnly = true)
            allSongs = refreshTagsFromFiles(ctx, b, force = false)
        }
        var base = withContext(Dispatchers.IO) { loadLibrary(ctx) }
        showWithFileTags(base)
        if (!startupDone) {
            // Die aufwendigen Prüfungen erst, wenn die Oberfläche steht und die ersten Cover geladen sind.
            // So ruckelt der Start auf schwächeren Geräten nicht.
            delay(2500)
            startupDone = true
            withContext(Dispatchers.IO) { trimCoverDiskCache(ctx) }
        }
        if (rescanChanged(ctx, force = false) > 0) {
            base = withContext(Dispatchers.IO) { loadLibrary(ctx) }
            showWithFileTags(base)
        }
    }
    val songs = remember(allSongs, selected) {
        selected?.let { sel -> allSongs.filter { it.folder in sel } } ?: allSongs
    }

    val controller = rememberController()
    val controllerNow by rememberUpdatedState(controller)
    // Beim Verlassen der App Titel und Position merken
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controllerNow?.let { saveQueueState(ctx, it) }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showNow by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf<MiniOrigin?>(null) }
    // Player per Hochziehen des Miniplayers öffnen: der Finger steuert den Fortschritt (0 = Miniplayer, 1 = Vollbild)
    val openProgress = remember { Animatable(0f) }
    var gestureOpen by remember { mutableStateOf(false) }
    var pendingOrigin by remember { mutableStateOf<MiniOrigin?>(null) }
    val openDistance = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() * 0.4f
    }
    var showInfo by remember { mutableStateOf(readShowAudioInfo(ctx)) }
    // beim Start einmal bei GitHub nachfragen, ob ein neueres Release existiert
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        delay(6000)   // erst nach dem Start, das Netzwerk soll die Oberfläche nicht bremsen
        update = withContext(Dispatchers.IO) { checkForUpdate(ctx) }
    }
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
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) saveQueueState(ctx, c, full = false)
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                currentId = item?.mediaId
                // ganze Warteschlange nur speichern, wenn sie neu ist; sonst reicht die Position
                saveQueueState(ctx, c, full = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode; saveRepeat(ctx, mode) }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }
    // Beim Abspielen alle 15 Sekunden die Position sichern (falls die App im Hintergrund beendet wird)
    LaunchedEffect(controller, isPlaying) {
        val c = controller ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            delay(15_000)
            saveQueueState(ctx, c, full = false)
        }
    }
    // Zuletzt gespielte Warteschlange wiederherstellen: pausiert, nur Play drücken
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(controller, allSongs) {
        val c = controller ?: return@LaunchedEffect
        if (restored || allSongs.isEmpty()) return@LaunchedEffect
        restored = true
        // läuft noch etwas im Hintergrund-Dienst, nichts überschreiben
        if (c.mediaItemCount > 0) return@LaunchedEffect
        val saved = readQueueState(ctx) ?: return@LaunchedEffect
        val byId = allSongs.associateBy { it.id }
        val savedCurrent = saved.ids.getOrNull(saved.index)
        val list = saved.ids.mapNotNull { byId[it] }
        if (list.isEmpty()) return@LaunchedEffect
        val idx = list.indexOfFirst { it.id == savedCurrent }.coerceAtLeast(0)
        val pos = if (list[idx].id == savedCurrent) saved.positionMs else 0L
        // MediaItems für eine lange Warteschlange nicht auf dem Haupt-Thread bauen
        val items = withContext(Dispatchers.Default) { toMediaItems(list) }
        c.setMediaItems(items, idx, pos)
        c.prepare()
        currentId = list[idx].id.toString()
    }
    val current = allSongs.find { it.id.toString() == currentId }
    // Verschwommenes Cover für Menüs, Dialoge und Einstellungen (nur bei Unschärfe mit eingeschaltetem Menü-Design)
    LaunchedEffect(
        current?.id, CornerSettings.playerBg, CornerSettings.menuUnified,
        current?.let { TagEvents.coverVersions[it.id] }
    ) {
        val sg = current
        MenuBackdrop.bitmap =
            if (sg != null && CornerSettings.playerBg == PlayerBackground.BLUR && CornerSettings.menuUnified)
                loadBlurredBackdrop(ctx, sg)
            else null
    }

    CoverTheme(current, isSystemInDarkTheme()) {
        val miniVisible = current != null && controller != null
        Box(Modifier.fillMaxSize()) {
        // Bei "Menü-Design einheitlich" (und Unschärfe) liegt das verschwommene Cover hinter der Hauptseite
        LibraryBackdrop()
        Scaffold(
            containerColor = if (MenuBackdrop.active) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background
        ) { pad ->
            val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
            // Nur oben Abstand lassen: unten laufen die Listen hinter Miniplayer und Navigationsleiste durch
            val listPad = PaddingValues(
                start = pad.calculateStartPadding(layoutDir),
                end = pad.calculateEndPadding(layoutDir),
                top = pad.calculateTopPadding()
            )
            // Miniplayer (ca. 80 dp) + Außenabstand 12 dp + etwas Luft + Navigationsleiste
            val bottomSpace = pad.calculateBottomPadding() + if (miniVisible) 100.dp else 0.dp
            if (!granted) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text("Bitte erlaube den Zugriff auf deine Musik.")
                }
            } else {
                LibraryScreen(
                    pad = listPad,
                    bottomSpace = bottomSpace,
                    songs = songs,
                    sort = sort,
                    currentId = currentId,
                    playlists = playlists,
                    albumStyle = albumStyle,
                    playlistStyle = playlistStyle,
                    headerLogo = headerLogo,
                    update = update,
                    favorites = favorites,
                    onToggleFavorite = toggleFavorite,
                    onReorderFavorites = { favorites = it; saveFavorites(ctx, it) },
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
        if (current != null && controller != null) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                // Der Miniplayer schiebt sich beim ersten Mal sanft von unten herein
                    val miniEnter = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = miniEnter,
                        enter = slideInVertically(tween(Motion.MEDIUM, easing = Motion.Ease)) { it / 2 } +
                            fadeIn(tween(Motion.MEDIUM))
                    ) {
                        MiniPlayer(
                            current, isPlaying, controller,
                            onDragStart = { o -> pendingOrigin = o },
                            onDrag = { total ->
                                val p = (-total / openDistance).coerceIn(0f, 1f)
                                // erst bei echter Aufwärtsbewegung den Player einblenden
                                if (!gestureOpen && total < -12f) {
                                    origin = pendingOrigin
                                    gestureOpen = true
                                    showNow = true
                                    scope.launch { openProgress.snapTo(0f) }
                                }
                                if (gestureOpen) scope.launch { openProgress.snapTo(p) }
                            },
                            onDragEnd = { total, velocity ->
                                if (gestureOpen) {
                                    val p = (-total / openDistance).coerceIn(0f, 1f)
                                    val opens = p > 0.35f || velocity < -900f
                                    scope.launch {
                                        if (opens) {
                                            openProgress.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.Ease))
                                        } else {
                                            // nicht weit genug gezogen: wieder zurück zum Miniplayer
                                            openProgress.animateTo(0f, tween(Motion.MEDIUM, easing = Motion.Ease))
                                            showNow = false
                                            gestureOpen = false
                                        }
                                    }
                                }
                            }
                        ) { o -> gestureOpen = false; origin = o; showNow = true }
                    }
            }
        }
        }

        if (showNow && current != null && controller != null) {
            NowPlayingSheet(
                current, isPlaying, shuffle, repeat, controller,
                showAudioInfo = showInfo,
                origin = origin,
                controlledOpen = if (gestureOpen) openProgress else null,
                isFavorite = current.id in favorites,
                onToggleFavorite = { toggleFavorite(current) },
                onGoToAlbum = {
                    albumRequest = current.album.ifBlank { "Unbekanntes Album" }
                    showNow = false
                },
                onAddToPlaylist = { playlistRequest = current }
            ) { showNow = false; gestureOpen = false }
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
                headerLogo = headerLogo,
                onHeaderLogo = { headerLogo = it; saveHeaderLogo(ctx, it) },
                cornerStyle = CornerSettings.style,
                onCornerStyle = { CornerSettings.style = it; saveCornerStyle(ctx, it) },
                playerBg = CornerSettings.playerBg,
                onPlayerBg = { CornerSettings.playerBg = it; savePlayerBg(ctx, it) },
                menuUnified = CornerSettings.menuUnified,
                onMenuUnified = { CornerSettings.menuUnified = it; saveMenuUnified(ctx, it) },
                haptics = Haptics.enabled,
                onHaptics = { Haptics.setEnabled(ctx, it); if (it) Haptics.tick(ctx) },
                rescanning = rescanning,
                onRescan = {
                    if (!rescanning) scope.launch {
                        rescanning = true
                        val n = rescanChanged(ctx, force = true)
                        val base = withContext(Dispatchers.IO) { loadLibrary(ctx) }
                        allSongs = refreshTagsFromFiles(ctx, base, force = true)
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
            BackIcon(description = "Zurück")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SortOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
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
    headerLogo: Boolean,
    onHeaderLogo: (Boolean) -> Unit,
    cornerStyle: CornerStyle,
    onCornerStyle: (CornerStyle) -> Unit,
    playerBg: PlayerBackground,
    onPlayerBg: (PlayerBackground) -> Unit,
    menuUnified: Boolean,
    onMenuUnified: (Boolean) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
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
        // Beim Wechsel zwischen den Seiten gleiten Inhalte weich hinein und heraus, die Höhe des Menüs passt sich an
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val ease = Motion.Ease
                if (targetState == "main" || (targetState == "index" && initialState == "folders")) {
                    // zurück: neue Seite kommt von links
                    (slideInHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> -w / 5 } +
                        fadeIn(tween(Motion.MEDIUM - 60, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> w / 5 } +
                            fadeOut(tween(Motion.FAST))) using
                        SizeTransform(clip = true) { _, _ -> tween(Motion.MEDIUM, easing = ease) }
                } else {
                    // weiter: neue Seite kommt von rechts
                    (slideInHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> w / 5 } +
                        fadeIn(tween(Motion.MEDIUM - 60, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> -w / 5 } +
                            fadeOut(tween(Motion.FAST))) using
                        SizeTransform(clip = true) { _, _ -> tween(Motion.MEDIUM, easing = ease) }
                }
            },
            label = "settingsPage"
        ) { p ->
        Column {
        when (p) {
            "main" -> {
                Text(
                    "Einstellungen",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Sortierung") },
                    supportingContent = {
                        Text(
                            when (sort) {
                                SortMode.NAME -> "Name (A–Z)"
                                SortMode.MODIFIED -> "Datum modifiziert (neueste zuerst)"
                                SortMode.DATE -> "Datum hinzugefügt (neueste zuerst)"
                            }
                        )
                    },
                    leadingContent = { CalendarIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "sort" }
                )
                ListItem(
                    headlineContent = { Text("Index") },
                    leadingContent = { HashIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "index" }
                )
                ListItem(
                    headlineContent = { Text("Alben-Ansicht") },
                    supportingContent = { Text(albumStyle.label()) },
                    leadingContent = { AlbumDiscIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "albumView" }
                )
                ListItem(
                    headlineContent = { Text("Playlist-Ansicht") },
                    supportingContent = { Text(playlistStyle.label()) },
                    leadingContent = { GridIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "playlistView" }
                )
                ListItem(
                    headlineContent = { Text("Design") },
                    leadingContent = { CornersIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "design" }
                )
                ListItem(
                    headlineContent = { Text("Kopfzeile") },
                    supportingContent = { Text(if (headerLogo) "ChibiNeko-Logo" else "Standard (Text „Musik“)") },
                    leadingContent = { TitleTIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "headerView" }
                )
                ListItem(
                    headlineContent = { Text("Audioinfos im Player") },
                    leadingContent = { InfoIcon() },
                    trailingContent = {
                        Switch(checked = showAudioInfo, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { onShowAudioInfo(!showAudioInfo) }
                )
                ListItem(
                    headlineContent = { Text("Haptisches Feedback") },
                    leadingContent = { VibrationIcon() },
                    trailingContent = {
                        Switch(checked = haptics, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { onHaptics(!haptics) }
                )
                Spacer(Modifier.height(32.dp))
            }
            "index" -> {
                SheetHeader("Index") { page = "main" }
                ListItem(
                    headlineContent = { Text("Musikordner") },
                    supportingContent = {
                        Text(
                            if (selected == null) "Alle Ordner"
                            else "${all.count { it in current }} von ${all.size} Ordnern"
                        )
                    },
                    leadingContent = { FolderIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.clickable { page = "folders" }
                )
                ListItem(
                    headlineContent = { Text("Bibliothek neu einlesen") },
                    supportingContent = {
                        Text(if (rescanning) "Wird eingelesen …" else "Geänderte Tags und Cover übernehmen")
                    },
                    leadingContent = { RefreshIcon() },
                    trailingContent = {
                        if (rescanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    },
                    colors = clear,
                    modifier = Modifier.clickable { onRescan() }
                )
                Spacer(Modifier.height(32.dp))
            }
            "sort" -> {
                SheetHeader("Sortierung") { page = "main" }
                SortOption("Datum hinzugefügt", "Neueste zuerst", sort == SortMode.DATE) {
                    onSort(SortMode.DATE)
                }
                SortOption("Datum modifiziert", "Zuletzt geändert zuerst", sort == SortMode.MODIFIED) {
                    onSort(SortMode.MODIFIED)
                }
                SortOption("Name", "A bis Z", sort == SortMode.NAME) {
                    onSort(SortMode.NAME)
                }
                Spacer(Modifier.height(32.dp))
            }
            "headerView" -> {
                SheetHeader("Kopfzeile") { page = "main" }
                SortOption("Standard", "Text „Musik“", !headerLogo) { onHeaderLogo(false) }
                SortOption("ChibiNeko-Logo", "Bild statt Text", headerLogo) { onHeaderLogo(true) }
                Spacer(Modifier.height(32.dp))
            }
            "design" -> {
                SheetHeader("Design") { page = "main" }
                Text(
                    "Rundungen",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                SortOption("Standard", null, cornerStyle == CornerStyle.STANDARD) {
                    onCornerStyle(CornerStyle.STANDARD)
                }
                SortOption(
                    "Einheitlich",
                    "Chips, Menüs, Felder und Dialoge runden im gleichen Stil",
                    cornerStyle == CornerStyle.UNIFORM
                ) { onCornerStyle(CornerStyle.UNIFORM) }
                Text(
                    "Player-Hintergrund",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                )
                SortOption("Einfarbig", "Wie bisher", playerBg == PlayerBackground.SOLID) {
                    onPlayerBg(PlayerBackground.SOLID)
                }
                SortOption(
                    "Farbverlauf",
                    "Oben die Farbe des Covers, nach unten weich auslaufend",
                    playerBg == PlayerBackground.GRADIENT
                ) { onPlayerBg(PlayerBackground.GRADIENT) }
                SortOption(
                    "Unschärfe",
                    "Das Cover, sehr stark verschwommen, als Hintergrund",
                    playerBg == PlayerBackground.BLUR
                ) { onPlayerBg(PlayerBackground.BLUR) }
                // Kippschalter: Menüs, Dialoge und Einstellungen wie der Player (nur bei Unschärfe)
                val menuSwitchEnabled = playerBg == PlayerBackground.BLUR
                ListItem(
                    headlineContent = { Text("Menü-Design einheitlich") },
                    supportingContent = {
                        Text(
                            if (menuSwitchEnabled) "Die Hauptseite (Titel, Album, Playlists, Favoriten) bekommt den Hintergrund des Players"
                            else "Nur bei Unschärfe verfügbar"
                        )
                    },
                    trailingContent = {
                        Switch(checked = menuUnified, onCheckedChange = null, enabled = menuSwitchEnabled)
                    },
                    colors = clear,
                    modifier = Modifier.clickable(enabled = menuSwitchEnabled) { onMenuUnified(!menuUnified) }
                )
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
                SheetHeader("Musikordner") { page = "index" }
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
        }   // Ende Column
        }   // Ende AnimatedContent
    }
}
