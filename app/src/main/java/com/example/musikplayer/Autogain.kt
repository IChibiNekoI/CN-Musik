package com.example.musikplayer

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kyant.taglib.TagLib
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.tanh

/*
 * Auto-Gain: gleicht die Lautstärke zwischen Titeln an.
 *
 * 1. Messen: Pro Titel wird einmal die Lautheit bestimmt (ReplayGain-Tag, falls vorhanden, sonst eigene Messung
 *    nach ITU-R BS.1770 an einem Ausschnitt von etwa 40 Sekunden aus der Mitte). Das Ergebnis wird gespeichert.
 * 2. Rechnen: Aus Lautheit und Spitzenpegel ergibt sich eine Verstärkung in dB (Ziel -14 LUFS, höchstens +6 dB
 *    und nie so viel, dass der Titel übersteuert).
 * 3. Anwenden: Ein Audio-Prozessor in der Ausgabe des Players multipliziert das Signal und blendet Änderungen weich ein.
 */

/** Zielpegel in LUFS, auf den Titel gebracht werden. */
private const val TARGET_LUFS = -14f

/** Stärkste Anhebung und Absenkung in dB. */
private const val MAX_BOOST_DB = 6f
private const val MAX_CUT_DB = -20f

/** Länge des Messausschnitts in Mikrosekunden. */
private const val WINDOW_US = 40_000_000L

/** Verstärkung in dB aus gemessener Lautheit und Spitzenpegel (linear, 0 = unbekannt). */
fun autoGainDb(lufs: Float, peak: Float): Float {
    var g = (TARGET_LUFS - lufs).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
    if (peak > 0f) {
        // So weit anheben, dass der lauteste Moment gerade nicht übersteuert
        g = min(g, -20f * log10(peak))
    } else if (g > 0f) {
        g = min(g, 3f)   // Spitzenpegel unbekannt: nur vorsichtig anheben
    }
    return g
}

