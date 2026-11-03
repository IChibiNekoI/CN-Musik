package com.example.musikplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class OutputKind { HEADPHONES, SPEAKER, OTHER }

/** Hersteller, für die ein Logo statt des Namens gezeigt wird */
enum class OutputBrand { NONE, XIAOMI, SOUNDCORE }

/**
 * Das Gerät, über das gerade Musik ausgegeben wird: Anzeigename und Art (für das Symbol).
 * [brand] und [model]: Beginnt der Name mit einem bekannten Hersteller, steht dessen Logo statt des Wortes da,
 * [model] ist der Rest des Namens (aus "Xiaomi Buds 5" wird Logo + "Buds 5").
 */
data class OutputDevice(
    val name: String,
    val kind: OutputKind,
    val brand: OutputBrand = OutputBrand.NONE,
    val model: String = name
)

object OutputDevices {
    private val SPEAKER_NAME = Regex("speaker|soundbar|boom|lautsprecher|homepod|soundlink", RegexOption.IGNORE_CASE)

    // Hersteller am Anfang des Namens (auch "Anker soundcore ..."), samt Trennzeichen danach
    private val XIAOMI_PREFIX = Regex("^\\s*xiaomi[\\s\\-_:]*", RegexOption.IGNORE_CASE)
    private val SOUNDCORE_PREFIX = Regex("^\\s*(?:anker[\\s\\-_:]*)?soundcore[\\s\\-_:]*", RegexOption.IGNORE_CASE)

    /** Trennt einen bekannten Hersteller vom Rest des Namens ab. Unbekannte Namen bleiben unverändert. */
    fun splitBrand(name: String): Pair<OutputBrand, String> {
        XIAOMI_PREFIX.find(name)?.let { return OutputBrand.XIAOMI to name.substring(it.range.last + 1).trim() }
        SOUNDCORE_PREFIX.find(name)?.let { return OutputBrand.SOUNDCORE to name.substring(it.range.last + 1).trim() }
        return OutputBrand.NONE to name
    }

    /** Bluetooth-Ausgaben: A2DP, Telefonie (SCO), BLE-Headset, BLE-Lautsprecher, BLE-Broadcast und Hörgeräte */
    private fun isBluetooth(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
        26, 27, 30 -> true
        else -> false
    }

    /**
     * Das über Bluetooth verbundene Ausgabegerät, sonst null. Lautsprecher des Geräts, Kabel-Kopfhörer, USB und HDMI
     * werden bewusst nicht angezeigt.
     */
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
        // Rückfall (und wenn die Weiterleitung noch nicht steht): irgendein verbundenes Bluetooth-Gerät
        if (dev == null || !isBluetooth(dev.type)) dev = outputs.firstOrNull { isBluetooth(it.type) }
        if (dev == null || !isBluetooth(dev.type)) return null
        val name = dev.productName?.toString()?.trim().orEmpty().ifBlank { "Bluetooth" }
        val speaker = dev.type == 27 || SPEAKER_NAME.containsMatchIn(name)
        val (brand, model) = splitBrand(name)
        return OutputDevice(name, if (speaker) OutputKind.SPEAKER else OutputKind.HEADPHONES, brand, model)
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

/**
 * Logo eines Herstellers. Die Logos sind auf die Pixel zugeschnitten; die Höhe wird nicht mehr "nach Gefühl" je Logo
 * gewählt, sondern aus der x-Höhe (Höhe der Kleinbuchstaben ohne Ober-/Unterlänge) berechnet. So haben beide Logos
 * dieselbe Buchstabenhöhe, egal wie viel Platz ein Aufstrich (d bei soundcore, i-Punkt bei Xiaomi) im Bild braucht.
 * Der Unterrand beider Logos ist die Schriftgrundlinie, daher sitzen sie auf der Grundlinie des Modelltextes.
 */
@Composable
private fun RowScope.BrandLogo(brand: OutputBrand) {
    // x-Höhe als Anteil der Bildhöhe (aus den Pixeln der PNGs gemessen): soundcore ~41/64, Xiaomi ~62/64
    val (res, xHeightFraction, label) = when (brand) {
        OutputBrand.XIAOMI -> Triple(R.drawable.logo_xiaomi, 0.97f, "Xiaomi")
        OutputBrand.SOUNDCORE -> Triple(R.drawable.logo_soundcore, 0.64f, "soundcore")
        OutputBrand.NONE -> return
    }
    val painter = painterResource(res)
    val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 6f }
    val height = LOGO_X_HEIGHT / xHeightFraction
    Image(
        painter = painter,
        contentDescription = label,
        modifier = Modifier
            .height(height)
            .aspectRatio(ratio)
            .alignBy { it.measuredHeight }, // Grundlinie = Unterkante des Logos
        contentScale = ContentScale.Fit
    )
}

/** Gewünschte x-Höhe der Logos. labelMedium (12sp) hat ca. 6,3 dp; etwas darüber, damit das Logo optisch gleich groß wirkt. */
private val LOGO_X_HEIGHT = 6.8.dp

/**
 * Name der angeschlossenen Kopfhörer mit Symbol davor. Bei Xiaomi und soundcore steht das Logo statt des
 * Herstellernamens, danach das Modell (z. B. Logo + "Buds 5"). Wird mittig zwischen die Zeiten gesetzt.
 * Symbol, Logo und Text sind an der Schriftgrundlinie ausgerichtet.
 */
@Composable
fun OutputDeviceLabel(modifier: Modifier = Modifier) {
    val device by rememberOutputDevice()
    // Nur bei Kopfhörern (Bluetooth, Kabel, USB). Bei Lautsprecher und allem anderen bleibt die Mitte leer.
    val d = device?.takeIf { it.kind == OutputKind.HEADPHONES } ?: return
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val density = LocalDensity.current
    // Das Symbol soll auf der Mitte der Großbuchstaben liegen: Grundlinie = Symbolmitte + halbe Versalhöhe
    val halfCapPx = with(density) { 4.2.dp.roundToPx() }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.alignBy { it.measuredHeight / 2 + halfCapPx }) {
            HeadphonesIcon(iconSize = 14.dp, tint = color)
        }
        Spacer(Modifier.width(4.dp))
        if (d.brand != OutputBrand.NONE) {
            BrandLogo(d.brand)
            if (d.model.isNotEmpty()) Spacer(Modifier.width(4.dp))
        }
        // Ohne Hersteller steht der ganze Name da; mit Hersteller nur das Modell (kann leer sein, dann bleibt das Logo allein)
        val text = if (d.brand != OutputBrand.NONE) d.model else d.name
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline()
            )
        }
    }
}
