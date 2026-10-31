package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Wie IconButton (gleiche Größe und Berührfläche), aber ohne grauen Kreis: Die Taste drückt sich ein und federt
 * mit Überschwinger zurück. [rotate] dreht das Symbol beim Drücken um so viele Grad (z. B. Zahnrad) und federt zurück.
 */
@Composable
fun BounceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotate: Float = 0f,
    haptic: Boolean = true,   // kurzer Klick beim Antippen (Suche, Einstellungen, Zurück, Plus, ...)
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .bounceClick(pressedScale = 0.8f, haptic = haptic, rotate = rotate, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Menü, das aus seiner Ecke aufpoppt: wächst mit einem kleinen Überschwinger aus der Taste heraus und schrumpft
 * beim Schließen schnell wieder hinein. Ersetzt DropdownMenu; wird wie dieses direkt neben der Taste aufgerufen.
 */
@Composable
fun BounceMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            // leicht unterdämpft: kleiner, weicher Überschwinger beim Aufpoppen
            progress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
        } else {
            // zu: schnell und ohne Nachschwingen
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f))
        }
    }
    // Das Fenster bleibt bis zum Ende der Schließ-Animation bestehen
    if (expanded || progress.value > 0.001f) {
        val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
        val positionProvider = remember(margin) { MenuPositionProvider(margin) }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            // Nur solange offen abfangen, damit man beim Schließen nicht kurz blockiert ist
            properties = PopupProperties(focusable = expanded)
        ) {
            Surface(
                modifier = modifier.graphicsLayer {
                    val p = progress.value
                    val s = 0.72f + 0.28f * p
                    scaleX = s
                    scaleY = s
                    alpha = (p * 2.2f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0f)   // wächst aus der oberen rechten Ecke (bei der Taste)
                },
                shape = MenuDefaults.shape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation
            ) {
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .defaultMinSize(minWidth = 112.dp)
                        .padding(vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

/** Unter der Taste, rechtsbündig mit ihr; passt es unten nicht mehr hin, darüber. Immer mit Abstand zum Rand. */
private class MenuPositionProvider(private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = maxOf(marginPx, windowSize.width - popupContentSize.width - marginPx)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(marginPx, maxX)
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height - marginPx) below
        else (anchorBounds.top - popupContentSize.height).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}

/**
 * Unteres Menü (Sheet) mit Feder-Bewegung wie der Player: schiebt sich weich herauf, der Hintergrund dunkelt mit,
 * lässt sich mit dem Finger wegziehen (auch aus gescrollten Listen heraus) und läuft dabei mit der Fingergeschwindigkeit
 * weiter. Schließen per Tippen daneben, Zurück-Taste oder Wegziehen. Ersetzt ModalBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BounceSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }             // 0 = unten draußen, 1 = offen
    var drag by remember { mutableFloatStateOf(0f) }       // Zug nach unten in px
    var sheetH by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    var dragJob by remember { mutableStateOf<Job?>(null) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    val noRipple = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) { progress.animateTo(1f, sheetSpring()) }

    // Fenster erst entfernen, wenn das Menü fast ganz unten ist (die Feder klingt sonst noch lange unsichtbar nach)
    var dismissed by remember { mutableStateOf(false) }
    fun dismissOnce() {
        if (!dismissed) {
            dismissed = true
            onDismissRequest()
        }
    }

    fun closeSimple() {
        if (closing) return
        closing = true
        dragJob?.cancel()
        scope.launch {
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.002f)) {
                if (value < 0.02f) dismissOnce()
            }
        }
    }

    fun release(velocity: Float) {
        dragJob?.cancel()
        dragJob = scope.launch {
            val h = sheetH
            if (h > 0f && drag + velocity * 0.15f > h * 0.35f) {
                closing = true
                animate(drag, h, velocity, spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 1f)) { value, _ ->
                    drag = value
                    if (value > h * 0.98f) dismissOnce()
                }
            } else {
                animate(drag, 0f, velocity, spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = 0.5f)) { value, _ ->
                    drag = value
                }
            }
        }
    }

    // Wegziehen aus gescrollten Inhalten: Ist der Inhalt ganz oben, übernimmt das Menü den weiteren Zug nach unten
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || drag <= 0f || available.y >= 0f) return Offset.Zero
                dragJob?.cancel()
                val c = maxOf(available.y, -drag)
                drag += c
                return Offset(0f, c)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                dragJob?.cancel()
                drag += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0f) return Velocity.Zero
                release(available.y)
                return available
            }
        }
    }

    // Dialog statt Popup: Ein Popup-Fenster misst sich nur am sichtbaren Bereich ohne Navigationsleiste,
    // dadurch blieb unten ein Streifen. Das Dialog-Fenster ohne decorFitsSystemWindows deckt den ganzen Bildschirm ab.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { closeSimple() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxSheetH = maxHeight * 0.9f
            // Abdunkelung hinter dem Menü, Tipp darauf schließt
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val away = if (sheetH > 0f) (drag / sheetH).coerceIn(0f, 1f) else 0f
                        alpha = progress.value.coerceIn(0f, 1f) * (1f - away)
                    }
                    .background(scrimColor)
                    .clickable(interactionSource = noRipple, indication = null) { closeSimple() }
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetH)
                    .onSizeChanged { sheetH = it.height.toFloat() }
                    .graphicsLayer {
                        // bis zur ersten Messung unsichtbar, sonst blitzt das Menü kurz an seinem Platz
                        alpha = if (sheetH > 0f) 1f else 0f
                        translationY = (1f - progress.value) * sheetH + drag
                    }
                    .nestedScroll(connection)
                    .draggable(
                        state = rememberDraggableState { d ->
                            dragJob?.cancel()
                            drag = (drag + d).coerceAtLeast(0f)
                        },
                        orientation = Orientation.Vertical,
                        onDragStopped = { v -> release(v) }
                    )
                    .pointerInput(Unit) {},
                shape = BottomSheetDefaults.ExpandedShape,
                color = BottomSheetDefaults.ContainerColor,
                tonalElevation = BottomSheetDefaults.Elevation
            ) {
                Column(Modifier.padding(bottom = navBottom)) {
                    BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    content()
                }
            }
        }
    }
}
