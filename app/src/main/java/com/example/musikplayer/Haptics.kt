package com.example.musikplayer

import android.content.Context
import android.media.AudioAttributes
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
 * Haptisches Feedback im Stil von "Micro-Haptics": winzige, knackige Impulse, die dem Finger nur bestätigen,
 * dass etwas ausgelöst wurde. Nie ein Brummen.
 *
 * Drei Stufen:
 *  - [tick]  : sehr leicht (Raster beim Ziehen, Listenende, Tausch beim Sortieren)
 *  - [click] : kurz und knackig wie ein mechanischer Schalter (Tasten, Schalter, Reiter)
 *  - [snap]  : etwas voller (Player rastet ein, Titel angehoben/abgelegt)
 *
 * Unabhängig von den Android-Systemeinstellungen ("Touch-Feedback", "Berührungsfeedback" usw.):
 * Der Vibrator wird direkt angesprochen, und zwar mit einer ausdrücklichen Verwendung ([ATTRS]).
 * Ohne Angabe ordnet Android kurze Vibrationen selbst "Touch-Feedback" zu und unterdrückt sie, sobald
 * der Nutzer das im System abgeschaltet hat. Nur der Schalter in der App entscheidet.
 * Die Effekte kommen aus den Grundbausteinen (Primitives) des Vibrationsmotors; wo es die nicht gibt,
 * folgt ein ganz kurzer Impuls mit passender Stärke.
 */
object Haptics {
    /** Ein/aus (aus dem Speicher geladen, siehe [load]) */
    var enabled by mutableStateOf(false)
        private set

    /**
     * Verwendung der Vibration. ALARM wird von Android nie wegen der Touch-Feedback-Einstellung, des Lautlos-Modus
     * oder des Energiesparmodus unterdrückt. Falls das stören sollte (z. B. wegen einer eigenen Systemlautstärke
     * für Alarme), lässt sich hier [AudioAttributes.USAGE_MEDIA] einsetzen. Dann gilt aber die Medien-Einstellung des Systems.
     */
    private val ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Grundbaustein, Stärke (0..1) und Ersatz für Geräte ohne Grundbausteine (Dauer in ms, Stärke 1..255) */
    private enum class Kind(val primitive: Int, val scale: Float, val ms: Long, val amp: Int) {
        TICK(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f, 8L, 70),
        CLICK(VibrationEffect.Composition.PRIMITIVE_TICK, 0.95f, 10L, 140),
        SNAP(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f, 14L, 190)
    }

    private var loaded = false
    private var lastMs = 0L
    private var vibrator: Vibrator? = null
    private val effects = HashMap<Kind, VibrationEffect>()

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

    /** Sehr leichtes Ticken */
    fun tick(ctx: Context) = fire(ctx, Kind.TICK)

    /** Kurzer, knackiger Klick wie bei einem mechanischen Schalter */
    fun click(ctx: Context) = fire(ctx, Kind.CLICK)

    /** Etwas volleres Einrasten */
    fun snap(ctx: Context) = fire(ctx, Kind.SNAP)

    @Suppress("DEPRECATION")   // vibrate(Effekt, AudioAttributes): nur so lässt sich die Verwendung festlegen
    private fun fire(ctx: Context, kind: Kind) {
        load(ctx)
        if (!enabled) return
        // viele Aufrufe kurz hintereinander (z. B. beim schnellen Ziehen) werden zusammengefasst
        val now = SystemClock.uptimeMillis()
        if (now - lastMs < 25) return
        lastMs = now
        try {
            val v = vibrator ?: ctx.applicationContext
                .getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?.also { vibrator = it }
            if (v == null || !v.hasVibrator()) return
            val effect = effects.getOrPut(kind) { build(v, kind) }
            v.vibrate(effect, ATTRS)
        } catch (e: Exception) {
        }
    }

    private fun build(v: Vibrator, k: Kind): VibrationEffect = when {
        v.areAllPrimitivesSupported(k.primitive) ->
            VibrationEffect.startComposition().addPrimitive(k.primitive, k.scale).compose()
        v.hasAmplitudeControl() -> VibrationEffect.createOneShot(k.ms, k.amp)
        // ohne Stärkeregelung: kurz halten, aber lang genug, dass der Motor überhaupt anläuft
        else -> VibrationEffect.createOneShot(k.ms.coerceAtLeast(10L), VibrationEffect.DEFAULT_AMPLITUDE)
    }
}

/**
 * Haptik beim Ziehen an einem Fortschrittsbalken: ein leichtes Raster alle 1/[steps] der Strecke,
 * ein Klick beim Anfassen und Antippen, ein Einrasten, wenn der Finger an den Anfang oder das Ende kommt.
 */
class ScrubHaptics(private val ctx: Context, private val steps: Int = 40) {
    private var lastStep = 0
    private var atEdge = false

    private fun edge(f: Float) = f <= 0.002f || f >= 0.998f

    fun tap() = Haptics.click(ctx)

    fun start(f: Float) {
        lastStep = (f * steps).toInt()
        atEdge = edge(f)
        Haptics.click(ctx)
    }

    fun move(f: Float) {
        val e = edge(f)
        val step = (f * steps).toInt()
        if (e && !atEdge) Haptics.snap(ctx)
        else if (!e && step != lastStep) Haptics.tick(ctx)
        atEdge = e
        lastStep = step
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
