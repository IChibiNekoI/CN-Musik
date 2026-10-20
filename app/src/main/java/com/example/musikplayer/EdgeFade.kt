package com.example.musikplayer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Höhe des weichen Übergangs am oberen Listenrand */
private val TOP_FADE_HEIGHT = 32.dp

/**
 * Weicher Übergang am oberen Rand einer Liste: Was nach oben wegscrollt, blendet aus, statt hart abgeschnitten zu werden.
 * Steht die Liste ganz oben, passiert nichts. Je weiter gescrollt, desto stärker der Übergang (Stärke wird nur beim Zeichnen gelesen).
 */
fun Modifier.topEdgeFade(state: LazyListState, height: Dp = TOP_FADE_HEIGHT): Modifier =
    topEdgeFade(height) { fadePx ->
        if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
    }

fun Modifier.topEdgeFade(state: LazyGridState, height: Dp = TOP_FADE_HEIGHT): Modifier =
    topEdgeFade(height) { fadePx ->
        if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
    }

private fun Modifier.topEdgeFade(height: Dp, strength: (fadePx: Float) -> Float): Modifier = this
    // eigene Ebene, damit der Verlauf den Inhalt ausblendet und nicht den Hintergrund dahinter einfärbt
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        val s = strength(h)
        if (s > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(0f to Color.Black.copy(alpha = 1f - s), 1f to Color.Black),
                    startY = 0f,
                    endY = h
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, h),
                blendMode = BlendMode.DstIn
            )
        }
    }
