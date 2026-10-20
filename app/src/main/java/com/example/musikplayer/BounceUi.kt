package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .bounceClick(pressedScale = 0.8f, haptic = false, rotate = rotate, onClick = onClick),
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
