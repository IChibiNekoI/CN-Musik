package com.example.musikplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class OutputKind { HEADPHONES, SPEAKER, OTHER }

/** Das Gerät, über das gerade Musik ausgegeben wird: Anzeigename und Art (für das Symbol) */
data class OutputDevice(val name: String, val kind: OutputKind)

object OutputDevices {
    private val SPEAKER_NAME = Regex("speaker|soundbar|boom|lautsprecher|homepod|soundlink", RegexOption.IGNORE_CASE)

    /** Je kleiner, desto eher wird das Gerät als aktuelle Ausgabe angenommen (nur als Rückfall vor Android 13) */
    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID -> 0
        26, 27, 30 -> 0    // BLE-Headset, BLE-Lautsprecher, BLE-Broadcast
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> 2
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 5
        else -> 99
    }

    fun current(ctx: Context): OutputDevice? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        // Ab Android 13 sagt das System selbst, wohin Musik gerade geht
        var dev: AudioDeviceInfo? = null
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val routed = am.getAudioDevicesForAttributes(attrs).firstOrNull()
                if (routed != null) {
                    dev = outputs.firstOrNull { it.type == routed.type && it.address == routed.address }
                        ?: outputs.firstOrNull { it.type == routed.type }
                }
            } catch (_: Exception) {
            }
        }
        if (dev == null) dev = outputs.filter { rank(it.type) < 99 }.minByOrNull { rank(it.type) }
        if (dev == null) return null
        val product = dev.productName?.toString()?.trim().orEmpty()
        return when (dev.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                OutputDevice(ctx.getString(R.string.output_speaker), OutputKind.SPEAKER)
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                // bei Kabel-Kopfhörern nennt das System meist nur den Gerätenamen des Handys
                OutputDevice(ctx.getString(R.string.output_headphones), OutputKind.HEADPHONES)
            AudioDeviceInfo.TYPE_USB_HEADSET ->
                OutputDevice(product.ifBlank { ctx.getString(R.string.output_headphones) }, OutputKind.HEADPHONES)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID, 26, 30 -> {
                val name = product.ifBlank { "Bluetooth" }
                OutputDevice(name, if (SPEAKER_NAME.containsMatchIn(name)) OutputKind.SPEAKER else OutputKind.HEADPHONES)
            }
            27 -> OutputDevice(product.ifBlank { "Bluetooth" }, OutputKind.SPEAKER)
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> OutputDevice(product.ifBlank { "HDMI" }, OutputKind.OTHER)
            else -> OutputDevice(product.ifBlank { ctx.getString(R.string.output_speaker) }, OutputKind.OTHER)
        }
    }
}

/** Das aktuelle Ausgabegerät. Aktualisiert sich, wenn Geräte angeschlossen oder getrennt werden. */
@Composable
fun rememberOutputDevice(): State<OutputDevice?> {
    val ctx = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(OutputDevices.current(ctx)) }
    DisposableEffect(ctx) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable { state.value = OutputDevices.current(ctx) }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
            private fun update() {
                refresh.run()
                // Die Weiterleitung folgt dem Anschließen mit etwas Verzug
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 800)
            }
        }
        am?.registerAudioDeviceCallback(callback, handler)
        onDispose {
            handler.removeCallbacks(refresh)
            am?.unregisterAudioDeviceCallback(callback)
        }
    }
    return state
}

/** Name des Ausgabegeräts, bei Kopfhörern mit Symbol davor. Wird mittig zwischen die Zeiten gesetzt. */
@Composable
fun OutputDeviceLabel(modifier: Modifier = Modifier) {
    val device by rememberOutputDevice()
    val d = device ?: return
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (d.kind == OutputKind.HEADPHONES) {
            HeadphonesIcon(iconSize = 18.dp, tint = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            d.name,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