/**
 * Multipliziert das Signal mit einer Verstärkung. Änderungen im laufenden Titel (z. B. Auto-Gain an/aus) blendet er weich
 * ein. An der Titelgrenze springt die Verstärkung dagegen ohne Übergang auf den Wert des neuen Titels, und zwar schon
 * bei seinem ersten Sample (siehe [onStreamBoundary]). Ein sanfter Begrenzer fängt Spitzen ab.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile private var targetGain = 1f
    private var currentGain = 1f
    private var smoothing = 0.0005f
    private var isFloat = false

    // Verstärkung des laufenden und des nächsten Titels, vom Controller gesetzt (linear)
    @Volatile private var curGain = 1f
    @Volatile private var nextGain = 1f

    // Titelgrenze schon überschritten, die Wechsel-Meldung des Players steht noch aus: Der Wert des neuen Titels bleibt
    @Volatile private var held = false
    @Volatile private var heldAt = 0L

    // Zeitpunkt des letzten Sprungs oder Neustarts: Der nächste Anfang gehört dann zum laufenden Titel, nicht zum nächsten
    @Volatile private var skipAt = -100_000L

    // Position des laufenden Titels, um unpassende Meldungen mitten im Titel zu erkennen
    @Volatile private var anchorPos = 0L
    @Volatile private var anchorDur = 0L
    @Volatile private var anchorAt = 0L
    @Volatile private var anchorPlaying = false

    private fun lin(db: Float) = 10f.pow(db / 20f)

    /** Setzt die Verstärkung sofort und fest (z. B. Auto-Gain aus), jeder Wartezustand endet. Darf von jedem Thread kommen. */
    fun setGainDb(db: Float) {
        val g = lin(db)
        curGain = g
        nextGain = g
        held = false
        targetGain = g
    }

    /** Verstärkung (dB) des laufenden und des nächsten Titels. Wirkt im laufenden Titel weich. */
    fun setGains(curDb: Float, nextDb: Float) {
        curGain = lin(curDb)
        nextGain = lin(nextDb)
        if (!held) targetGain = curGain
    }

    /** Ein Titelwechsel (automatisch) wurde gemeldet: Der Wert des neuen Titels gilt jetzt auch für den Controller. */
    fun itemChanged() {
        held = false
    }

    /** Der Nutzer hat gesprungen oder eine neue Warteschlange gestartet: Der nächste Anfang gehört zum laufenden Titel. */
    fun markSkip() {
        skipAt = SystemClock.elapsedRealtime()
        held = false
    }

    fun setPlayback(positionMs: Long, durationMs: Long, playing: Boolean) {
        anchorPos = positionMs
        anchorDur = durationMs
        anchorAt = SystemClock.elapsedRealtime()
        anchorPlaying = playing
    }

    /**
     * Wird vom Wiedergabe-Thread aufgerufen, kurz bevor das erste Audio eines neuen Titels in diese Kette gelangt.
     * Die Wechsel-Meldung des Players kommt erst, wenn man den Titel hört, also später, weil der Player vorausrechnet.
     * Deshalb setzt diese Stelle die Verstärkung schon für das allererste Sample, ohne Übergang.
     */
    fun onStreamBoundary() {
        val now = SystemClock.elapsedRealtime()
        when {
            // Sprung oder Start: Der neue Anfang gehört zum Titel, der gerade als laufend gilt
            now - skipAt < 2_000L -> { targetGain = curGain; held = false }
            // Fortlaufender Wechsel: nur, wenn der laufende Titel fast zu Ende ist, sonst ist die Meldung unpassend
            plausibleEnd(now) -> { targetGain = nextGain; held = true; heldAt = now }
            else -> return
        }
        currentGain = targetGain
    }

    private fun plausibleEnd(now: Long): Boolean {
        if (anchorDur <= 0L) return true
        val pos = anchorPos + if (anchorPlaying) now - anchorAt else 0L
        return anchorDur - pos < 12_000L
    }

    override fun onFlush() {
        // Nach Sprung oder Neustart sofort auf dem Zielwert beginnen, nicht von einem alten Wert aus
        currentGain = targetGain
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> {
                isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                // Zeitkonstante etwa 80 ms: Änderungen klingen weich statt als Sprung
                smoothing = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * 0.08))).toFloat()
                inputAudioFormat
            }
            // Andere Formate (z. B. 24 Bit) laufen unverändert durch
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        // Kam die Wechsel-Meldung des Players nie (sollte nicht vorkommen), den Wartezustand nach einer Weile beenden
        if (held && SystemClock.elapsedRealtime() - heldAt > 6_000L) {
            held = false
            targetGain = curGain
        }
        val target = targetGain
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        var g = currentGain
        for (f in 0 until frames) {
            g += (target - g) * smoothing
            for (c in 0 until channels) {
                if (isFloat) {
                    out.putFloat(limit(src.getFloat() * g))
                } else {
                    val v = limit(src.getShort() / 32768f * g)
                    out.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        currentGain = g
        // Reste, die kein ganzer Frame sind, unverändert durchreichen
        while (src.hasRemaining()) out.put(src.get())
        out.flip()
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.95f) return x
        val y = 0.95f + 0.05f * tanh((a - 0.95f) / 0.05f)
        return if (x < 0f) -y else y
    }
}

/**
 * Gespeicherte Messwerte je Titel: Lautheit in LUFS und Spitzenpegel (linear, -1 = unbekannt).
 * Ist eine Messung gescheitert (Datei nicht lesbar, Format unbekannt), steht NaN als Lautheit drin, damit der Titel
 * nicht bei jedem Start erneut versucht wird. Solche Titel laufen ohne Anpassung.
 */
object LoudnessCache {
    class Entry(val lufs: Float, val peak: Float) {
        val failed: Boolean get() = lufs.isNaN()
    }

    val FAILED = Entry(Float.NaN, -1f)

    /** Wird nach jedem neuen Messwert aufgerufen (vom Player-Controller genutzt, um die Verstärkung neu zu setzen). */
    @Volatile
    var onChange: (() -> Unit)? = null

