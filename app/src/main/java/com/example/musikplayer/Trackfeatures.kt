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
    val brightness: Float,
    /** Dynamik: 0 (durchgehend gleich laut, stark komprimiert) bis 1 (große Lautstärkeunterschiede, z. B. Klassik). Negativ = nicht gemessen. */
    val dynamics: Float = -1f,
    /** Wie klar ein Puls zu hören ist, 0 (frei fließend, Ambient) bis 1 (deutlicher Beat). Negativ = nicht gemessen. */
    val pulse: Float = -1f,
    /** Anteil des Basses (unter etwa 200 Hz), 0 (kaum Bass) bis 1 (bassdominiert). Negativ = nicht gemessen. */
    val bass: Float = -1f
) {
    val failed: Boolean get() = energy < 0f
    val hasTempo: Boolean get() = bpm > 0f

    /** Hat die neuen Merkmale (Dynamik, Puls, Bass). Ältere Einträge haben nur Tempo, Energie und Helligkeit. */
    val extended: Boolean get() = failed || dynamics >= 0f

    companion object {
        val FAILED = TrackFeatures(0f, -1f, 0f)
    }
}

/**
 * Gespeicherte Merkmale pro Titel (Datei "features.txt", eine Zeile pro Titel: id,bpm,energie,helligkeit,dynamik,puls,bass,größe,änderungszeit).
 * Größe und Änderungszeit sind der Datei-Stempel: Hat sich die Datei verändert, gilt der Eintrag nicht mehr und wird neu gemessen.
 * Ältere Zeilen mit nur vier oder sieben Werten bleiben gültig (die mit vier werden im Hintergrund nach und nach neu gemessen,
 * die neuere Zeile gewinnt). Beim Aufräumen ([tidy]) wird die Datei kompakt neu geschrieben.
 */
