package com.example.musikplayer

import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
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

/**
 * Zum nächsten/vorherigen Titel springen und dabei auch aus der Pause heraus abspielen.
 * Im Modus "einen Titel wiederholen" verhält sich das Weiter- und Zurückspringen wie bei "alle wiederholen": Am Ende
 * der Liste geht es zum Anfang, am Anfang zum Ende (der Player selbst bleibt in diesem Modus sonst am Rand stehen).
 * Arbeitet mit jedem Player, auch dem Controller der App und dem Player im Dienst (Widgets).
 */
fun skipNext(c: Player) {
    if (c.hasNextMediaItem()) {
        SkipDirection.dir = 1
        c.seekToNextMediaItem()
        c.play()
    } else if (c.repeatMode == Player.REPEAT_MODE_ONE && !c.currentTimeline.isEmpty) {
        SkipDirection.dir = 1
        c.seekToDefaultPosition(c.currentTimeline.getFirstWindowIndex(c.shuffleModeEnabled))
        c.play()
    }
}

fun skipPrev(c: Player) {
    if (c.hasPreviousMediaItem()) {
        SkipDirection.dir = -1
        c.seekToPreviousMediaItem()
        c.play()
    } else if (c.repeatMode == Player.REPEAT_MODE_ONE && !c.currentTimeline.isEmpty) {
        SkipDirection.dir = -1
        c.seekToDefaultPosition(c.currentTimeline.getLastWindowIndex(c.shuffleModeEnabled))
        c.play()
    }
}