    private val map = ConcurrentHashMap<Long, Entry>()
    private var loaded = false
    private var file: File? = null

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, "loudness.txt").also { file = it }
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val p = line.split(',')
                if (p.size == 3) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    val l = p[1].toFloatOrNull() ?: return@forEachLine
                    val pk = p[2].toFloatOrNull() ?: return@forEachLine
                    map[id] = Entry(l, pk)
                }
            }
        } catch (e: Exception) {
            // Beschädigte Datei: vorhandene Werte behalten, Rest wird neu gemessen
        }
    }

    fun get(ctx: Context, id: Long): Entry? {
        ensureLoaded(ctx)
        return map[id]
    }

    @Synchronized
    fun put(ctx: Context, id: Long, entry: Entry) {
        ensureLoaded(ctx)
        map[id] = entry
        try {
            file?.appendText("$id,${entry.lufs},${entry.peak}\n")
        } catch (e: Exception) {
            // Speichern ist optional: ohne Datei wird beim nächsten Start neu gemessen
        }
        onChange?.invoke()
    }
}

/**
 * Misst im Hintergrund alle Titel der Bibliothek, die noch keinen Wert haben. Bereits gemessene Titel (auch
 * gescheiterte) werden übersprungen, beim Start wird also nur Neues gemessen. Läuft sehr langsam und mit niedriger
 * Priorität, wartet auf Messungen für den laufenden Titel und pausiert im Energiesparmodus.
 */
object LoudnessScanner {
    /** Titel, die gerade gemessen werden (geteilt mit dem Controller, damit nichts doppelt gemessen wird). */
    val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Anzahl dringender Messungen (laufender und nächste Titel): Solange eine läuft, pausiert der Hintergrundlauf. */
    val urgent = AtomicInteger(0)

    @Volatile private var generation = 0

    fun stop() {
        generation++
    }

    fun start(ctx: Context, items: List<Pair<Long, Uri>>) {
        val app = ctx.applicationContext
        val gen = ++generation
        if (items.isEmpty()) return
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            fun cancelled() = gen != generation
            // Die Vorab-Messung der ganzen Bibliothek ist reine Vorarbeit (der laufende und die nächsten Titel werden
            // ohnehin sofort gemessen). Sie läuft deshalb nur am Ladegerät, nicht im Energiesparmodus und nicht,
            // solange eine dringende Messung läuft.
            fun mayScan() = urgent.get() == 0 && pm?.isPowerSaveMode != true && (bm?.isCharging ?: true)
            fun nap(ms: Long): Boolean {
                try { Thread.sleep(ms) } catch (e: InterruptedException) { return false }
                return true
            }
            // Erst die App in Ruhe starten lassen
            if (!nap(8_000)) return@Thread
            for ((id, uri) in items) {
                if (cancelled()) return@Thread
                if (LoudnessCache.get(app, id) != null) continue
                while (!cancelled() && !mayScan()) {
                    if (!nap(5_000)) return@Thread
                }
                if (cancelled()) return@Thread
                if (!inFlight.add(id)) continue
                try {
                    val entry = try { LoudnessAnalyzer.analyze(app, uri, 0L) } catch (e: Exception) { null }
                    LoudnessCache.put(app, id, entry ?: LoudnessCache.FAILED)
                } finally {
                    inFlight.remove(id)
                }
                if (!nap(250)) return@Thread   // Pause zwischen den Titeln: der Prozessor wird nicht dauerhaft ausgelastet
            }
        }.apply {
            name = "LoudnessScan"
            isDaemon = true
            start()
        }
    }
}

/** Misst die Lautheit eines Titels: erst ReplayGain-Tag, sonst eigene Messung an einem Ausschnitt. */
object LoudnessAnalyzer {

    fun analyze(ctx: Context, uri: Uri, durationHintMs: Long): LoudnessCache.Entry? =
        fromReplayGain(ctx, uri) ?: measure(ctx, uri, durationHintMs)

