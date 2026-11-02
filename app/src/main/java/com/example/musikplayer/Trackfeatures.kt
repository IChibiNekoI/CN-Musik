package com.example.musikplayer

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/**
 * Grobe Klangmerkmale eines Titels für den Neko Mix. Sie entstehen im selben Durchlauf wie die Lautheits-Messung
 * (der Titel wird dafür nicht ein zweites Mal decodiert) und nur im Hintergrund am Ladegerät.
 */
class TrackFeatures(
    /** Tempo in Schlägen pro Minute, auf eine Oktave (80 bis 160) gefaltet, damit halbes und doppeltes Tempo gleich zählen. 0 = unklar. */
    val bpm: Float,
    /** Wie kraftvoll der Titel klingt, 0 (ruhig) bis 1 (intensiv). Negativ = Messung gescheitert. */
    val energy: Float,
    /** Wie hell der Titel klingt, 0 (dunkel, bassig) bis 1 (hell, höhenreich). */
    val brightness: Float
) {
    val failed: Boolean get() = energy < 0f
    val hasTempo: Boolean get() = bpm > 0f

    companion object {
        val FAILED = TrackFeatures(0f, -1f, 0f)
    }
}

/** Gespeicherte Merkmale pro Titel (Datei "features.txt", eine Zeile pro Titel: id,bpm,energie,helligkeit). */
object TrackFeatureCache {
    private val map = ConcurrentHashMap<Long, TrackFeatures>()
    private var loaded = false
    private var file: File? = null

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.applicationContext.filesDir, "features.txt").also { file = it }
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val p = line.split(',')
                if (p.size == 4) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    val bpm = p[1].toFloatOrNull() ?: return@forEachLine
                    val energy = p[2].toFloatOrNull() ?: return@forEachLine
                    val bright = p[3].toFloatOrNull() ?: return@forEachLine
                    map[id] = TrackFeatures(bpm, energy, bright)
                }
            }
        } catch (e: Exception) {
            // Beschädigte Datei: vorhandene Werte behalten, der Rest wird neu gemessen
        }
    }

    fun get(ctx: Context, id: Long): TrackFeatures? {
        ensureLoaded(ctx)
        return map[id]
    }

    @Synchronized
    fun put(ctx: Context, id: Long, features: TrackFeatures) {
        ensureLoaded(ctx)
        map[id] = features
        try {
            file?.appendText("$id,${features.bpm},${features.energy},${features.brightness}\n")
        } catch (e: Exception) {
            // Speichern ist optional: ohne Datei wird beim nächsten Start neu gemessen
        }
    }
}

private val LN2 = ln(2.0)

/**
 * Berechnet die Merkmale aus dem Ton, der ohnehin gerade decodiert wird. Alles läuft in einem Durchgang über die
 * Abtastwerte: Es wird nur mitgezählt (Energie je 10 ms), danach rechnet [finish] einmalig auf wenigen tausend Werten.
 */
class TrackFeatureExtractor(sampleRate: Int) {
    private val hop = (sampleRate / 100).coerceAtLeast(1)   // 10 ms je Abschnitt
    private var inHop = 0
    private var sumSq = 0.0
    private var sumDiffSq = 0.0
    private var prev = 0f
    private var totalSq = 0.0
    private var totalDiffSq = 0.0
    private var env = FloatArray(4096)
    private var count = 0

    /** Ein Mono-Abtastwert (-1 bis 1). */
    fun add(x: Float) {
        val d = x - prev
        prev = x
        sumSq += (x * x).toDouble()
        sumDiffSq += (d * d).toDouble()
        if (++inHop >= hop) {
            if (count == env.size) env = env.copyOf(env.size * 2)
            env[count++] = (sumSq / inHop).toFloat()
            totalSq += sumSq
            totalDiffSq += sumDiffSq
            sumSq = 0.0
            sumDiffSq = 0.0
            inHop = 0
        }
    }

    /** Merkmale aus allem, was bisher kam. null, wenn zu wenig Ton da war (unter 6 Sekunden) oder nur Stille. */
    fun finish(): TrackFeatures? {
        val n = count
        if (n < 600 || totalSq <= 0.0) return null
        val samples = n.toDouble() * hop

        // Lautstärke (Effektivwert) und Helligkeit (Anteil der schnellen Änderungen im Signal)
        val rmsDb = 10.0 * log10(totalSq / samples + 1e-12)
        val loud = ((rmsDb + 36.0) / 24.0).coerceIn(0.0, 1.0)
        val bright = ((log10(totalDiffSq / totalSq + 1e-4) + 2.5) / 2.5).coerceIn(0.0, 1.0)

        // Einsatz-Verlauf: Wo wird es plötzlich lauter? (relativ zur mittleren Lautstärke, damit leise Titel nicht benachteiligt sind)
        var mean = 0.0
        for (i in 0 until n) mean += env[i]
        mean = mean / n + 1e-12
        val onset = FloatArray(n)
        var prevC = ln(1.0 + 100.0 * env[0] / mean)
        var fluxSum = 0.0
        for (i in 1 until n) {
            val c = ln(1.0 + 100.0 * env[i] / mean)
            val o = max(0.0, c - prevC)
            onset[i] = o.toFloat()
            fluxSum += o
            prevC = c
        }
        val flux = fluxSum / (n - 1)
        val energy = 0.65 * loud + 0.35 * (flux / 0.2).coerceIn(0.0, 1.0)
        return TrackFeatures(tempo(onset, n), energy.toFloat(), bright.toFloat())
    }

    /** Tempo über die Selbstähnlichkeit des Einsatz-Verlaufs (Autokorrelation), Werte zwischen 60 und 200 BPM. */
    private fun tempo(onset: FloatArray, n: Int): Float {
        val minLag = 30    // 200 BPM bei 100 Abschnitten pro Sekunde
        val maxLag = 100   // 60 BPM
        if (n < maxLag * 4) return 0f
        var m = 0.0
        for (i in 0 until n) m += onset[i]
        m /= n
        val x = DoubleArray(n) { onset[it] - m }
        var r0 = 0.0
        for (v in x) r0 += v * v
        if (r0 <= 1e-9) return 0f
        r0 /= n

        val r = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            r[lag] = s / (n - lag)
        }
        var best = -1.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            if (r[lag] <= r[lag - 1] || r[lag] < r[lag + 1]) continue   // nur Gipfel
            val bpm = 6000.0 / lag
            val octaves = ln(bpm / 120.0) / LN2
            val score = r[lag] * exp(-0.5 * octaves * octaves)   // bevorzugt gängige Tempi um 120 BPM
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || r[bestLag] / r0 < 0.05) return 0f   // kein erkennbarer Puls

        // Gipfel zwischen den Werten genauer bestimmen (Parabel durch drei Punkte)
        val a = r[bestLag - 1]
        val b = r[bestLag]
        val c = r[bestLag + 1]
        val den = a - 2.0 * b + c
        val delta = if (abs(den) > 1e-12) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        var bpm = 6000.0 / (bestLag + delta)
        while (bpm < 80.0) bpm *= 2.0
        while (bpm >= 160.0) bpm /= 2.0
        return bpm.toFloat()
    }
}
