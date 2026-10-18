package com.example.musikplayer

import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

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

/** Richtung des letzten Titelwechsels: 1 = vorwärts, -1 = zurück. Bestimmt, von wo das neue Cover hereingleitet. */
object SkipDirection {
    var dir = 1
}

/** Zum nächsten/vorherigen Titel springen und dabei auch aus der Pause heraus abspielen. */
fun skipNext(c: MediaController) {
    if (c.hasNextMediaItem()) {
        SkipDirection.dir = 1
        c.seekToNextMediaItem()
        c.play()
    }
}

fun skipPrev(c: MediaController) {
    if (c.hasPreviousMediaItem()) {
        SkipDirection.dir = -1
        c.seekToPreviousMediaItem()
        c.play()
    }
}
