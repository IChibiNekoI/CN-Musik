package com.example.musikplayer

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Kopfzeile <-> Suchfeld: wächst aus der Ecke der Such-Taste (rechts) heraus bzw. kehrt von links zurück */
internal fun AnimatedContentTransitionScope<Boolean>.searchHeader(): ContentTransform {
    val origin = TransformOrigin(if (targetState) 1f else 0f, 0.5f)
    val enter = fadeIn(tween(Motion.MEDIUM, delayMillis = 40)) +
        scaleIn(spring(dampingRatio = 0.7f, stiffness = 420f), initialScale = 0.85f, transformOrigin = origin)
    val exit = fadeOut(tween(Motion.FAST)) +
        scaleOut(tween(Motion.FAST, easing = Motion.Ease), targetScale = 0.92f, transformOrigin = origin)
    return (enter togetherWith exit) using SizeTransform(clip = false)
}

/** Ergebnisse <-> Tabs: Der neue Inhalt steigt mit Feder leicht auf, der alte weicht aus und blendet aus */
internal fun AnimatedContentTransitionScope<Boolean>.searchBody(): ContentTransform {
    val opening = targetState
    val move = spring<IntOffset>(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val enter = slideInVertically(move) { h -> if (opening) h / 14 else -h / 18 } +
        fadeIn(tween(Motion.MEDIUM, delayMillis = 60))
    val exit = slideOutVertically(move) { h -> if (opening) -h / 18 else h / 14 } +
        fadeOut(tween(Motion.FAST))
    return (enter togetherWith exit) using SizeTransform(clip = false)
}

/**
 * Alben und Playlists öffnen und schließen wie ein Seitenwechsel: Die Detailansicht schiebt sich mit Feder von rechts
 * herein und wird dabei größer, die Liste weicht nach links aus. Beim Zurückgehen läuft es umgekehrt.
 */
internal fun AnimatedContentTransitionScope<String?>.pushPop(): ContentTransform {
    val push = targetState != null
    val move = spring<IntOffset>(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val enter = slideInHorizontally(move) { w -> if (push) w / 4 else -w / 8 } +
        fadeIn(tween(Motion.MEDIUM, delayMillis = 40)) +
        scaleIn(tween(Motion.MEDIUM, easing = Motion.Ease), initialScale = if (push) 0.96f else 1.03f)
    val exit = slideOutHorizontally(move) { w -> if (push) -w / 8 else w / 4 } +
        fadeOut(tween(Motion.FAST)) +
        scaleOut(tween(Motion.MEDIUM, easing = Motion.Ease), targetScale = if (push) 1.03f else 0.96f)
    return (enter togetherWith exit) using SizeTransform(clip = false)
}

/** Wie [pushPop], aber für eine Ansicht, die sich nur öffnet (true) oder schließt (false), z. B. "Meistens gespielt" auf der Startseite */
internal fun AnimatedContentTransitionScope<Boolean>.pushPopFlag(): ContentTransform {
    val push = targetState
    val move = spring<IntOffset>(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val enter = slideInHorizontally(move) { w -> if (push) w / 4 else -w / 8 } +
        fadeIn(tween(Motion.MEDIUM, delayMillis = 40)) +
        scaleIn(tween(Motion.MEDIUM, easing = Motion.Ease), initialScale = if (push) 0.96f else 1.03f)
    val exit = slideOutHorizontally(move) { w -> if (push) -w / 8 else w / 4 } +
        fadeOut(tween(Motion.FAST)) +
        scaleOut(tween(Motion.MEDIUM, easing = Motion.Ease), targetScale = if (push) 1.03f else 0.96f)
    return (enter togetherWith exit) using SizeTransform(clip = false)
}

/** Welche Ansicht die Suche gerade zeigt: Treffer, alle Alben oder ein geöffnetes Album. [depth] bestimmt die Richtung des Übergangs. */
internal data class SearchView(val all: Boolean = false, val album: String? = null) {
    val depth: Int get() = (if (all) 1 else 0) + (if (album != null) 1 else 0)
}

/** Wie [pushPop] bei Album und Playlist: tiefer hinein schiebt die neue Ansicht von rechts herein, zurück läuft es umgekehrt */
internal fun AnimatedContentTransitionScope<SearchView>.searchPushPop(): ContentTransform {
    val push = targetState.depth > initialState.depth
    val move = spring<IntOffset>(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    val enter = slideInHorizontally(move) { w -> if (push) w / 4 else -w / 8 } +
        fadeIn(tween(Motion.MEDIUM, delayMillis = 40)) +
        scaleIn(tween(Motion.MEDIUM, easing = Motion.Ease), initialScale = if (push) 0.96f else 1.03f)
    val exit = slideOutHorizontally(move) { w -> if (push) -w / 8 else w / 4 } +
        fadeOut(tween(Motion.FAST)) +
        scaleOut(tween(Motion.MEDIUM, easing = Motion.Ease), targetScale = if (push) 1.03f else 0.96f)
    return (enter togetherWith exit) using SizeTransform(clip = false)
}

/** Wischgeste auf der Kopfzeile: senkrechtes Wischen; hoch = onUp, runter = onDown (erst beim Loslassen) */
internal fun Modifier.headerSwipe(onUp: () -> Unit, onDown: () -> Unit): Modifier = composed {
    val up by rememberUpdatedState(onUp)
    val down by rememberUpdatedState(onDown)
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    pointerInput(Unit) {
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total < -threshold) up() else if (total > threshold) down()
                total = 0f
            },
            onDragCancel = { total = 0f },
            onVerticalDrag = { _, dy -> total += dy }
        )
    }
}

/** kind: 0 = zu [target] springen (liegt im oberen Drittel), 1 = Anfang, 2 = Ende */
internal suspend fun LazyListState.headerJump(kind: Int, target: Int) {
    when (kind) {
        1 -> animateScrollToItem(0)
        2 -> { val n = layoutInfo.totalItemsCount; if (n > 0) animateScrollToItem(n - 1) }
        else -> if (target >= 0) animateScrollToItem(target, -(layoutInfo.viewportSize.height / 3))
    }
}

internal suspend fun androidx.compose.foundation.lazy.grid.LazyGridState.headerJump(kind: Int, target: Int) {
    when (kind) {
        1 -> animateScrollToItem(0)
        2 -> { val n = layoutInfo.totalItemsCount; if (n > 0) animateScrollToItem(n - 1) }
        else -> if (target >= 0) animateScrollToItem(target, -(layoutInfo.viewportSize.height / 3))
    }
}