    /** REPLAYGAIN_TRACK_GAIN bezieht sich auf -18 LUFS. */
    private fun fromReplayGain(ctx: Context, uri: Uri): LoudnessCache.Entry? {
        return try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val props = TagLib.getMetadata(pfd.dup().detachFd())?.propertyMap ?: return@use null
                val gain = props["REPLAYGAIN_TRACK_GAIN"]?.firstOrNull()?.let { parseNumber(it) } ?: return@use null
                val peak = props["REPLAYGAIN_TRACK_PEAK"]?.firstOrNull()?.let { parseNumber(it) } ?: -1f
                LoudnessCache.Entry(-18f - gain, if (peak > 0f) peak else -1f)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseNumber(s: String): Float? =
        Regex("-?\\d+([.,]\\d+)?").find(s)?.value?.replace(',', '.')?.toFloatOrNull()

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    /** K-Gewichtung nach BS.1770 für beliebige Abtastrate: Hochton-Anhebung und Hochpass. */
    private fun kWeighting(fs: Double): Pair<Biquad, Biquad> {
        val f0 = 1681.974450955533
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        val shelf = Biquad(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0
        )
        val f1 = 38.13547087602444
        val q1 = 0.5003270373238773
        val k1 = tan(PI * f1 / fs)
        val a01 = 1.0 + k1 / q1 + k1 * k1
        val hp = Biquad(1.0, -2.0, 1.0, 2.0 * (k1 * k1 - 1.0) / a01, (1.0 - k1 / q1 + k1 * k1) / a01)
        return shelf to hp
    }

    private fun measure(ctx: Context, uri: Uri, durationHintMs: Long): LoudnessCache.Entry? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION)
            else durationHintMs * 1000L
            // Ausschnitt aus der Mitte des Titels (bei kurzen Titeln alles)
            val startUs = if (durUs > 90_000_000L) (durUs * 0.25).toLong() else 0L
            if (startUs > 0L) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var floatPcm = false

            var shelf = Array(2) { kWeighting(sampleRate.toDouble()).first }
            var hp = Array(2) { kWeighting(sampleRate.toDouble()).second }
            var hopFrames = sampleRate / 10   // 100 ms
            var hopCount = 0
            var hopSum = 0.0
            val hops = ArrayList<Double>()
            var peak = 0f
            var framesDone = 0L
            val maxFrames = { sampleRate.toLong() * (WINDOW_US / 1_000_000L) }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + 20_000_000_000L   // höchstens 20 s Rechenzeit
            while (!outputDone && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (n < 0 || (t >= 0 && t > startUs + WINDOW_US)) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, t, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        shelf = Array(2) { kWeighting(sampleRate.toDouble()).first }
                        hp = Array(2) { kWeighting(sampleRate.toDouble()).second }
                        hopFrames = sampleRate / 10
                        hopCount = 0
                        hopSum = 0.0
                    }
                    oi >= 0 -> {
                        val out = codec.getOutputBuffer(oi)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val b = out.order(ByteOrder.nativeOrder())
                            val ch = channels.coerceAtLeast(1)
                            val bps = if (floatPcm) 4 else 2
                            val frames = info.size / (bps * ch)
                            for (f in 0 until frames) {
                                var frameEnergy = 0.0
                                for (c in 0 until ch) {
                                    val s = if (floatPcm) b.getFloat() else b.getShort() / 32768f
                                    if (c < 2) {
                                        peak = max(peak, abs(s))
                                        val k = hp[c].process(shelf[c].process(s.toDouble()))
                                        frameEnergy += k * k
                                    }
                                }
                                // Mono zählt wie zwei gleiche Kanäle
                                if (ch == 1) frameEnergy *= 2.0
                                hopSum += frameEnergy
                                if (++hopCount >= hopFrames) {
                                    hops.add(hopSum / hopCount)
                                    hopSum = 0.0
                                    hopCount = 0
                                }
                            }
                            framesDone += frames
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(oi, false)
                        if (framesDone >= maxFrames()) outputDone = true
                    }
                }
            }

            // Blöcke zu 400 ms (4 Abschnitte, 75 % Überlappung), dann Gating nach BS.1770
            if (hops.size < 4) return null
            val blocks = ArrayList<Double>()
            for (i in 0..hops.size - 4) blocks.add((hops[i] + hops[i + 1] + hops[i + 2] + hops[i + 3]) / 4.0)
            fun lufs(z: Double) = -0.691 + 10.0 * log10(z)
            val aboveAbs = blocks.filter { it > 0.0 && lufs(it) > -70.0 }
            if (aboveAbs.isEmpty()) return null
            val relThreshold = lufs(aboveAbs.average()) - 10.0
            val gated = aboveAbs.filter { lufs(it) > relThreshold }
            if (gated.isEmpty()) return null
            val loudness = lufs(gated.average()).toFloat()
            if (loudness.isNaN() || loudness.isInfinite()) return null
            return LoudnessCache.Entry(loudness, if (peak > 0f) peak else -1f)
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            try { ex.release() } catch (e: Exception) {}
        }
    }
}

