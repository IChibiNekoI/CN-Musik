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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stärke des Miniplayer-Bounces beim Landen des Players (Anteil der Höhe). Das ist die Obergrenze, die bei schnellem Schließen erreicht wird. */
private const val MINI_BOUNCE_MAX = 0.035f

/** Anteil von [MINI_BOUNCE_MAX], der auch bei ganz langsamem Schließen bleibt (0 = dann gar kein Bounce) */
private const val MINI_BOUNCE_MIN_SHARE = 0.2f

/** Schließ-Geschwindigkeit (Fortschritt pro Sekunde): bis hier gilt es als langsam, ab [CLOSE_SPEED_FAST] volle Stärke. Die Zurück-Taste liegt bei etwa 6,5. */
private const val CLOSE_SPEED_SLOW = 2f
private const val CLOSE_SPEED_FAST = 9f

@Composable
fun MusikApp(
    openPlayerSignal: Int = 0,
    viewUri: android.net.Uri? = null,
    onViewUriHandled: () -> Unit = {}
) {
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
    var hidden by remember { mutableStateOf(readHiddenSongs(ctx)) }
    val hideSong: (Song) -> Unit = { s ->
        hidden = hidden + s.id
        saveHiddenSongs(ctx, hidden)
        Toast.makeText(ctx, str(R.string.track_hidden), Toast.LENGTH_SHORT).show()
    }
    val unhideSong: (Song) -> Unit = { s ->
        hidden = hidden - s.id
        saveHiddenSongs(ctx, hidden)
    }
    var headerStyle by remember { mutableStateOf(readHeaderStyle(ctx)) }
    var foxUnlocked by remember { mutableStateOf(readFoxUnlocked(ctx)) }
    // Easter Egg: 50 Tipps auf die Kopfzeile hintereinander (Pause zwischen zwei Tipps höchstens 2 Sekunden)
    var headerTaps by remember { mutableStateOf(0) }
    var lastHeaderTap by remember { mutableStateOf(0L) }
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
    // Erst wenn die Bibliothek einmal geladen ist, darf "Keine Songs gefunden" erscheinen (nicht schon beim Start)
    var libraryLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(granted, refreshTick, TagEvents.tick) {
        if (!granted) return@LaunchedEffect
        // Die rohen Tags aus der Android-Datenbank werden nie angezeigt, sonst springen sie später um:
        // erst sofort mit den gespeicherten Datei-Tags anzeigen (liest keine Dateien),
        // dann geänderte Dateien nachlesen.
        suspend fun showWithFileTags(b: List<Song>) {
            allSongs = refreshTagsFromFiles(ctx, b, force = false, cacheOnly = true)
            if (allSongs.isNotEmpty()) libraryLoaded = true
            allSongs = refreshTagsFromFiles(ctx, b, force = false)
            libraryLoaded = true
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
    // Ausgeblendete Titel fehlen nur im Titel-Tab, sie stehen unter Einstellungen > Ausgeblendete Titel
    val hiddenSongs = remember(allSongs, hidden) {
        allSongs.filter { it.id in hidden }.sortedBy { it.title.lowercase() }
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
    // zuletzt bekannte Position des Miniplayers: Ziel beim Schließen, auch wenn der Player über die Benachrichtigung geöffnet wurde
    var lastOrigin by remember { mutableStateOf<MiniOrigin?>(null) }
    // Laufend gemessene Position des Miniplayers (schon vor dem ersten Antippen bekannt): Ziel beim Schließen,
    // auch wenn der Player schnell wieder geschlossen wird, bevor die Position beim Öffnen sicher gemessen war
    var liveOrigin by remember { mutableStateOf<MiniOrigin?>(null) }
    // Ein Antrieb für Öffnen und Schließen des Players (0 = Miniplayer, 1 = Vollbild):
    // Finger, Loslassen mit Geschwindigkeit, Zurück-Taste und Zurück-Geste, alles unterbrechbar
    val sheetMotion = remember { SheetMotion() }
    LaunchedEffect(sheetMotion) {
        sheetMotion.onLanded = { Haptics.snap(ctx) }   // Einrasten des Players spürbar machen
        sheetMotion.run()
    }
    // Landung des Players im Miniplayer: kurzes, leichtes Eindrücken mit Nachfedern (0 = Ruhe)
    val miniBounce = remember { Animatable(0f) }
    // Ist der Player weg (egal wie), steht der Fortschritt wieder auf Miniplayer
    LaunchedEffect(showNow) { if (!showNow) sheetMotion.snapTo(0f) }
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
        if (openPlayerSignal > 0) {
            if (!showNow) {
                // z. B. Start über die Benachrichtigung: kein Miniplayer als Ursprung, der Player steht sofort
                origin = lastOrigin ?: liveOrigin
                gestureOpen = false
                sheetMotion.snapTo(1f)
            }
            showNow = true
        }
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
    // Von außen geöffnete Datei ("Öffnen mit"): wird nach dem Laden der Bibliothek abgespielt
    var externalStart by remember { mutableStateOf(viewUri != null) }
    var externalSong by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(viewUri, controller) {
        val uri = viewUri ?: return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        externalStart = true
        // kurz auf die Bibliothek warten, damit die Datei ihrem Titel dort zugeordnet werden kann
        kotlinx.coroutines.withTimeoutOrNull(3000) {
            androidx.compose.runtime.snapshotFlow { allSongs.isNotEmpty() }.first { it }
        }
        val song = resolveExternalSong(ctx, uri, allSongs)
        if (allSongs.none { it.id == song.id }) externalSong = song
        SkipDirection.dir = 1
        c.setMediaItems(toMediaItems(listOf(song)), 0, 0L)
        c.prepare()
        c.play()
        currentId = song.id.toString()
        onViewUriHandled()
    }
    // Zuletzt gespielte Warteschlange wiederherstellen: pausiert, nur Play drücken
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(controller, allSongs) {
        val c = controller ?: return@LaunchedEffect
        if (restored || allSongs.isEmpty()) return@LaunchedEffect
        // Wurde eine Datei von außen geöffnet, ersetzt sie die zuletzt gespielte Warteschlange
        if (externalStart) return@LaunchedEffect
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
        ?: externalSong?.takeIf { it.id.toString() == currentId }
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
                    Text(str(R.string.permission_needed))
                }
            } else {
                LibraryScreen(
                    loaded = libraryLoaded,
                    pad = listPad,
                    bottomSpace = bottomSpace,
                    songs = songs,
                    sort = sort,
                    currentId = currentId,
                    playlists = playlists,
                    albumStyle = albumStyle,
                    playlistStyle = playlistStyle,
                    headerStyle = headerStyle,
                    onHeaderTap = {
                        val now = android.os.SystemClock.elapsedRealtime()
                        headerTaps = if (now - lastHeaderTap > 2000) 1 else headerTaps + 1
                        lastHeaderTap = now
                        if (headerTaps >= 50 && !foxUnlocked) {
                            foxUnlocked = true
                            saveFoxUnlocked(ctx)
                            Haptics.snap(ctx)
                            Toast.makeText(ctx, str(R.string.fox_unlocked), Toast.LENGTH_LONG).show()
                        }
                    },
                    update = update,
                    favorites = favorites,
                    onToggleFavorite = toggleFavorite,
                    onHideSong = hideSong,
                    hiddenIds = hidden,
                    onReorderFavorites = { favorites = it; saveFavorites(ctx, it) },
                    onPlaylistsChange = { playlists = it; savePlaylists(ctx, it) },
                    onPlay = { list, index ->
                        controller?.let { c ->
                            SkipDirection.dir = 1
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
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        // Sehr leicht gestaucht und minimal breiter, wie ein weiches Auftreffen
                        val k = miniBounce.value
                        scaleY = 1f - k
                        scaleX = 1f + k * 0.35f
                    }
            ) {
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
                            onOriginChanged = { if (it != liveOrigin) liveOrigin = it },
                            onDrag = { total ->
                                val p = (-total / openDistance).coerceIn(0f, 1f)
                                // erst bei echter Aufwärtsbewegung den Player einblenden
                                if (!gestureOpen && total < -12f) {
                                    origin = pendingOrigin
                                    pendingOrigin?.let { lastOrigin = it }
                                    gestureOpen = true
                                    showNow = true
                                }
                                if (gestureOpen) sheetMotion.follow(p)
                            },
                            onDragEnd = { total, velocity ->
                                if (gestureOpen) {
                                    val p = (-total / openDistance).coerceIn(0f, 1f)
                                    // Fingergeschwindigkeit (px/s, nach oben negativ) in Fortschritt pro Sekunde umrechnen
                                    val v = -velocity / openDistance
                                    // Wohin führt die Bewegung in Kürze? Danach entscheidet sich auf oder zu.
                                    val opens = p + v * SHEET_PROJECTION_S > 0.35f
                                    if (opens) {
                                        sheetMotion.settle(1f, v, p)
                                    } else {
                                        // nicht weit genug gezogen: mit der Fingergeschwindigkeit zurück zum Miniplayer
                                        sheetMotion.settle(0f, v, p) { showNow = false; gestureOpen = false }
                                    }
                                }
                            }
                        ) { o -> gestureOpen = false; origin = o; o?.let { lastOrigin = it }; scope.launch { sheetMotion.snapTo(0f); showNow = true } }
                    }
            }
        }
        }

        if (showNow && current != null && controller != null) {
            NowPlayingSheet(
                current, isPlaying, shuffle, repeat, controller,
                library = allSongs,
                showAudioInfo = showInfo,
                origin = liveOrigin ?: origin,
                motion = sheetMotion,
                fingerDriven = gestureOpen,
                isFavorite = current.id in favorites,
                onToggleFavorite = { toggleFavorite(current) },
                onGoToAlbum = {
                    albumRequest = current.album.ifBlank { str(R.string.unknown_album) }
                    showNow = false
                },
                onAddToPlaylist = { playlistRequest = current }
            ) {
                // Landet der Player im Miniplayer (statt nur wegzurutschen), federt der Miniplayer weich nach
                val landed = (liveOrigin ?: origin) != null
                showNow = false
                gestureOpen = false
                // Stärke hängt davon ab, wie schnell der Player geschlossen wurde; MINI_BOUNCE_MAX ist die Obergrenze
                val speed = sheetMotion.lastSettleSpeed
                val fast = ((speed - CLOSE_SPEED_SLOW) / (CLOSE_SPEED_FAST - CLOSE_SPEED_SLOW)).coerceIn(0f, 1f)
                val bounce = MINI_BOUNCE_MAX * (MINI_BOUNCE_MIN_SHARE + (1f - MINI_BOUNCE_MIN_SHARE) * fast)
                if (landed) scope.launch {
                    miniBounce.snapTo(bounce)
                    miniBounce.animateTo(
                        0f,
                        androidx.compose.animation.core.spring(
                            dampingRatio = 0.4f, stiffness = 550f, visibilityThreshold = 0.0005f
                        )
                    )
                }
            }
        }
        if (showSettings) {
            AppSettingsSheet(
                allSongs = allSongs,
                hiddenSongs = hiddenSongs,
                onUnhide = unhideSong,
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
                headerStyle = headerStyle,
                foxUnlocked = foxUnlocked,
                onHeaderStyle = { headerStyle = it; saveHeaderStyle(ctx, it) },
                cornerStyle = CornerSettings.style,
                onCornerStyle = { CornerSettings.style = it; saveCornerStyle(ctx, it) },
                playerBg = CornerSettings.playerBg,
                onPlayerBg = { CornerSettings.playerBg = it; savePlayerBg(ctx, it) },
                menuUnified = CornerSettings.menuUnified,
                onMenuUnified = { CornerSettings.menuUnified = it; saveMenuUnified(ctx, it) },
                haptics = Haptics.enabled,
                onHaptics = { Haptics.setEnabled(ctx, it); if (it) Haptics.click(ctx) },
                language = Lang.load(ctx),
                onLanguage = {
                    if (it != Lang.load(ctx)) {
                        Lang.save(ctx, it)
                        // Activity neu aufbauen, damit alle Texte in der neuen Sprache geladen werden
                        ctx.findActivity()?.recreate()
                    }
                },
                rescanning = rescanning,
                onRescan = {
                    if (!rescanning) scope.launch {
                        rescanning = true
                        val n = rescanChanged(ctx, force = true)
                        val base = withContext(Dispatchers.IO) { loadLibrary(ctx) }
                        allSongs = refreshTagsFromFiles(ctx, base, force = true)
                        rescanning = false
                        Toast.makeText(ctx, plural(R.plurals.library_updated, n), Toast.LENGTH_SHORT).show()
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
        BounceIconButton(onClick = onBack) {
            BackIcon(description = str(R.string.back))
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
        modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { onClick() }
    )
}

@Composable
private fun ViewStyleOptions(style: ViewStyle, onChange: (ViewStyle) -> Unit) {
    SortOption(str(R.string.style_list), str(R.string.style_list_sub), !style.grid) {
        onChange(style.copy(grid = false))
    }
    SortOption(str(R.string.style_tiles), str(R.string.style_tiles2_sub), style.grid && style.cols == 2) {
        onChange(ViewStyle(grid = true, cols = 2))
    }
    SortOption(str(R.string.style_tiles), str(R.string.style_tiles3_sub), style.grid && style.cols == 3) {
        onChange(ViewStyle(grid = true, cols = 3))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(
    allSongs: List<Song>,
    hiddenSongs: List<Song>,
    onUnhide: (Song) -> Unit,
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
    headerStyle: HeaderStyle,
    foxUnlocked: Boolean,
    onHeaderStyle: (HeaderStyle) -> Unit,
    cornerStyle: CornerStyle,
    onCornerStyle: (CornerStyle) -> Unit,
    playerBg: PlayerBackground,
    onPlayerBg: (PlayerBackground) -> Unit,
    menuUnified: Boolean,
    onMenuUnified: (Boolean) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    language: AppLang,
    onLanguage: (AppLang) -> Unit,
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

    // Stark gerundete Geräteecken (ab 40 dp): ganz unten eine leere Zeile, damit nichts in der Ecke abgeschnitten wirkt
    val cornerView = androidx.compose.ui.platform.LocalView.current
    val cornerDensity = androidx.compose.ui.platform.LocalDensity.current
    val deviceCorner = remember(cornerView) {
        val insets = cornerView.rootWindowInsets
        val px = listOf(
            android.view.RoundedCorner.POSITION_TOP_LEFT,
            android.view.RoundedCorner.POSITION_TOP_RIGHT,
            android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
            android.view.RoundedCorner.POSITION_BOTTOM_RIGHT
        ).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull() ?: 0
        with(cornerDensity) { px.toDp() }
    }
    // Zusätzliche leere Zeile am Ende des Inhalts (scrollt mit, schneidet nichts ab)
    val bottomExtra = if (deviceCorner >= 40.dp) 56.dp else 0.dp

    BounceSheet(onDismissRequest = onDismiss) {
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
                    str(R.string.settings),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                // Die Hauptseite ist inzwischen höher als das Menü erlaubt: Der Inhalt scrollt, die Überschrift bleibt stehen
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                Text(
                    str(R.string.settings_library),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.index)) },
                    supportingContent = { Text(str(R.string.index_summary)) },
                    leadingContent = { HashIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "index" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.sort)) },
                    supportingContent = {
                        Text(
                            when (sort) {
                                SortMode.NAME -> str(R.string.sort_name_az)
                                SortMode.MODIFIED -> str(R.string.sort_modified_summary)
                                SortMode.DATE -> str(R.string.sort_added_summary)
                            }
                        )
                    },
                    leadingContent = { CalendarIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "sort" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.hidden_tracks)) },
                    supportingContent = {
                        Text(
                            if (hiddenSongs.isEmpty()) str(R.string.hidden_tracks_none)
                            else plural(R.plurals.n_tracks, hiddenSongs.size)
                        )
                    },
                    leadingContent = { EyeOffIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "hidden" }
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    str(R.string.settings_appearance),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.album_view)) },
                    supportingContent = { Text(albumStyle.label()) },
                    leadingContent = { AlbumDiscIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "albumView" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.playlist_view)) },
                    supportingContent = { Text(playlistStyle.label()) },
                    leadingContent = { GridIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "playlistView" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.design)) },
                    supportingContent = { Text(str(R.string.design_summary)) },
                    leadingContent = { CornersIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "design" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.header)) },
                    supportingContent = { Text(
                            when (headerStyle) {
                                HeaderStyle.LOGO -> str(R.string.header_logo)
                                HeaderStyle.FOX -> str(R.string.header_fox)
                                HeaderStyle.TEXT -> str(R.string.header_summary_default)
                            }
                        ) },
                    leadingContent = { TitleTIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "headerView" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.progress_bar)) },
                    supportingContent = { Text(if (CornerSettings.waveProgress) str(R.string.progress_wave) else str(R.string.progress_bar_style)) },
                    leadingContent = { WaveBarsIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "progressStyle" }
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    str(R.string.settings_playback),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.audio_info)) },
                    supportingContent = { Text(str(R.string.audio_info_summary)) },
                    leadingContent = { InfoIcon() },
                    trailingContent = {
                        Switch(checked = showAudioInfo, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) { onShowAudioInfo(!showAudioInfo) }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.haptics)) },
                    supportingContent = { Text(str(R.string.haptics_summary)) },
                    leadingContent = { VibrationIcon() },
                    trailingContent = {
                        Switch(checked = haptics, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) { onHaptics(!haptics) }
                )
                val outputCtx = LocalContext.current
                val outputOn = CornerSettings.showOutputDevice
                ListItem(
                    headlineContent = { Text(str(R.string.output_device)) },
                    supportingContent = { Text(str(R.string.output_device_summary)) },
                    leadingContent = { HeadphonesIcon() },
                    trailingContent = {
                        Switch(checked = outputOn, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !outputOn
                        CornerSettings.showOutputDevice = on
                        saveShowOutputDevice(outputCtx, on)
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    str(R.string.settings_general),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.language)) },
                    supportingContent = {
                        Text(
                            when (language) {
                                AppLang.SYSTEM -> str(R.string.language_system)
                                AppLang.DE -> "Deutsch"
                                AppLang.EN -> "English"
                            }
                        )
                    },
                    leadingContent = { GlobeIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "language" }
                )
                Spacer(Modifier.height(32.dp + bottomExtra))
                }
            }
            "index" -> {
                SheetHeader(str(R.string.index)) { page = "main" }
                ListItem(
                    headlineContent = { Text(str(R.string.music_folders)) },
                    supportingContent = {
                        Text(
                            if (selected == null) str(R.string.all_folders)
                            else str(R.string.folders_count, all.count { it in current }, all.size)
                        )
                    },
                    leadingContent = { FolderIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "folders" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.rescan)) },
                    supportingContent = {
                        Text(if (rescanning) str(R.string.rescan_busy) else str(R.string.rescan_summary))
                    },
                    leadingContent = { RefreshIcon() },
                    trailingContent = {
                        if (rescanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { onRescan() }
                )
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "hidden" -> {
                SheetHeader(str(R.string.hidden_tracks)) { page = "main" }
                if (hiddenSongs.isEmpty()) {
                    Text(
                        str(R.string.hidden_tracks_empty),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Scrollbar und träge geladen: auch mit vielen ausgeblendeten Titeln bleibt die Seite flüssig
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 32.dp + bottomExtra)
                    ) {
                        androidx.compose.foundation.lazy.items(hiddenSongs, key = { it.id }) { s ->
                            ListItem(
                                headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Cover(s, 48.dp) },
                                trailingContent = {
                                    BounceIconButton(onClick = { onUnhide(s) }) {
                                        EyeIcon(description = str(R.string.show_track))
                                    }
                                },
                                colors = clear
                            )
                        }
                    }
                }
            }
            "sort" -> {
                SheetHeader(str(R.string.sort)) { page = "main" }
                SortOption(str(R.string.sort_added), str(R.string.sort_added_sub), sort == SortMode.DATE) {
                    onSort(SortMode.DATE)
                }
                SortOption(str(R.string.sort_modified), str(R.string.sort_modified_sub), sort == SortMode.MODIFIED) {
                    onSort(SortMode.MODIFIED)
                }
                SortOption(str(R.string.sort_name), str(R.string.sort_name_sub), sort == SortMode.NAME) {
                    onSort(SortMode.NAME)
                }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "language" -> {
                SheetHeader(str(R.string.language)) { page = "main" }
                SortOption(str(R.string.language_system), str(R.string.language_system_sub), language == AppLang.SYSTEM) {
                    onLanguage(AppLang.SYSTEM)
                }
                SortOption("Deutsch", null, language == AppLang.DE) { onLanguage(AppLang.DE) }
                SortOption("English", null, language == AppLang.EN) { onLanguage(AppLang.EN) }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "progressStyle" -> {
                val progressCtx = LocalContext.current
                SheetHeader(str(R.string.progress_bar)) { page = "main" }
                SortOption(str(R.string.progress_bar_style), str(R.string.progress_bar_style_sub), !CornerSettings.waveProgress) {
                    CornerSettings.waveProgress = false
                    saveWaveProgress(progressCtx, false)
                }
                SortOption(str(R.string.progress_wave), str(R.string.progress_wave_sub), CornerSettings.waveProgress) {
                    CornerSettings.waveProgress = true
                    saveWaveProgress(progressCtx, true)
                }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "headerView" -> {
                SheetHeader(str(R.string.header)) { page = "main" }
                SortOption(str(R.string.standard), str(R.string.header_option_text), headerStyle == HeaderStyle.TEXT) {
                    onHeaderStyle(HeaderStyle.TEXT)
                }
                SortOption(str(R.string.header_logo), str(R.string.header_option_logo_sub), headerStyle == HeaderStyle.LOGO) {
                    onHeaderStyle(HeaderStyle.LOGO)
                }
                if (foxUnlocked) {
                    SortOption(str(R.string.header_fox), str(R.string.header_fox_sub), headerStyle == HeaderStyle.FOX) {
                        onHeaderStyle(HeaderStyle.FOX)
                    }
                }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "design" -> {
                SheetHeader(str(R.string.design)) { page = "main" }
                Text(
                    str(R.string.corners),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                SortOption(str(R.string.standard), null, cornerStyle == CornerStyle.STANDARD) {
                    onCornerStyle(CornerStyle.STANDARD)
                }
                SortOption(str(R.string.corners_uniform), null, cornerStyle == CornerStyle.UNIFORM) {
                    onCornerStyle(CornerStyle.UNIFORM)
                }
                Text(
                    str(R.string.player_bg),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                )
                SortOption(str(R.string.bg_solid), null, playerBg == PlayerBackground.SOLID) {
                    onPlayerBg(PlayerBackground.SOLID)
                }
                SortOption(str(R.string.bg_gradient), null, playerBg == PlayerBackground.GRADIENT) {
                    onPlayerBg(PlayerBackground.GRADIENT)
                }
                SortOption(
                    str(R.string.bg_blur),
                    str(R.string.bg_blur_sub),
                    playerBg == PlayerBackground.BLUR
                ) { onPlayerBg(PlayerBackground.BLUR) }
                // Kippschalter: Menüs, Dialoge und Einstellungen wie der Player (nur bei Unschärfe)
                val menuSwitchEnabled = playerBg == PlayerBackground.BLUR
                ListItem(
                    headlineContent = { Text(str(R.string.menu_unified)) },
                    // Der lange Text bleibt immer (unsichtbar) im Layout und reserviert seine Zeilen: Der kurze Hinweis
                    // "Nur bei Unschärfe verfügbar" liegt darüber. So springt die Seite beim Wechseln nicht.
                    supportingContent = {
                        Box {
                            Text(
                                str(R.string.menu_unified_on),
                                modifier = if (menuSwitchEnabled) Modifier
                                else Modifier
                                    .alpha(0f)
                                    .clearAndSetSemantics { }
                            )
                            if (!menuSwitchEnabled) Text(str(R.string.menu_unified_off))
                        }
                    },
                    trailingContent = {
                        Switch(checked = menuUnified, onCheckedChange = null, enabled = menuSwitchEnabled)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, enabled = menuSwitchEnabled) { onMenuUnified(!menuUnified) }
                )
                // Schatten unter dem Cover im Player
                val shadowCtx = LocalContext.current
                val shadowOn = CornerSettings.coverShadow
                ListItem(
                    headlineContent = { Text(str(R.string.shadow)) },
                    trailingContent = { Switch(checked = shadowOn, onCheckedChange = null) },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !shadowOn
                        CornerSettings.coverShadow = on
                        saveCoverShadow(shadowCtx, on)
                    }
                )
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "albumView" -> {
                SheetHeader(str(R.string.album_view)) { page = "main" }
                ViewStyleOptions(albumStyle, onAlbumStyle)
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "playlistView" -> {
                SheetHeader(str(R.string.playlist_view)) { page = "main" }
                ViewStyleOptions(playlistStyle, onPlaylistStyle)
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            else -> {
                SheetHeader(str(R.string.music_folders)) { page = "index" }
                Text(
                    str(R.string.folders_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Row(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { onFolders(all) }) { Text(str(R.string.all)) }
                    TextButton(onClick = { onFolders(emptySet()) }) { Text(str(R.string.none)) }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 32.dp + bottomExtra)
                ) {
                    counts.forEach { (folder, n) ->
                        ListItem(
                            headlineContent = {
                                Text(folder.trimEnd('/').ifEmpty { str(R.string.root_folder) })
                            },
                            supportingContent = { Text(plural(R.plurals.n_songs, n)) },
                            trailingContent = {
                                Checkbox(checked = folder in current, onCheckedChange = null)
                            },
                            colors = clear,
                            modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) {
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
