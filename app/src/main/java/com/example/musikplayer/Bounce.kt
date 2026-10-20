package com.example.musikplayer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Schaltet den Überscroll-Effekt des Systems für die Listen darin aus.
 * Die Listen nutzen stattdessen [bounceOverscroll]: Das sieht auf jedem Gerät gleich aus
 * (der System-Effekt fehlt bei manchen Herstellern) und doppelt sich nicht.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoSystemOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollConfiguration provides null, content = content)
}

/**
 * Leichter, verspielter Bounce am oberen und unteren Listenende.
 *
 * - Ziehen über den Rand: die Liste folgt mit Gummiband-Widerstand (zäher, je weiter man zieht).
 * - Loslassen: sie federt mit der Fingergeschwindigkeit zurück.
 * - Schnelles Wischen bis ans Ende: der Rest der Wischbewegung lässt die Liste kurz anstoßen und zurückfedern.
 * - Man kann die Liste jederzeit wieder festhalten.
 *
 * Muss zusammen mit [NoSystemOverscroll] direkt an der Liste verwendet werden.
 */
@Composable
fun Modifier.bounceOverscroll(): Modifier {
    val scope = rememberCoroutineScope()
    val limitPx = with(LocalDensity.current) { 96.dp.toPx() }
    // Überzug in px: positiv = oben über den Anfang hinaus, negativ = unten über das Ende hinaus
    var raw by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    val connection = remember {
        fun settle(velocity: Float, dir: Float) {
            job?.cancel()
            job = scope.launch {
                // leicht unterdämpft = kleiner, verspielter Nachschwinger; er wird nie auf die Gegenseite gezogen
                animate(raw, 0f, velocity, spring(dampingRatio = 0.6f, stiffness = 260f)) { v, _ ->
                    raw = if (dir > 0f) v.coerceAtLeast(0f) else v.coerceAtMost(0f)
                }
                raw = 0f
            }
        }
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || raw == 0f) return Offset.Zero
                // Finger hat die zurückfedernde Liste gefangen
                job?.cancel()
                val dy = available.y
                // Zurückziehen baut zuerst den Überzug ab, erst danach scrollt die Liste wieder
                if ((raw > 0f && dy < 0f) || (raw < 0f && dy > 0f)) {
                    val consumed = if (abs(dy) > abs(raw)) -raw else dy
                    raw += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Die Liste ist am Rand und der Finger zieht weiter
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                job?.cancel()
                raw += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (raw == 0f) return Velocity.Zero
                // Losgelassen, während die Liste gedehnt ist: mit der Fingergeschwindigkeit zurückfedern
                settle(available.y, sign(raw))
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y == 0f) return Velocity.Zero
                // Wischen hat das Ende erreicht und noch Schwung übrig: kurz anstoßen. Nur ein Teil, damit es leicht bleibt.
                settle(available.y * 0.35f, sign(available.y))
                return available
            }
        }
    }
    return this
        .nestedScroll(connection)
        .graphicsLayer { translationY = sign(raw) * rubberBand(abs(raw), limitPx) }
}