/**
 * Verbindet Player, Messung und Prozessor: Setzt die Verstärkung für den laufenden Titel (aus dem Speicher oder
 * nach einer Messung) und misst die nächsten Titel der Warteschlange schon vorab.
 */
class AutoGainController(
    private val ctx: Context,
    private val processor: GainAudioProcessor
) {
    private val main = Handler(Looper.getMainLooper())
    // Eine Messung nach der anderen, mit niedriger Priorität, damit Wiedergabe und Oberfläche nicht leiden
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }
    }
    private var player: ExoPlayer? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "auto_gain") main.post { refresh() }
    }
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Sprung oder neue Warteschlange: Der nächste Audio-Anfang gehört zum laufenden Titel. Sonst ein fortlaufender Wechsel.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            ) processor.markSkip() else processor.itemChanged()
            refresh()
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { refresh() }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
        ) { refresh() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { refresh() }
        override fun onRepeatModeChanged(repeatMode: Int) { refresh() }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { refresh() }
    }

    fun attach(p: ExoPlayer) {
        player = p
        p.addListener(playerListener)
        appPrefs(ctx).registerOnSharedPreferenceChangeListener(prefsListener)
        // Neue Messwerte (auch aus dem Hintergrundlauf) sofort übernehmen
        LoudnessCache.onChange = { main.post { refresh() } }
        refresh()
    }

    fun release() {
        player?.removeListener(playerListener)
        appPrefs(ctx).unregisterOnSharedPreferenceChangeListener(prefsListener)
        LoudnessCache.onChange = null
        player = null
        worker.shutdownNow()
    }

    /** Verstärkung (dB) für einen Titel aus dem Speicher; null = noch nicht gemessen. */
    private fun gainDbOf(item: MediaItem?): Float? {
        val id = item?.mediaId?.toLongOrNull() ?: return null
        val e = LoudnessCache.get(ctx, id) ?: return null
        return if (e.failed) 0f else autoGainDb(e.lufs, e.peak)   // gescheiterte Messung: unverändert abspielen
    }

    private fun refresh() {
        val p = player ?: return
        if (!readAutoGain(ctx)) {
            processor.setGainDb(0f)
            return
        }
        val current = p.currentMediaItem
        if (current?.mediaId?.toLongOrNull() == null) {
            processor.setGainDb(0f)
            return
        }
        val curDb = gainDbOf(current) ?: run {
            request(current)
            0f   // bis zur Messung unverändert, dann weich angepasst
        }
        // Der Titel danach: Seine Verstärkung steht schon bereit, wenn er beginnt. Die nächsten zwei werden vorab gemessen.
        var nextDb: Float? = null
        val tl = p.currentTimeline
        if (!tl.isEmpty) {
            var idx = p.currentMediaItemIndex
            for (step in 0 until 2) {
                idx = tl.getNextWindowIndex(idx, p.repeatMode, p.shuffleModeEnabled)
                if (idx == C.INDEX_UNSET || idx !in 0 until p.mediaItemCount) break
                val item = p.getMediaItemAt(idx)
                if (step == 0) nextDb = gainDbOf(item) ?: 0f
                request(item)
            }
        }
        processor.setGains(curDb, nextDb ?: curDb)
        val dur = p.duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it }
        processor.setPlayback(p.currentPosition.coerceAtLeast(0L), dur, p.isPlaying)
    }

    private fun request(item: MediaItem?) {
        val id = item?.mediaId?.toLongOrNull() ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (LoudnessCache.get(ctx, id) != null) return
        if (!LoudnessScanner.inFlight.add(id)) return   // wird schon gemessen
        LoudnessScanner.urgent.incrementAndGet()
        try {
            worker.execute {
                try {
                    val entry = try { LoudnessAnalyzer.analyze(ctx, uri, 0L) } catch (e: Exception) { null }
                    LoudnessCache.put(ctx, id, entry ?: LoudnessCache.FAILED)   // löst über onChange ein refresh() aus
                } finally {
                    LoudnessScanner.inFlight.remove(id)
                    LoudnessScanner.urgent.decrementAndGet()
                }
            }
        } catch (e: Exception) {
            LoudnessScanner.inFlight.remove(id)
            LoudnessScanner.urgent.decrementAndGet()
        }
    }
}
