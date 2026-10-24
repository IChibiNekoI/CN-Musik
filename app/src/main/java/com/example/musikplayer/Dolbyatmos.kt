package com.example.musikplayer

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Erkennt, ob Dolby Atmos in den Geräteeinstellungen eingeschaltet ist.
 *
 * Android hat dafür keine einheitliche Schnittstelle: Jeder Hersteller (Xiaomi, OnePlus, Lenovo, Samsung ...) legt den
 * Schalter unter einem eigenen Namen in den Systemeinstellungen ab. Deshalb werden alle Einstellungen durchsucht, deren Name
 * "dolby" enthält und nach einem Schalter klingt (enable, switch, state ...). Ist einer davon an, gilt Dolby Atmos als aktiv.
 * Die gefundenen Namen stehen im Logcat unter dem Tag "DolbyAtmos".
 */
object DolbyAtmos {
    private const val TAG = "DolbyAtmos"
    private val NAME = Regex("dolby", RegexOption.IGNORE_CASE)
    private val SWITCH = Regex("enable|switch|state|status|toggle|onoff|on_off", RegexOption.IGNORE_CASE)
    private val NOT_A_SWITCH = Regex("support|avail|version|profile|capab|tuning|preset|mode|level|intensity", RegexOption.IGNORE_CASE)
    private val ON = setOf("1", "true", "on", "enabled", "yes")

    fun isEnabled(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        var anyOn = false
        for (uri in listOf(Settings.Global.CONTENT_URI, Settings.System.CONTENT_URI, Settings.Secure.CONTENT_URI)) {
            try {
                cr.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    val ni = c.getColumnIndex("name")
                    val vi = c.getColumnIndex("value")
                    if (ni < 0 || vi < 0) return@use
                    while (c.moveToNext()) {
                        val name = c.getString(ni) ?: continue
                        if (!NAME.containsMatchIn(name)) continue
                        val value = c.getString(vi)?.trim()?.lowercase()
                        Log.d(TAG, "Einstellung $name = $value")
                        if (SWITCH.containsMatchIn(name) && !NOT_A_SWITCH.containsMatchIn(name) && value in ON) anyOn = true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Einstellungen nicht lesbar: $uri (${e.javaClass.simpleName})")
            }
        }
        return anyOn
    }
}

/** true, solange Dolby Atmos am Gerät an ist. Aktualisiert sich selbst, wenn der Schalter im System umgelegt wird. */
@Composable
fun rememberDolbyAtmosEnabled(): State<Boolean> {
    val ctx = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(false) }
    DisposableEffect(ctx) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var job: Job? = null
        val refresh = {
            job?.cancel()
            job = scope.launch {
                delay(250)   // viele Einstellungen ändern sich gleichzeitig: kurz zusammenfassen
                state.value = DolbyAtmos.isEnabled(ctx)
            }
        }
        refresh()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
        val uris = listOf(Settings.Global.CONTENT_URI, Settings.System.CONTENT_URI, Settings.Secure.CONTENT_URI)
        uris.forEach {
            try {
                ctx.contentResolver.registerContentObserver(it, true, observer)
            } catch (_: Exception) {
            }
        }
        onDispose {
            ctx.contentResolver.unregisterContentObserver(observer)
            scope.cancel()
        }
    }
    return state
}

/** "Dolby Atmos" mit einem schlichten Doppel-D davor, gezeichnet (keine Bilddatei) */
@Composable
fun DolbyAtmosBadge(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 11.dp)) {
            val h = size.height
            val dw = 6.dp.toPx()
            val gap = size.width - 2 * dw
            drawD(color, Offset(0f, 0f), dw, h, leftSide = true)
            drawD(color, Offset(dw + gap, 0f), dw, h, leftSide = false)
        }
        Spacer(Modifier.width(5.dp))
        Text("Dolby", color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(" Atmos", color = color, fontSize = 15.sp, fontWeight = FontWeight.Light, maxLines = 1)
    }
}

/** Halbes "D": die flache Seite zeigt zur Mitte, die runde nach außen */
private fun DrawScope.drawD(color: androidx.compose.ui.graphics.Color, origin: Offset, w: Float, h: Float, leftSide: Boolean) {
    if (leftSide) {
        // rund nach links: Halbellipse, deren flache Seite bei x = origin.x + w liegt
        drawArc(color, startAngle = 90f, sweepAngle = 180f, useCenter = true, topLeft = Offset(origin.x, origin.y), size = Size(2 * w, h))
    } else {
        // rund nach rechts: flache Seite bei x = origin.x
        drawArc(color, startAngle = 270f, sweepAngle = 180f, useCenter = true, topLeft = Offset(origin.x - w, origin.y), size = Size(2 * w, h))
    }
}
