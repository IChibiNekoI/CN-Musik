package com.example.musikplayer

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
