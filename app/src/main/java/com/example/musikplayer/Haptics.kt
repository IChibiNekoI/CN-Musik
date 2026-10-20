package com.example.musikplayer

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Haptisches Feedback: ein sehr leichtes "Ticken" beim Tippen auf Tasten und beim Verschieben von Titeln.
 * Es nutzt den vorgefertigten Effekt EFFECT_TICK des Geräts, das ist der sanfteste, den Android kennt.
 * Standardmäßig ausgeschaltet, einschaltbar in den Einstellungen.
 */
object Haptics {
    /** Ein/aus (aus dem Speicher geladen, siehe [load]) */
    var enabled by mutableStateOf(false)
        private set

    private var loaded = false
    private var lastTickMs = 0L
    private var vibrator: Vibrator? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Gespeicherte Wahl einmal laden */
    fun load(ctx: Context) {
        if (!loaded) {
            loaded = true
            enabled = prefs(ctx).getBoolean("haptics", false)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        loaded = true
        enabled = on
        prefs(ctx).edit().putBoolean("haptics", on).apply()
    }

    /** Ganz leichtes Ticken. Tut nichts, wenn das Feedback aus ist oder das Gerät nicht vibrieren kann. */
    fun tick(ctx: Context) {
        load(ctx)
        if (!enabled) return
        // viele Aufrufe kurz hintereinander (z. B. beim schnellen Verschieben) werden zusammengefasst
        val now = SystemClock.uptimeMillis()
        if (now - lastTickMs < 40) return
        lastTickMs = now
        try {
            val v = vibrator ?: ctx.applicationContext
                .getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.also { vibrator = it }
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        } catch (e: Exception) {
        }
    }
}

/**
 * Leichtes Ticken, wenn man beim Scrollen oben oder unten in einer Liste ankommt.
 * Gilt nur, wenn man selbst scrollt oder gerade losgelassen hat (Fling), nicht wenn sich die Liste von selbst ändert.
 * Läuft über den Haptik-Schalter in den Einstellungen.
 */
@Composable
fun EdgeHaptics(state: ScrollableState) {
    val ctx = LocalContext.current
    LaunchedEffect(state) {
        var lastBack = state.canScrollBackward
        var lastForward = state.canScrollForward
        var wasScrolling = false
        snapshotFlow { Triple(state.canScrollBackward, state.canScrollForward, state.isScrollInProgress) }
            .collect { (back, forward, scrolling) ->
                val arrived = (lastBack && !back) || (lastForward && !forward)
                if (arrived && (scrolling || wasScrolling)) Haptics.tick(ctx)
                lastBack = back
                lastForward = forward
                wasScrolling = scrolling
            }
    }
}

/** LazyColumn mit Ticken am oberen und unteren Ende */
@Composable
fun HapticLazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    EdgeHaptics(state)
    NoSystemOverscroll {
        LazyColumn(modifier.bounceOverscroll().topEdgeFade(state), state = state, contentPadding = contentPadding, content = content)
    }
}

/** LazyVerticalGrid mit Ticken am oberen und unteren Ende */
@Composable
fun HapticLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyGridScope.() -> Unit
) {
    val state = rememberLazyGridState()
    EdgeHaptics(state)
    NoSystemOverscroll {
        LazyVerticalGrid(
            columns = columns,
            modifier = modifier.bounceOverscroll().topEdgeFade(state),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
