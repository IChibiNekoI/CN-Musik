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
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.foundation.gestures.draggable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
                setContent { AppTheme { MusikApp() } }
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
        val folder: String,
    val album: String = "",
    val dateAdded: Long = 0L,
    val track: Int = 0
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
        // Füllung startet als kleiner Kreis (Höhe = Breite) und füllt den Balken bei 100 % genau aus
        val density = LocalDensity.current
        val minPx = with(density) { 12.dp.toPx() }
        val fillPx = (minPx + progress.coerceIn(0f, 1f) * (width - minPx)).coerceAtLeast(minPx)
        Box(
            Modifier
                .width(with(density) { fillPx.toDp() })
                .height(12.dp)
                .clip(CircleShape)
                .background(fillColor)
        )
    }
}

/** Dominanter Farbton eines Covers (HSL). */
private class CoverSeed(val h: Float, val s: Float, val neutral: Boolean)

private object SeedCache : LruCache<Long, CoverSeed>(100)

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** Ermittelt den dominanten, kräftigen Farbton des Covers. */
private fun seedFromBitmap(src: Bitmap): CoverSeed? {
    return try {
        val n = 48
        val bmp = Bitmap.createScaledBitmap(src, n, n, true)
        val pixels = IntArray(n * n)
        bmp.getPixels(pixels, 0, n, 0, 0, n, n)
        val buckets = 24
        val weight = FloatArray(buckets)
        val satSum = FloatArray(buckets)
        val buf = FloatArray(3)
        for (px in pixels) {
            ColorUtils.colorToHSL(px, buf)
            val l = buf[2]
            if (l < 0.08f || l > 0.96f) continue
            val w = buf[1] * (1f - kotlin.math.abs(2f * l - 1f))
            val b = ((buf[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[b] += w
            satSum[b] += buf[1] * w
        }
        var best = 0
        for (i in 1 until buckets) if (weight[i] > weight[best]) best = i
        val neutral = weight[best] < 20f
        val h = (best + 0.5f) * (360f / buckets)
        val s = if (neutral) 0f else satSum[best] / weight[best]
        CoverSeed(h, s, neutral)
    } catch (e: Exception) {
        null
    }
}

/** Alle Farben, die das Cover-Theme überschreibt (feste Reihenfolge, damit man sie überblenden kann). */
private class Tones(val v: List<Color>)

private fun lerpTones(a: Tones, b: Tones, f: Float) =
    Tones(List(a.v.size) { androidx.compose.ui.graphics.lerp(a.v[it], b.v[it], f) })

private fun tonesOf(c: ColorScheme) = Tones(
    listOf(
        c.surface, c.onSurface, c.onSurfaceVariant, c.surfaceVariant,
        c.surfaceContainerLowest, c.surfaceContainerLow, c.surfaceContainer,
        c.surfaceContainerHigh, c.surfaceContainerHighest,
        c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer,
        c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer,
        c.outline, c.outlineVariant
    )
)

private fun Tones.applyTo(base: ColorScheme): ColorScheme = base.copy(
    background = v[0], surface = v[0], onBackground = v[1], onSurface = v[1],
    onSurfaceVariant = v[2], surfaceVariant = v[3],
    surfaceContainerLowest = v[4], surfaceContainerLow = v[5], surfaceContainer = v[6],
    surfaceContainerHigh = v[7], surfaceContainerHighest = v[8],
    primary = v[9], onPrimary = v[10], primaryContainer = v[11], onPrimaryContainer = v[12],
    secondary = v[13], onSecondary = v[14],
    secondaryContainer = v[15], onSecondaryContainer = v[16],
    outline = v[17], outlineVariant = v[18]
)

private fun tonesFromSeed(seed: CoverSeed, dark: Boolean): Tones {
    val h = seed.h
    val s = seed.s
    val sp = if (seed.neutral) 0f else s.coerceIn(0.5f, 0.95f)
    return if (dark) {
        val sb = (s * 0.6f).coerceAtMost(0.55f)
        val sm = (s * 0.4f).coerceAtMost(0.4f)
        Tones(
            listOf(
                hsl(h, sb, 0.18f), hsl(h, sm, 0.95f), hsl(h, sm, 0.80f), hsl(h, sm, 0.28f),
                hsl(h, sb, 0.14f), hsl(h, sb, 0.20f), hsl(h, sb, 0.22f),
                hsl(h, sb, 0.26f), hsl(h, sb, 0.30f),
                hsl(h, sp, 0.80f), hsl(h, sp * 0.8f, 0.12f), hsl(h, sp * 0.6f, 0.34f), hsl(h, sm, 0.92f),
                hsl(h, sm, 0.80f), hsl(h, sm, 0.15f), hsl(h, sm, 0.34f), hsl(h, sm, 0.94f),
                hsl(h, sm, 0.55f), hsl(h, sm, 0.38f)
            )
        )
    } else {
        val sb = (s * 0.6f).coerceAtMost(0.6f)
        val sm = (s * 0.4f).coerceAtMost(0.4f)
        Tones(
            listOf(
                hsl(h, sb, 0.96f), hsl(h, sm, 0.10f), hsl(h, sm, 0.30f), hsl(h, sb, 0.88f),
                hsl(h, sb, 0.995f), hsl(h, sb, 0.95f), hsl(h, sb, 0.93f),
                hsl(h, sb, 0.91f), hsl(h, sb, 0.89f),
                hsl(h, sp, 0.38f), hsl(h, sp, 0.98f), hsl(h, sp, 0.88f), hsl(h, sm, 0.15f),
                hsl(h, sm, 0.35f), hsl(h, sm, 0.98f), hsl(h, sm, 0.86f), hsl(h, sm, 0.15f),
                hsl(h, sm, 0.45f), hsl(h, sm, 0.80f)
            )
        )
    }
}

/**
 * Färbt den Inhalt passend zum Cover von [song] ein (weiche Überblendung beim Wechsel).
 * Ohne Song bleibt das normale Theme erhalten.
 */
@Composable
fun CoverTheme(song: Song?, dark: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val seed by produceState<CoverSeed?>(null, song?.id) {
        val sg = song
        value = if (sg == null) null else SeedCache.get(sg.id) ?: withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    seedFromBitmap(ctx.contentResolver.loadThumbnail(sg.uri, Size(96, 96), null))
                } catch (e: Exception) {
                    null
                }
            } else null
        }?.also { SeedCache.put(sg.id, it) }
    }

    val base = MaterialTheme.colorScheme
    val sd = seed
    val target = remember(sd, dark, base) {
        if (sd != null) tonesFromSeed(sd, dark) else tonesOf(base)
    }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        from = lerpTones(from, to, t.value)
        to = target
        t.snapTo(0f)
        t.animateTo(1f, androidx.compose.animation.core.tween(500))
    }
    val shown = lerpTones(from, to, t.value)

    MaterialTheme(
        colorScheme = shown.applyTo(base),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

@Composable
fun NowPlayingSheet(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    showAudioInfo: Boolean = false,
    onDismiss: () -> Unit
) {
    // Der Player ist immer dunkel -> Statusleisten-Icons hell halten
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = false
        ctrl?.isAppearanceLightNavigationBars = false
        onDispose {
            if (ctrl != null) {
                if (prevStatus != null) ctrl.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) ctrl.isAppearanceLightNavigationBars = prevNav
            }
        }
    }
    CoverTheme(song, dark = true) {
        NowPlayingSheetContent(song, isPlaying, shuffle, repeat, c, showAudioInfo, onDismiss)
    }
}

