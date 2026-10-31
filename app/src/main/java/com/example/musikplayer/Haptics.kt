package com.example.musikplayer

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
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

object Haptics {

    /** Globaler Schalter (Einstellungen). Wird in [load] aus den SharedPreferences gelesen. */
    var enabled by mutableStateOf(true)
        private set

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        enabled = prefs(ctx).getBoolean("haptics", true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        prefs(ctx).edit().putBoolean("haptics", on).apply()
    }

    private fun getVibrator(context: Context): Vibrator? {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Als Touch-Rückmeldung einstufen, damit das System sie wie normales Tastenfeedback behandelt
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            vibrator.vibrate(effect)
        }
    }

    /** Notlösung ohne Fenster: sehr kurzer Einzelimpuls. */
    private fun pulse(context: Context, durationMs: Long, amplitude: Int) {
        val vibrator = getVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            val amp = if (vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
            vibrate(vibrator, VibrationEffect.createOneShot(durationMs, amp))
        } catch (e: Exception) {
        }
    }

    /**
     * System-Haptik wie Flutters HapticFeedback in Namida (Modus "haptic_feedback"): Das System spielt
     * den vom Hersteller auf den Motor abgestimmten Effekt, knackig und trocken.
     */
    private fun feedback(context: Context, constant: Int, fbMs: Long, fbAmp: Int) {
        if (!enabled) return
        val view = context.findActivity()?.window?.decorView
        if (view == null) {
            pulse(context, fbMs, fbAmp)
            return
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    // Stufen wie in Namidas VibratorInterfaceHapticFeedback
    fun verylight(context: Context) = feedback(context, HapticFeedbackConstants.KEYBOARD_TAP, 10, 10)
    fun light(context: Context) = feedback(context, HapticFeedbackConstants.KEYBOARD_TAP, 10, 20)
    fun medium(context: Context) = feedback(context, HapticFeedbackConstants.VIRTUAL_KEY, 20, 40)
    fun high(context: Context) = feedback(context, HapticFeedbackConstants.LONG_PRESS, 20, 50)
    fun veryhigh(context: Context) = feedback(context, HapticFeedbackConstants.LONG_PRESS, 20, 80)

    // Aliase für bestehende Aufrufe im Code
    fun click(context: Context) = medium(context)
    fun tick(context: Context) = light(context)
    fun snap(context: Context) = high(context)
}

/**
 * Haptik für den Fortschrittsbalken: Beim Ziehen gibt es in jedem Raster-Schritt exakt dieselbe Vibration wie beim Einrasten
 * des Players ([Haptics.snap]).
 */
class ScrubHaptics(private val ctx: Context) {
    private var lastStep = -1
    private val steps = 40   // Rasterweite beim Ziehen

    fun tap() = Haptics.click(ctx)

    fun start(fraction: Float) {
        lastStep = (fraction * steps).toInt()
        Haptics.snap(ctx)
    }

    fun move(fraction: Float) {
        val step = (fraction * steps).toInt()
        if (step == lastStep) return
        lastStep = step
        Haptics.snap(ctx)
    }
}

/**
 * Vibration, wenn man beim Scrollen oben oder unten in einer Liste ankommt, dieselbe wie beim Ziehen am Fortschrittsbalken
 * und beim Einrasten des Players ([Haptics.snap]).
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
                if (arrived && (scrolling || wasScrolling)) Haptics.snap(ctx)
                lastBack = back
                lastForward = forward
                wasScrolling = scrolling
            }
    }
}

/** Liste: vibriert nur, wenn man oben oder unten ankommt, nicht während des Scrollens ([EdgeHaptics]). */
@Composable
fun HapticLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit
) {
    EdgeHaptics(state)
    LazyColumn(modifier = modifier, state = state, contentPadding = contentPadding, content = content)
}

/** Raster: vibriert nur, wenn man oben oder unten ankommt, nicht während des Scrollens ([EdgeHaptics]). */
@Composable
fun HapticLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyGridScope.() -> Unit
) {
    EdgeHaptics(state)
    LazyVerticalGrid(
        columns = columns,
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content
    )
}
