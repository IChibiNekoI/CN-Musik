package com.example.musikplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { PlayerScreen() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val folder: String
)

private object CoverCache : LruCache<String, Bitmap>(60)

private fun readFolders(ctx: Context): Set<String>? =
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getStringSet("folders", null)?.toSet()

private fun saveFolders(ctx: Context, set: Set<String>) {
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit().putStringSet("folders", set).apply()
}

private fun loadSongs(ctx: Context): List<Song> {
    val list = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val folderField = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        folderField
    )
    ctx.contentResolver.query(
        collection, proj,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val folderCol = c.getColumnIndexOrThrow(folderField)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val raw = c.getString(folderCol) ?: ""
            val folder = if (Build.VERSION.SDK_INT >= 29) raw else raw.substringBeforeLast('/', "")
            list += Song(
                id,
                c.getString(titleCol) ?: "Unbekannt",
                c.getString(artistCol) ?: "Unbekannt",
                ContentUris.withAppendedId(collection, id),
                folder
            )
        }
    }
    return list
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun rememberController(): MediaController? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(future) }
    }
    return controller
}

@Composable
fun Cover(song: Song, size: Dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<Bitmap?>(null, song.id, px) {
        val key = "${song.id}_$px"
        var b: Bitmap? = CoverCache.get(key)
        if (b == null) {
            b = withContext<Bitmap?>(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        ctx.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
            if (b != null) CoverCache.put(key, b)
        }
        value = b
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PlayerScreen() {
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
    var selected by remember { mutableStateOf(readFolders(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) allSongs = withContext(Dispatchers.IO) { loadSongs(ctx) }
    }
    val songs = remember(allSongs, selected) {
        selected?.let { sel -> allSongs.filter { it.folder in sel } } ?: allSongs
    }
    val items = remember(songs) {
        songs.map {
            MediaItem.Builder()
                .setMediaId(it.id.toString())
                .setUri(it.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()
                )
                .build()
        }
    }

    val controller = rememberController()
    var isPlaying by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showNow by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose {}
        isPlaying = c.isPlaying
        currentId = c.currentMediaItem?.mediaId
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                currentId = item?.mediaId
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }
    val current = allSongs.find { it.id.toString() == currentId }

    Scaffold(
        bottomBar = {
            if (current != null && controller != null) {
                MiniPlayer(current, isPlaying, controller) { showNow = true }
            }
        }
    ) { pad ->
        if (!granted) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Bitte erlaube den Zugriff auf deine Musik.")
            }
        } else {
            LazyColumn(contentPadding = pad) {
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Musik",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                        }
                    }
                }
                if (songs.isEmpty() && allSongs.isNotEmpty()) {
                    item {
                        Text(
                            "Keine Songs in den ausgewählten Ordnern.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                    ListItem(
                        headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Cover(s, 48.dp) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (s.id.toString() == currentId)
                                MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                        ),
                        modifier = Modifier.clickable {
                            controller?.let {
                                it.setMediaItems(items, i, 0L)
                                it.prepare()
                                it.play()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showNow && current != null && controller != null) {
        NowPlayingSheet(current, isPlaying, shuffle, repeat, controller) { showNow = false }
    }
    if (showSettings) {
        SettingsSheet(
            allSongs = allSongs,
            selected = selected,
            onChange = { set ->
                selected = set
                saveFolders(ctx, set)
            },
            onDismiss = { showSettings = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    allSongs: List<Song>,
    selected: Set<String>?,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf("main") }
    val counts = remember(allSongs) {
        allSongs.groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
    }
    val all = counts.map { it.first }.toSet()
    val current = selected ?: all

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        if (page == "main") {
            Text(
                "Einstellungen",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
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
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { page = "folders" }
            )
            Spacer(Modifier.height(32.dp))
        } else {
            Row(
                Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { page = "main" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                }
                Text("Musikordner", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Nur Songs aus angehakten Ordnern werden angezeigt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { onChange(all) }) { Text("Alle") }
                TextButton(onClick = { onChange(emptySet()) }) { Text("Keine") }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                counts.forEach { (folder, n) ->
                    ListItem(
                        headlineContent = { Text(folder.trimEnd('/').ifEmpty { "Hauptverzeichnis" }) },
                        supportingContent = { Text("$n Songs") },
                        trailingContent = {
                            Checkbox(checked = folder in current, onCheckedChange = null)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            onChange(if (folder in current) current - folder else current + folder)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(song: Song, isPlaying: Boolean, c: MediaController, onOpen: () -> Unit) {
      val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val margin = 12.dp
    val deviceCorner: Dp = remember(view) {
        if (Build.VERSION.SDK_INT >= 31) {
            val px = view.rootWindowInsets
                ?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
                ?.radius ?: 0
            with(density) { px.toDp() }
        } else 0.dp
    }
    val radius = if (deviceCorner > 0.dp) maxOf(deviceCorner - margin, 20.dp) else 28.dp

    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(start = margin, end = margin, bottom = margin),
        shape = RoundedCornerShape(radius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).clickable { onOpen() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(song, 56.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { c.seekToPreviousMediaItem() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück")
            }
            FilledIconButton(
                onClick = { if (isPlaying) c.pause() else c.play() },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Abspielen"
                )
            }
            IconButton(onClick = { c.seekToNextMediaItem() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Weiter")
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheetOld(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    onDismiss: () -> Unit
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(c) {
        while (true) {
            position = c.currentPosition
            duration = max(c.duration, 0L)
            delay(300)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Cover(song, 280.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))

            val range = max(duration.toFloat(), 1f)
            val shown = if (dragging) dragValue else position.toFloat()
            Slider(
                value = shown.coerceIn(0f, range),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    c.seekTo(dragValue.toLong())
                    position = dragValue.toLong()
                    dragging = false
                },
                valueRange = 0f..range
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(shown.toLong()), style = MaterialTheme.typography.labelMedium)
                Text(fmt(duration), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { c.shuffleModeEnabled = !shuffle }) {
                    Icon(
                        Icons.Filled.Shuffle, contentDescription = "Zufall",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { c.seekToPreviousMediaItem() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück")
                }
                FilledIconButton(
                    onClick = { if (isPlaying) c.pause() else c.play() },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Abspielen",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { c.seekToNextMediaItem() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Weiter")
                }
                IconButton(onClick = {
                    c.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Wiederholen",
                        tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
@Composable
fun PillProgress(
    progress: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val fillColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val change = rememberUpdatedState(onChange)
    val finished = rememberUpdatedState(onFinished)
    var width by remember { mutableFloatStateOf(1f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    change.value((pos.x / width).coerceIn(0f, 1f))
                    finished.value()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { pos -> change.value((pos.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { finished.value() },
                    onDragCancel = { finished.value() },
                    onHorizontalDrag = { ch, _ ->
                        ch.consume()
                        change.value((ch.position.x / width).coerceIn(0f, 1f))
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(trackColor)
        )
        Box(
            Modifier
                .widthIn(min = 24.dp)
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(12.dp)
                .clip(CircleShape)
                .background(fillColor)
        )
    }
}

@Composable
fun NowPlayingSheet(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    onDismiss: () -> Unit
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(c) {
        while (true) {
            position = c.currentPosition
            duration = max(c.duration, 0L)
            delay(250)
        }
    }
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    val shownFraction = if (dragging) dragFraction else fraction
    val shownMs = (shownFraction * duration).toLong()

          var offsetY by remember { mutableFloatStateOf(0f) }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        offsetY = (offsetY + delta).coerceAtLeast(0f)
    }
    androidx.activity.compose.BackHandler { onDismiss() }
    run {
        Surface(
            Modifier
                .fillMaxSize()
                .offset { androidx.compose.ui.unit.IntOffset(0, offsetY.toInt()) }
                .draggable(
                    state = dragState,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (offsetY > 400f || velocity > 1200f) {
                            androidx.compose.animation.core.animate(
                                offsetY, 3000f,
                                animationSpec = androidx.compose.animation.core.tween(200)
                            ) { value, _ -> offsetY = value }
                            onDismiss()
                        } else {
                            androidx.compose.animation.core.animate(offsetY, 0f) { value, _ ->
                                offsetY = value
                            }
                        }
                    }
                )
                .pointerInput(Unit) {}
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp)
            ) {
                val coverSize = minOf(maxWidth, maxHeight * 0.4f, 380.dp)
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Schließen")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Cover(song, coverSize)
                    Spacer(Modifier.height(32.dp))
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(28.dp))

                    PillProgress(
                        progress = shownFraction,
                        onChange = { dragging = true; dragFraction = it },
                        onFinished = {
                            val target = (dragFraction * duration).toLong()
                            c.seekTo(target)
                            position = target
                            dragging = false
                        }
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(fmt(shownMs), style = MaterialTheme.typography.labelMedium)
                        Text(fmt(duration), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(12.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { c.shuffleModeEnabled = !shuffle }) {
                            Icon(
                                Icons.Filled.Shuffle, contentDescription = "Zufall",
                                tint = if (shuffle) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { c.seekToPreviousMediaItem() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück")
                        }
                        FilledIconButton(
                            onClick = { if (isPlaying) c.pause() else c.play() },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Abspielen",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(onClick = { c.seekToNextMediaItem() }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Weiter")
                        }
                        IconButton(onClick = {
                            c.repeatMode = when (repeat) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }) {
                            Icon(
                                if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                contentDescription = "Wiederholen",
                                tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