@Composable
private fun NowPlayingSheetContent(
    song: Song,
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Int,
    c: MediaController,
    showAudioInfo: Boolean,
    onDismiss: () -> Unit
) {
    val infoCtx = LocalContext.current
    val audioInfo by produceState<String?>(null, song.id, showAudioInfo) {
        value = if (!showAudioInfo) null
        else withContext(Dispatchers.IO) { loadAudioInfo(infoCtx, song) }
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val coverOffset = remember { Animatable(0f) }
    val coverScope = rememberCoroutineScope()

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
                val coverSize = minOf(maxWidth, maxHeight * 0.55f)
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Schließen")
                        }
                    }
                    Spacer(Modifier.weight(1.25f))   // Abstand oben: kleiner = Cover weiter oben
                    Box(
                        Modifier
                            .offset { IntOffset(coverOffset.value.roundToInt(), 0) }
                            .pointerInput(c) {
                                val threshold = size.width * 0.25f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val dx = coverOffset.value
                                        if (dx < -threshold) c.seekToNextMediaItem()
                                        else if (dx > threshold) c.seekToPreviousMediaItem()
                                        coverScope.launch { coverOffset.animateTo(0f) }
                                    },
                                    onDragCancel = {
                                        coverScope.launch { coverOffset.animateTo(0f) }
                                    },
                                    onHorizontalDrag = { change, delta ->
                                        change.consume()
                                        coverScope.launch {
                                            coverOffset.snapTo(coverOffset.value + delta)
                                        }
                                    }
                                )
                            }
                    ) {
                        Cover(song, coverSize)
                    }
                    Spacer(Modifier.weight(1.7f))    // Abstand Cover -> Titel
                    Text(
                        song.title,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(0.8f))    // Abstand Künstler -> Fortschrittsbalken

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
                    Spacer(Modifier.weight(1.0f))    // Abstand Balken -> Tasten

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
                    Box(
                        Modifier.fillMaxWidth().weight(1.95f),   // Abstand unten
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        audioInfo?.let {
                            Text(
                                it,
                                modifier = Modifier.padding(bottom = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