object TrackFeatureCache {
    private val map = ConcurrentHashMap<Long, TrackFeatures>()
    private val stamps = ConcurrentHashMap<Long, AudioStamp>()   // fehlt bei älteren Einträgen
    private var loaded = false
    private var file: File? = null
    private var lineCount = 0   // Zeilen in der Datei: mehr als Einträge heißt, es liegen veraltete Doppelte darin

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.applicationContext.filesDir, "features.txt").also { file = it }
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val p = line.split(',')
                if (p.size == 4 || p.size == 7 || p.size == 9) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    val bpm = p[1].toFloatOrNull() ?: return@forEachLine
                    val energy = p[2].toFloatOrNull() ?: return@forEachLine
                    val bright = p[3].toFloatOrNull() ?: return@forEachLine
                    map[id] = if (p.size >= 7) {
                        TrackFeatures(
                            bpm, energy, bright,
                            p[4].toFloatOrNull() ?: return@forEachLine,
                            p[5].toFloatOrNull() ?: return@forEachLine,
                            p[6].toFloatOrNull() ?: return@forEachLine
                        )
                    } else TrackFeatures(bpm, energy, bright)
                    val st = if (p.size == 9) AudioStamps.parse(p[7], p[8]) else null
                    if (st != null) stamps[id] = st else stamps.remove(id)
                    lineCount++
                }
            }
        } catch (e: Exception) {
            // Beschädigte Datei: vorhandene Werte behalten, der Rest wird neu gemessen
        }
    }

    /** Merkmale des Titels. Hat sich die Datei seit der Messung verändert, gibt es keine (der Titel wird neu gemessen). */
    fun get(ctx: Context, id: Long): TrackFeatures? {
        ensureLoaded(ctx)
        val f = map[id] ?: return null
        return if (AudioStamps.fits(stamps[id], id)) f else null
    }

    private fun lineOf(id: Long, f: TrackFeatures, st: AudioStamp?): String {
        val base = "$id,${f.bpm},${f.energy},${f.brightness},${f.dynamics},${f.pulse},${f.bass}"
        return if (st != null) "$base,${st.size},${st.modified}" else base
    }

    @Synchronized
    fun put(ctx: Context, id: Long, features: TrackFeatures) {
        ensureLoaded(ctx)
        map[id] = features
        val st = AudioStamps.of(id)
        if (st != null) stamps[id] = st else stamps.remove(id)
        try {
            file?.appendText(lineOf(id, features, st) + "\n")
            lineCount++
        } catch (e: Exception) {
            // Speichern ist optional: ohne Datei wird beim nächsten Start neu gemessen
        }
    }

    /** Übernimmt Merkmale auf neue IDs (alt -> neu), wenn ein Titel nach einem Refresh wiedererkannt wurde. */
    @Synchronized
    fun carryOver(ctx: Context, remap: Map<Long, Long>) {
        ensureLoaded(ctx)
        val sb = StringBuilder()
        var n = 0
        for ((old, new) in remap) {
            if (old == new) continue
            val f = map[old] ?: continue
            if (map.containsKey(new)) continue
            map[new] = f
            // Der alte Stempel wandert mit: Hat sich die Datei beim Umzug verändert, passt er nicht mehr und es wird neu gemessen
            val st = stamps[old]
            if (st != null) stamps[new] = st else stamps.remove(new)
            sb.append(lineOf(new, f, st)).append('\n')
            n++
        }
        if (n == 0) return
        try {
            file?.appendText(sb.toString())
            lineCount += n
        } catch (e: Exception) {
            // Speichern ist optional
        }
    }

    /**
     * Räumt auf und schreibt die Datei kompakt neu: eine Zeile je Titel, Einträge zu Titeln, die es nicht mehr gibt
     * ([keep]) oder deren Datei sich verändert hat, fallen weg, ältere Einträge ohne Stempel bekommen den aktuellen.
     * Gehört nach AudioStamps.update auf einen Hintergrund-Thread. Eine leere [keep]-Menge (Bibliothek nicht verfügbar) ändert nichts.
     */
    @Synchronized
    fun tidy(ctx: Context, keep: Set<Long>) {
        ensureLoaded(ctx)
        if (keep.isEmpty()) return
        var dirty = lineCount > map.size
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            val stale = !AudioStamps.fits(stamps[id], id)
            if (id !in keep || stale) { it.remove(); stamps.remove(id); dirty = true }
        }
        for (id in map.keys) {
            if (stamps[id] == null) AudioStamps.of(id)?.let { stamps[id] = it; dirty = true }
        }
        if (!dirty) return
        val f = file ?: return
        try {
            rewriteAtomically(f, map.entries.asSequence().map { (id, v) -> lineOf(id, v, stamps[id]) })
            lineCount = map.size
        } catch (e: Exception) {
            // Aufräumen ist optional: die alte Datei bleibt gültig
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
    // Bass: einfacher Tiefpass bei etwa 200 Hz, gemessen wird, wie viel der Energie darunter liegt
    private val lowAlpha = (1.0 - exp(-2.0 * Math.PI * 200.0 / sampleRate.coerceAtLeast(8000))).toFloat()
    private var low = 0f
    private var totalLowSq = 0.0
    private var sumLowSq = 0.0
    private var pulse = 0f
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
        low += lowAlpha * (x - low)
        sumSq += (x * x).toDouble()
        sumDiffSq += (d * d).toDouble()
        sumLowSq += (low * low).toDouble()
        if (++inHop >= hop) {
            if (count == env.size) env = env.copyOf(env.size * 2)
            env[count++] = (sumSq / inHop).toFloat()
            totalSq += sumSq
            totalDiffSq += sumDiffSq
            totalLowSq += sumLowSq
            sumSq = 0.0
            sumDiffSq = 0.0
            sumLowSq = 0.0
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
        val bpm = tempo(onset, n)

        // Dynamik: Abstand zwischen lauten und leisen Momenten (Pegel je 10 ms, 95. gegen 10. Perzentil in dB).
        // Stark komprimierte Titel liegen eng beieinander, Klassik und Akustisches weit auseinander.
        val db = FloatArray(n) { (10.0 * log10(env[it] + 1e-12)).toFloat() }
        db.sort()
        val spread = (db[(n * 0.95).toInt().coerceAtMost(n - 1)] - db[(n * 0.10).toInt()]).toDouble()
        val dynamics = ((spread - 8.0) / 32.0).coerceIn(0.0, 1.0)

        // Bass: Anteil der Energie unter etwa 200 Hz, logarithmisch (1 % = 0, 100 % = 1)
        val bass = ((log10(totalLowSq / totalSq + 1e-4) + 2.0) / 2.0).coerceIn(0.0, 1.0)

        return TrackFeatures(bpm, energy.toFloat(), bright.toFloat(), dynamics.toFloat(), pulse, bass.toFloat())
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
        // Pulsstärke: wie deutlich der beste Gipfel gegenüber dem Rauschen des Einsatz-Verlaufs ist (0 bis 1)
        pulse = if (bestLag == 0) 0f else (r[bestLag] / r0 / 0.5).coerceIn(0.0, 1.0).toFloat()
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
