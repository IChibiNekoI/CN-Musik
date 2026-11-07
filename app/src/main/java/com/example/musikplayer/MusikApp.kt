package com.example.musikplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
    var folderRules by remember { mutableStateOf(readFolderRules(ctx)) }
    var sort by remember { mutableStateOf(readSort(ctx)) }
    var playlists by remember { mutableStateOf(readPlaylists(ctx)) }
    var favorites by remember { mutableStateOf(readFavorites(ctx)) }
    // Das Widget kann Favoriten ändern: Änderungen in den gespeicherten Einstellungen hier übernehmen
    DisposableEffect(Unit) {
        val prefs = appPrefs(ctx)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "favorites") {
                val f = readFavorites(ctx)
                if (f != favorites) favorites = f
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    var history by remember { mutableStateOf(readHistory(ctx)) }
    var playLog by remember { mutableStateOf(readPlayLog(ctx)) }
    // Titel, die aus der Warteschlange des Neko Mixes entfernt wurden, bleiben 30 Tage aus dem Mix
    var mixDislikes by remember { mutableStateOf(readMixDislikes(ctx)) }
    // Skips im Neko Mix (nur dort gezählt): übersprungene Titel werden seltener gewählt
    var mixSkips by remember { mutableStateOf(readMixSkips(ctx)) }
    // Titel der Warteschlange, die vom Neko Mix gestartet wurde (leer, sobald etwas anderes gestartet wird)
    var mixQueueIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // Der Neko Mix hat eigene Repeat- und Shuffle-Einstellungen, unabhängig vom restlichen Player
    var mixActive by remember { mutableStateOf(readMixActive(ctx)) }
    // Zufallswiedergabe einer Playlist: Zufall und Alle wiederholen gelten nur für diese Wiedergabe und werden nicht gespeichert
    var tempProfile by remember { mutableStateOf(false) }
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
    val songs = remember(allSongs, folderRules) { folderRules.filter(allSongs) }
    // Ausgeblendete Titel fehlen nur im Titel-Tab, sie stehen unter Einstellungen > Ausgeblendete Titel
    val hiddenSongs = remember(allSongs, hidden) {
        allSongs.filter { it.id in hidden }.sortedBy { it.title.lowercase() }
    }

    val controller = rememberController()
    val controllerNow by rememberUpdatedState(controller)
    // Zwischen normalem Profil und Mix-Profil umschalten. Erst das aktive Profil setzen, dann die Werte anwenden:
    // Die Player-Listener speichern Änderungen immer ins aktive Profil und schreiben dabei nur dessen eigene Werte zurück.
    fun switchProfile(toMix: Boolean) {
        if (mixActive == toMix) return
        mixActive = toMix
        saveMixActive(ctx, toMix)
        controller?.let { c ->
            val r = if (toMix) readMixRepeat(ctx) else readRepeat(ctx)
            val sh = if (toMix) readMixShuffle(ctx) else readShuffle(ctx)
            if (c.repeatMode != r) c.repeatMode = r
            if (c.shuffleModeEnabled != sh) c.shuffleModeEnabled = sh
        }
    }
    // Die vorübergehende Zufallswiedergabe beenden: Zufall und Wiederholen kehren zum gespeicherten Profil zurück
    fun endTempProfile() {
        if (!tempProfile) return
        tempProfile = false
        controller?.let { c ->
            val r = if (mixActive) readMixRepeat(ctx) else readRepeat(ctx)
            val sh = if (mixActive) readMixShuffle(ctx) else readShuffle(ctx)
            if (c.repeatMode != r) c.repeatMode = r
            if (c.shuffleModeEnabled != sh) c.shuffleModeEnabled = sh
        }
    }
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
        // gespeicherte Einstellungen des aktiven Profils (normal oder Neko Mix) wiederherstellen
        val savedRepeat = if (mixActive) readMixRepeat(ctx) else readRepeat(ctx)
        val savedShuffle = if (mixActive) readMixShuffle(ctx) else readShuffle(ctx)
        if (c.repeatMode != savedRepeat) c.repeatMode = savedRepeat
        if (c.shuffleModeEnabled != savedShuffle) c.shuffleModeEnabled = savedShuffle
        repeat = savedRepeat
        shuffle = savedShuffle
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
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Skip im Neko Mix: Nur Weiterschalten per Hand zählt (kein automatisches Ende, kein Entfernen aus der Warteschlange),
                // nur nach vorn, nur für Titel, die der Mix gestartet hat, und nur, wenn der Titel noch nicht lange lief.
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
                if (!c.shuffleModeEnabled && newPosition.mediaItemIndex < oldPosition.mediaItemIndex) return
                val oldId = oldPosition.mediaItem?.mediaId?.toLongOrNull() ?: return
                if (oldId !in mixQueueIds) return
                val at = oldPosition.positionMs
                if (at >= 30_000L) return
                mixSkips = addMixSkip(ctx, oldId, if (at < 10_000L) 2 else 1, mixSkips)
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffle = enabled
                if (tempProfile) return
                if (mixActive) saveMixShuffle(ctx, enabled) else saveShuffle(ctx, enabled)
            }
            override fun onRepeatModeChanged(mode: Int) {
                repeat = mode
                if (tempProfile) return
                if (mixActive) saveMixRepeat(ctx, mode) else saveRepeat(ctx, mode)
            }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }
    // Davor die Datei-Stempel der Bibliothek merken (Größe + Änderungszeit): Messwerte zu veränderten Dateien gelten dann
    // als veraltet und werden neu gemessen; die Mess-Dateien werden dabei kompakt neu geschrieben.
    val stampKey by produceState(0, allSongs) {
        value = withContext(Dispatchers.Default) {
            val key = AudioStamps.update(allSongs)
            if (allSongs.isNotEmpty()) {
                val keep = HashSet<Long>(allSongs.size * 2)
                for (s in allSongs) keep.add(s.id)
                keep.addAll(TrackIdentity.knownIds(ctx))   // kürzlich verschwundene Titel behalten ihre Werte (Wiedererkennung)
                LoudnessCache.tidy(ctx, keep)
                TrackFeatureCache.tidy(ctx, keep)
            }
            key
        }
    }
    // Titel ohne Messwert im Hintergrund nachmessen (schon gemessene werden übersprungen), nur am Ladegerät:
    // Lautheit (Auto-Gain) und Klangmerkmale (Neko Mix) entstehen im selben Durchlauf
    LaunchedEffect(songs.size, stampKey) {
        if (songs.isNotEmpty() && stampKey != 0) {
            LoudnessScanner.start(ctx, songs.map { it.id to it.uri })
        } else {
            LoudnessScanner.stop()
        }
    }
    // Zuletzt gehört: Sobald ein Titel läuft (egal von wo gestartet), steht er im Verlauf. Pause und Weiter
    // beim selben Titel schiebt ihn nicht erneut nach vorn. Fürs Hörprotokoll des Mixes (30 Tage) zählt eine Wiedergabe erst nach 10 Sekunden,
    // damit Durchskippen es nicht verfälscht.
    var lastHistoryId by remember { mutableStateOf<String?>(null) }
    var lastCountedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentId, isPlaying) {
        val id = currentId?.toLongOrNull() ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (currentId != lastHistoryId) {
            lastHistoryId = currentId
            history = recordHistory(ctx, id, history)
        }
        if (currentId != lastCountedId) {
            delay(10_000)
            lastCountedId = currentId
            playLog = recordPlay(ctx, id, playLog)
        }
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
        mixQueueIds = emptySet()
        endTempProfile()
        switchProfile(false)
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
            else MaterialTheme.colorScheme.background,
            // Ausdrücklich setzen: Bei durchsichtigem Hintergrund wüsste Scaffold sonst keine Textfarbe und fiele auf
            // Schwarz zurück (Titel, Symbole, Überschriften und Namen blieben schwarz)
            contentColor = MaterialTheme.colorScheme.onBackground
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
                    history = history,
                    playLog = playLog,
                    mixSkips = mixSkips,
                    mixExcluded = mixDislikes.keys,
                    onMixPlayed = { list ->
                        mixQueueIds = list.map { it.id }.toSet()
                        endTempProfile()
                        switchProfile(true)
                    },
                    onShufflePlay = { list ->
                        // Zufälliger Titel der Playlist, Zufall und Alle wiederholen nur vorübergehend (wie beim Neko Mix, aber ohne zu speichern)
                        mixQueueIds = emptySet()
                        switchProfile(false)
                        tempProfile = true   // zuerst: ab jetzt speichern die Player-Listener nichts mehr
                        controller?.let { c ->
                            SkipDirection.dir = 1
                            if (c.repeatMode != Player.REPEAT_MODE_ALL) c.repeatMode = Player.REPEAT_MODE_ALL
                            if (!c.shuffleModeEnabled) c.shuffleModeEnabled = true
                            c.setMediaItems(toMediaItems(list), list.indices.random(), 0L)
                            c.prepare()
                            c.play()
                        }
                    },
                    onToggleFavorite = toggleFavorite,
                    onHideSong = hideSong,
                    hiddenIds = hidden,
                    onReorderFavorites = { favorites = it; saveFavorites(ctx, it) },
                    onPlaylistsChange = { playlists = it; savePlaylists(ctx, it) },
                    onPlay = { list, index ->
                        // Startet der Neko Mix (wurde kurz vorher als Mix markiert), bleibt sein Profil aktiv;
                        // alles andere schaltet auf das normale Profil zurück
                        val isMixStart = mixQueueIds.isNotEmpty() &&
                            list.size == mixQueueIds.size && list.all { it.id in mixQueueIds }
                        if (!isMixStart) {
                            mixQueueIds = emptySet()
                            endTempProfile()
                            switchProfile(false)
                        }
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
                    // Gleiche Albumliste wie in der Albenansicht, damit gleichnamige Alben nicht verwechselt werden
                    albumRequest = albumLabelOf(current, groupAlbums(songs)) ?: current.album.ifBlank { str(R.string.unknown_album) }
                    showNow = false
                },
                onAddToPlaylist = { playlistRequest = current },
                onQueueRemoved = { s ->
                    if (s.id in mixQueueIds) mixDislikes = addMixDislike(ctx, s.id, mixDislikes)
                }
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
                folderRules = folderRules,
                sort = sort,
                onFolderRules = { folderRules = it; saveFolderRules(ctx, it) },
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
