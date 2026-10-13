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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
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

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

private object CoverCache : LruCache<String, Bitmap>(60)

private fun loadSongs(ctx: Context): List<Song> {
    val list = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST
    )
    ctx.contentResolver.query(
        collection, proj,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            list += Song(
                id,
                c.getString(titleCol) ?: "Unbekannt",
                c.getString(artistCol) ?: "Unbekannt",
                ContentUris.withAppendedId(collection, id)
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

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(granted) {
        if (granted) songs = withContext(Dispatchers.IO) { loadSongs(ctx) }
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
    val current = songs.find { it.id.toString() == currentId }

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
                    Text(
                        "Musik",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
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
}

@Composable
fun MiniPlayer(song: Song, isPlaying: Boolean, c: MediaController, onOpen: () -> Unit) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).clickable { onOpen() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(song, 44.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { c.seekToPreviousMediaItem() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück")
            }
            FilledIconButton(onClick = { if (isPlaying) c.pause() else c.play() }) {
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
