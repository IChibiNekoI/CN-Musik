package com.example.musikplayer



/** Fester Zufallswert zwischen 0 und 1 für einen Titel: Gleicher Startwert und gleicher Titel ergeben immer denselben Wert. */
private fun unitFor(seed: Long, id: Long): Double {
    var z = seed + id * -7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    z = z xor (z ushr 31)
    return ((z ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(1e-9, 1.0)
}

private const val DAY_MS = 86_400_000.0
private const val HOUR_MS = 3_600_000L
private const val SESSION_GAP_MS = 20L * 60 * 1000
private const val MIX_DISCOVER_SHARE = 0.2   // Anteil der Plätze für Titel, die du in letzter Zeit nicht gehört hast
private const val REDISCOVER_MIN_PLAYS = 3    // so oft früher gehört: zählt als "früher oft gehört"
private const val NEW_MAX_PLAYS = 2           // so selten gehört: zählt als "neu für dich"

// Feineinstellung der Personalisierung (0 schaltet die jeweilige Wirkung ab)
private const val MIX_MAX_ARTIST_SHARE = 0.13    // so viel vom Mix darf höchstens von einem Künstler kommen (bei 30 Titeln: 4)
private const val MIX_DAYS_WEIGHT = 0.15         // Bonus je weiterem Hörtag eines Titels (Beständigkeit statt Serienhören an einem Tag)
private const val MIX_ERA_WEIGHT = 0.5           // Einfluss deines Zeitgeschmacks (Erscheinungsjahr der Titel)
private const val MIX_SOUND_SKIP_WEIGHT = 0.12   // Einfluss von "klingt wie das, was du im Mix überspringst"
private const val CLUSTER_MAX = 3                // so viele Geschmacksgruppen höchstens
private const val CLUSTER_SPLIT_RATIO = 0.6      // eine Gruppe mehr nur, wenn die Titel dadurch mindestens 40 % näher an ihrer Mitte liegen
private const val CLUSTER_MIN_SHARE = 0.15       // jede Gruppe muss mindestens so viel von deinem Hören ausmachen
private const val CLUSTER_MAX_POINTS = 150       // aus Rechenzeit-Gründen werden nur die wichtigsten Ankertitel gruppiert

/** Wie ein Titel in den Mix kam, soweit er aus der Entdecken-Auswahl stammt. */
enum class MixKind {
    /** Früher oft gehört, seit langer Zeit nicht mehr. */
    REDISCOVER,
    /** Noch nie oder kaum gehört, passt aber zu deinem Klangprofil, Künstlern oder neu in der Bibliothek. */
    NEW_TO_YOU,
    /** Liegt etwas außerhalb deines üblichen Klangs, ist aber über Künstler, Album oder Hörsitzungen mit deinem Hören verbunden. */
    STRETCH
}

/** Der Mix und, für Titel aus der Entdecken-Auswahl, die Art der Entdeckung (alle anderen fehlen in [kinds]). */
class MixResult(val songs: List<Song>, val kinds: Map<Long, MixKind>)

/** Gewicht eines Ereignisses, das [ageMs] zurückliegt, wenn es alle [days] Tage halb so viel zählen soll. */
private fun decay(ageMs: Long, days: Double): Double = Math.pow(0.5, ageMs / (days * DAY_MS))

/** Tempo (80 bis 160 BPM, eine Oktave) als Stelle auf einem Kreis von 0 bis 1. */
private fun tempoPos(bpm: Float): Double = Math.log(bpm / 80.0) / Math.log(2.0)

/** Abstand zweier Tempi auf dem Oktavenkreis: 0 = gleich, 0,5 = größter Abstand. */
private fun tempoDist(a: Double, b: Double): Double {
    val d = Math.abs(a - b)
    return Math.min(d, 1.0 - d)
}

/** Dein typischer Klang, gemittelt über Favoriten und oft gehörte Titel. */
private class MixProfile(
    val tempo: Double,
    val hasTempo: Boolean,
    val energy: Double,
    val brightness: Double,
    val confidence: Double,
    /** Dynamik, Puls und Bass deines typischen Klangs; null, solange zu wenige Ankertitel die neuen Merkmale haben. */
    val ext: ExtProfile? = null,
    /** Anteil dieser Geschmacksgruppe an deinem Hören (0 bis 1); bei nur einer Gruppe 1. */
    val share: Double = 1.0
)

private class ExtProfile(val dynamics: Double, val pulse: Double, val bass: Double)

/** Gauß-Passung eines Merkmals: 1 = gleich, fällt mit dem Abstand (in Einheiten von [width]) ab. */
private fun gauss(diff: Double, width: Double): Double {
    val z = diff / width
    return Math.exp(-0.5 * z * z)
}

/** Wie gut passt ein Titel zu einer einzelnen Geschmacksgruppe? 0 bis 1; ohne Messwert neutral. */
private fun clusterMatch(f: TrackFeatures?, p: MixProfile?): Double {
    if (p == null) return 0.0
    if (f == null || f.failed) return 0.35
    val de = (f.energy - p.energy) / 0.25
    val db = (f.brightness - p.brightness) / 0.3
    val gE = Math.exp(-0.5 * de * de)
    val gB = Math.exp(-0.5 * db * db)
    val gT = if (f.hasTempo && p.hasTempo) {
        val dt = tempoDist(tempoPos(f.bpm), p.tempo) / 0.12
        Math.exp(-0.5 * dt * dt)
    } else 0.5
    val x = p.ext
    // Mit Dynamik, Puls und Bass (Titel und Profil haben sie): feineres Bild, das z. B. Ambient von Techno
    // und Klassik von Rock trennt, auch wenn Tempo, Energie und Helligkeit ähnlich ausfallen
    if (x != null && f.extended) {
        val gD = gauss(f.dynamics - x.dynamics, 0.25)
        val gP = gauss(f.pulse - x.pulse, 0.25)
        val gS = gauss(f.bass - x.bass, 0.25)
        return 0.28 * gT + 0.22 * gE + 0.14 * gB + 0.14 * gD + 0.10 * gP + 0.12 * gS
    }
    return 0.4 * gT + 0.35 * gE + 0.25 * gB
}

/** Ein gemessener Ankertitel mit seinem Gewicht (Favorit und viel gehört zählt mehr). */
private class Anchor(val id: Long, val f: TrackFeatures, val w: Double)

/** Klang eines Titels, den du im Mix oft überspringst, mit der Stärke der Skips. */
private class SkipSound(val id: Long, val f: TrackFeatures, val w: Double)

/** Dein typischer Klang für eine Gruppe von Ankertiteln (gewichteter Mittelwert; Tempo als Kreis gemittelt). */
private fun profileOf(group: List<Anchor>, share: Double, confidence: Double): MixProfile? {
    var pW = 0.0; var pE = 0.0; var pB = 0.0
    var tW = 0.0; var tC = 0.0; var tS = 0.0
    var xW = 0.0; var xD = 0.0; var xP = 0.0; var xS = 0.0
    var ext = 0
    for (a in group) {
        val f = a.f
        val w = a.w
        pW += w; pE += w * f.energy; pB += w * f.brightness
        if (f.extended) {
            ext++
            xW += w; xD += w * f.dynamics; xP += w * f.pulse; xS += w * f.bass
        }
        if (f.hasTempo) {
            val ang = 2.0 * Math.PI * tempoPos(f.bpm)
            tC += w * Math.cos(ang); tS += w * Math.sin(ang); tW += w
        }
    }
    if (pW <= 0.0) return null
    val hasT = tW > 0.0 && tC * tC + tS * tS > 1e-9
    var pos = if (hasT) Math.atan2(tS, tC) / (2.0 * Math.PI) else 0.0
    if (pos < 0.0) pos += 1.0
    val x = if (ext >= 4 && xW > 0.0) ExtProfile(xD / xW, xP / xW, xS / xW) else null
    return MixProfile(pos, hasT, pE / pW, pB / pW, confidence, x, share)
}

/** Ergebnis einer Einteilung in [k] Gruppen: Gesamtabstand zu den Gruppenmitten und die Mitglieder je Gruppe (Indizes). */
private class GroupSplit(val cost: Double, val members: List<List<Int>>)

/** Einteilung in [k] Gruppen um echte Titel als Mitte (k-Medoids, Abstand = [soundDistance]); [d] ist die Abstandstabelle. */
private fun medoidSplit(d: Array<DoubleArray>, w: DoubleArray, k: Int): GroupSplit {
    val n = w.size
    val meds = IntArray(k)
    var heaviest = 0
    for (i in 1 until n) if (w[i] > w[heaviest]) heaviest = i
    meds[0] = heaviest
    // weitere Mitten: jeweils der Titel, der von allen bisherigen am weitesten weg ist
    for (c in 1 until k) {
        var bestI = 0
        var bestD = -1.0
        for (i in 0 until n) {
            var nearest = Double.MAX_VALUE
            for (m in 0 until c) nearest = Math.min(nearest, d[i][meds[m]])
            if (nearest > bestD) { bestD = nearest; bestI = i }
        }
        meds[c] = bestI
    }
    val assign = IntArray(n)
    for (round in 0 until 6) {
        for (i in 0 until n) {
            var bc = 0
            for (c in 1 until k) if (d[i][meds[c]] < d[i][meds[bc]]) bc = c
            assign[i] = bc
        }
        var changed = false
        for (c in 0 until k) {
            var bestM = meds[c]
            var bestSum = Double.MAX_VALUE
            for (m in 0 until n) {
                if (assign[m] != c) continue
                var sum = 0.0
                for (i in 0 until n) if (assign[i] == c) sum += w[i] * d[i][m]
                if (sum < bestSum) { bestSum = sum; bestM = m }
            }
            if (bestM != meds[c]) { meds[c] = bestM; changed = true }
        }
        if (!changed) break
    }
    for (i in 0 until n) {
        var bc = 0
        for (c in 1 until k) if (d[i][meds[c]] < d[i][meds[bc]]) bc = c
        assign[i] = bc
    }
    var cost = 0.0
    for (i in 0 until n) cost += w[i] * d[i][meds[assign[i]]]
    val groups = ArrayList<List<Int>>()
    for (c in 0 until k) {
        val mem = (0 until n).filter { assign[it] == c }
        if (mem.isNotEmpty()) groups += mem
    }
    return GroupSplit(cost, groups)
}

/**
 * Teilt die Ankertitel in bis zu drei Geschmacksgruppen. Wer z. B. ruhige Klassik und laute Rockmusik hört, hat
 * zwei getrennte Vorlieben: Ein einziger Mittelwert läge dazwischen und würde genau das bevorzugen, was du nicht hörst.
 * Eine weitere Gruppe gibt es nur, wenn sie die Titel deutlich näher an ihre Mitte bringt und genug Gewicht hat.
 */
private fun tasteGroups(points: List<Anchor>): List<List<Anchor>> {
    val n = points.size
    if (n < 12) return listOf(points)
    val d = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else soundDistance(points[i].f, points[j].f) } }
    val w = DoubleArray(n) { points[it].w }
    var total = 0.0
    for (x in w) total += x
    var best = medoidSplit(d, w, 1)
    for (k in 2..CLUSTER_MAX) {
        val next = medoidSplit(d, w, k)
        if (next.members.size < k) break
        var tooSmall = false
        for (m in next.members) {
            var mass = 0.0
            for (i in m) mass += w[i]
            if (mass / total < CLUSTER_MIN_SHARE) tooSmall = true
        }
        if (tooSmall) break
        if (best.cost <= 0.0 || next.cost / best.cost > CLUSTER_SPLIT_RATIO) break
        best = next
    }
    return best.members.map { m -> m.map { points[it] } }
}

/** Wie gut passt ein Titel zu deinem Klang? Der Titel zählt mit der Gruppe, zu der er am besten passt (größere Gruppen etwas mehr). */
private fun soundMatch(f: TrackFeatures?, profile: List<MixProfile>?): Double {
    if (profile == null || profile.isEmpty()) return 0.0
    if (f == null || f.failed) return 0.35
    var top = 0.0
    for (p in profile) if (p.share > top) top = p.share
    var best = 0.0
    for (p in profile) {
        val scaled = clusterMatch(f, p) * (0.8 + 0.2 * p.share / top)
        if (scaled > best) best = scaled
    }
    return best
}

/** Abstand zweier Titel im Klang: 0 = sehr ähnlich, etwa 1 = sehr verschieden; ohne Messwerte neutral. */
private fun soundDistance(a: TrackFeatures?, b: TrackFeatures?): Double {
    if (a == null || b == null || a.failed || b.failed) return 0.4
    val t = if (a.hasTempo && b.hasTempo) 2.0 * tempoDist(tempoPos(a.bpm), tempoPos(b.bpm)) else 0.4
    val dE = Math.abs((a.energy - b.energy).toDouble())
    val dB = Math.abs((a.brightness - b.brightness).toDouble())
    if (a.extended && b.extended) {
        return 0.35 * t + 0.2 * dE + 0.15 * dB +
            0.1 * Math.abs((a.dynamics - b.dynamics).toDouble()) +
            0.1 * Math.abs((a.pulse - b.pulse).toDouble()) +
            0.1 * Math.abs((a.bass - b.bass).toDouble())
    }
    return 0.5 * t + 0.3 * dE + 0.2 * dB
}

/**
 * Bringt die gewählten Titel in eine angenehme Reihenfolge: Der nächste Titel klingt möglichst ähnlich wie der letzte,
 * derselbe Künstler steht nicht direkt hintereinander, und der [orderSeed] sorgt für Abwechslung (Würfel).
 */
private fun flowOrder(list: List<Song>, orderSeed: Long, features: (Long) -> TrackFeatures?): List<Song> {
    val left = list.sortedBy { unitFor(orderSeed, it.id) }.toMutableList()
    if (left.size < 3) return left
    val result = ArrayList<Song>(left.size)
    var cur = left.removeAt(0)
    result += cur
    while (left.isNotEmpty()) {
        val fc = features(cur.id)
        var best = 0
        var bestScore = Double.MAX_VALUE
        for (i in left.indices) {
            val c = left[i]
            var d = soundDistance(fc, features(c.id))
            if (c.artist.isNotBlank() && c.artist == cur.artist) d += 0.6
            d += 0.35 * unitFor(orderSeed + cur.id, c.id)
            if (d < bestScore) {
                bestScore = d
                best = i
            }
        }
        cur = left.removeAt(best)
        result += cur
    }
    return result
}

/**
 * "Dein Neko Mix": eine Playlist aus der eigenen Bibliothek, die sich an deinem Hören orientiert.
 *
 * Gewertet werden (alles nur aus Daten, die die App ohnehin speichert, ohne Rechenlast im Hintergrund):
 *  - Favoriten und Wiedergaben der letzten 30 Tage, jüngere zählen mehr (Halbwertszeit 10 Tage)
 *  - Künstler, die du magst
 *  - Skips im Mix (nur dort): übersprungene Titel und ihre Künstler werden seltener gewählt
 *  - Ähnlichkeit: Titel, die in derselben Hörsitzung wie deine Favoriten liefen, und Alben deiner Favoriten
 *  - Klang: Tempo, Energie, Helligkeit sowie Dynamik, Puls und Bass im Vergleich zu deinem typischen Klang ([features], falls schon gemessen)
 *  - Wiederholungsschutz: Was du gerade erst gehört hast, steht seltener drin
 * Rund ein Fünftel der Plätze gehört Titeln, die du in letzter Zeit nicht gehört hast, in drei Arten der Entdeckung
 * (gleichmäßig verteilt, fehlt eine Art, übernehmen die anderen):
 *  - Wiederentdecken: früher oft gehört (Langzeit-Statistik), seit langem nicht mehr; je länger her, desto eher
 *  - Neu für dich: noch nie oder kaum gehört, aber passend zu Klang, Künstlern oder neu in der Bibliothek
 *  - Etwas anderes: Klang etwas außerhalb deines üblichen, aber über Künstler, Album oder Hörsitzungen verbunden
 * Das sind keine zusätzlichen Playlists, sondern drei Auswahlstrategien innerhalb desselben Mixes.
 * Die Mischung wird dynamisch: Je mehr Hörverlauf da ist, desto mehr zählen
 * Wiedergaben gegenüber Favoriten.
 *
 * Jeder Titel bekommt aus Startwert und Titel-ID einen festen Zufallswert, damit der Mix stabil bleibt: Er ändert sich
 * nicht mit dem Tag, sondern nur beim Neu-Mischen oder wenn sich Hörgewohnheiten oder Bibliothek spürbar ändern.
 * Die Reihenfolge der gewählten Titel bestimmt der [orderSeed].
 */
fun buildMix(
    songs: List<Song>,
    favorites: Set<Long>,
    playLog: List<PlayEvent>,
    seed: Long,
    orderSeed: Long,
    size: Int = 30,
    skips: List<MixSkip> = emptyList(),
    totals: Map<Long, PlayTotal> = emptyMap(),
    features: (Long) -> TrackFeatures? = { null }
): List<Song> = buildMixDetailed(songs, favorites, playLog, seed, orderSeed, size, skips, totals, features).songs

/** Wie [buildMix], liefert aber zusätzlich zu jedem Entdecken-Titel die Art der Entdeckung (für eine spätere Anzeige). */
fun buildMixDetailed(
    songs: List<Song>,
    favorites: Set<Long>,
    playLog: List<PlayEvent>,
    seed: Long,
    orderSeed: Long,
    size: Int = 30,
    skips: List<MixSkip> = emptyList(),
    totals: Map<Long, PlayTotal> = emptyMap(),
    features: (Long) -> TrackFeatures? = { null }
): MixResult {
    if (songs.isEmpty()) return MixResult(emptyList(), emptyMap())
    val nowMs = System.currentTimeMillis()
    val nowSec = nowMs / 1000L
    val byId = songs.associateBy { it.id }

    // Wiedergaben der letzten 30 Tage, jüngere zählen mehr
    val songPlays = HashMap<Long, Double>()
    val artistPlays = HashMap<String, Double>()
    val lastPlay = HashMap<Long, Long>()
    val songDays = HashMap<Long, HashSet<Long>>()   // an wie vielen verschiedenen Tagen lief der Titel?
    val tzOffset = java.util.TimeZone.getDefault().getOffset(nowMs).toLong()
    val events = ArrayList<PlayEvent>()
    for (e in playLog) {
        val age = nowMs - e.time
        if (age !in 0..MIX_WINDOW_MS) continue
        val s = byId[e.id] ?: continue   // ausgeblendete oder gelöschte Titel zählen nicht
        events += e
        val w = decay(age, 10.0)
        songPlays[s.id] = (songPlays[s.id] ?: 0.0) + w
        if (s.artist.isNotBlank()) artistPlays[s.artist] = (artistPlays[s.artist] ?: 0.0) + w
        if ((lastPlay[s.id] ?: 0L) < e.time) lastPlay[s.id] = e.time
        songDays.getOrPut(s.id) { HashSet() }.add((e.time + tzOffset) / 86_400_000L)
    }
    val favArtists = HashMap<String, Int>()
    for (id in favorites) {
        val a = byId[id]?.artist?.takeIf { it.isNotBlank() } ?: continue
        favArtists[a] = (favArtists[a] ?: 0) + 1
    }
    // Wie viel Hörverlauf da ist (0 bis 1): Mit wenig Verlauf zählen Favoriten mehr, mit viel die tatsächlichen Wiedergaben
    val confidence = Math.min(1.0, events.size / 150.0)
    // Entdeckerlust: Wer viele verschiedene Titel hört, bekommt etwas mehr Neues, wer oft dasselbe hört, etwas weniger
    val discoverShare = if (events.size >= 40) {
        val variety = songPlays.size.toDouble() / events.size
        Math.min(0.3, Math.max(0.12, 0.12 + 0.2 * variety))
    } else MIX_DISCOVER_SHARE

    // Skips im Mix, zwei Wochen Halbwertszeit; ein Skip in den ersten Sekunden zählt doppelt
    val songSkips = HashMap<Long, Double>()
    val artistSkips = HashMap<String, Double>()
    for (k in skips) {
        val age = nowMs - k.time
        if (age !in 0..MIX_WINDOW_MS) continue
        val w = k.weight * decay(age, 14.0)
        songSkips[k.id] = (songSkips[k.id] ?: 0.0) + w
        val a = byId[k.id]?.artist?.takeIf { it.isNotBlank() } ?: continue
        artistSkips[a] = (artistSkips[a] ?: 0.0) + w
    }

    // Ankertitel für Ähnlichkeit und Klang: Favoriten und die am meisten gehörten (ohne stark übersprungene)
    val seedIds = HashSet<Long>()
    for (id in favorites) if (id in byId) seedIds += id
    for (entry in songPlays.entries.sortedByDescending { it.value }.take(25)) {
        if ((songSkips[entry.key] ?: 0.0) < 1.5) seedIds += entry.key
    }

    // Hörsitzungen: Was lief kurz vor oder nach einem Ankertitel? (Abstand höchstens 3 Titel, höchstens 20 Minuten Pause)
    val coListen = HashMap<Long, Double>()
    if (seedIds.isNotEmpty()) {
        val ev = events.sortedBy { it.time }
        for (i in ev.indices) {
            for (j in i + 1..Math.min(i + 3, ev.size - 1)) {
                if (ev[j].time - ev[j - 1].time > SESSION_GAP_MS) break
                val a = ev[i].id
                val b = ev[j].id
                if (a == b) continue
                val w = 1.0 / (j - i)
                if (a in seedIds) coListen[b] = (coListen[b] ?: 0.0) + w
                if (b in seedIds) coListen[a] = (coListen[a] ?: 0.0) + w
            }
        }
    }
    val seedAlbums = HashMap<String, Int>()
    for (id in seedIds) {
        val s = byId[id] ?: continue
        if (s.album.isBlank()) continue
        val al = albumKeyOf(s)   // Albumidentität: gleichnamige Alben anderer Künstler zählen nicht mit
        seedAlbums[al] = (seedAlbums[al] ?: 0) + 1
    }

    // Zeitgeschmack: Welche Erscheinungsjahre hören die Ankertitel? (nur mit genug Jahresangaben, sonst ohne Wirkung)
    val eraPoints = ArrayList<Pair<Int, Double>>()
    for (id in seedIds) {
        val y = byId[id]?.year ?: continue
        if (y !in 1900..2100) continue
        eraPoints += y to ((if (id in favorites) 2.0 else 1.0) + Math.min(songPlays[id] ?: 0.0, 5.0) * 0.5)
    }
    var eraMean = 0.0
    var eraWidth = 0.0
    if (MIX_ERA_WEIGHT > 0.0 && eraPoints.size >= 8) {
        var wSum = 0.0; var ySum = 0.0
        for ((y, w) in eraPoints) { wSum += w; ySum += w * y }
        eraMean = ySum / wSum
        var varSum = 0.0
        for ((y, w) in eraPoints) varSum += w * (y - eraMean) * (y - eraMean)
        eraWidth = Math.max(8.0, 1.3 * Math.sqrt(varSum / wSum))
    }
    val eraStrength = Math.min(1.0, eraPoints.size / 20.0) * MIX_ERA_WEIGHT

    // Dein typischer Klang (erst ab 5 gemessenen Ankertiteln). Bei sehr verschiedenem Geschmack bis zu drei Gruppen.
    val anchors = ArrayList<Anchor>()
    for (id in seedIds) {
        val f = features(id) ?: continue
        if (f.failed) continue
        val w = (if (id in favorites) 2.0 else 1.0) + Math.min(songPlays[id] ?: 0.0, 5.0) * 0.5
        anchors += Anchor(id, f, w)
    }
    anchors.sortByDescending { it.w }
    val soundConfidence = Math.min(1.0, anchors.size / 15.0)
    val profile: List<MixProfile>? = if (anchors.size >= 5) {
        val groups = tasteGroups(anchors.take(CLUSTER_MAX_POINTS))
        val list: List<MixProfile> = if (groups.size <= 1) {
            listOfNotNull(profileOf(anchors, 1.0, soundConfidence))
        } else {
            var totalW = 0.0
            for (g in groups) for (a in g) totalW += a.w
            groups.mapNotNull { g -> profileOf(g, g.sumOf { it.w } / totalW, soundConfidence) }
        }
        if (list.isEmpty()) null else list
    } else null

    // Klang übersprungener Titel: Was ähnlich klingt wie das, was du im Mix oft überspringst, wird seltener gewählt
    val skipSounds = ArrayList<SkipSound>()
    if (MIX_SOUND_SKIP_WEIGHT > 0.0) {
        for (entry in songSkips.entries.sortedByDescending { it.value }.take(40)) {
            if (entry.value < 1.0) continue
            val f = features(entry.key) ?: continue
            if (f.failed) continue
            skipSounds += SkipSound(entry.key, f, entry.value)
        }
    }
    fun skipSoundDiv(id: Long, f: TrackFeatures?): Double {
        if (skipSounds.isEmpty() || f == null || f.failed) return 1.0
        var near = 0.0
        for (k in skipSounds) {
            if (k.id == id) continue   // der Titel selbst ist schon über seine eigenen Skips berücksichtigt
            val dist = soundDistance(f, k.f)
            if (dist < 0.18) near += k.w * (1.0 - dist / 0.18)
        }
        return 1.0 + MIX_SOUND_SKIP_WEIGHT * Math.min(near, 4.0)
    }

    val favW = 3.0 - confidence            // Favoriten: 3,0 (wenig Verlauf) bis 2,0 (viel Verlauf)
    val playW = 0.3 + 0.3 * confidence     // Wiedergaben: 0,3 bis 0,6 je Wiedergabe
    val known = ArrayList<Pair<Song, Double>>()   // Favoriten und kürzlich gehörte
    // Entdecken: Titel, die in 30 Tagen nicht gehört wurden und keine Favoriten sind, je nach Art der Entdeckung bewertet
    val rediscover = ArrayList<Pair<Song, Double>>()
    val newToYou = ArrayList<Pair<Song, Double>>()
    val stretch = ArrayList<Pair<Song, Double>>()
    for (s in songs) {
        val isFav = s.id in favorites
        val plays = songPlays[s.id] ?: 0.0
        val artistScore = Math.min((artistPlays[s.artist] ?: 0.0) * 0.4 + (favArtists[s.artist] ?: 0) * 0.5, 5.0)
        val similar = Math.min(coListen[s.id] ?: 0.0, 4.0) * 0.5 + Math.min(if (seedAlbums.isEmpty() || s.album.isBlank()) 0 else (seedAlbums[albumKeyOf(s)] ?: 0), 3) * 0.25
        val f = features(s.id)
        val match = soundMatch(f, profile)
        val sound = match * 2.0 * soundConfidence
        val soundSkipDiv = skipSoundDiv(s.id, f)
        // Zeitgeschmack: passt das Erscheinungsjahr zu dem, was du hörst? (ohne Jahresangabe neutral)
        val era = if (eraWidth > 0.0) {
            (if (s.year in 1900..2100) gauss(s.year - eraMean, eraWidth) else 0.5) * eraStrength
        } else 0.0
        var w: Double
        if (isFav || plays > 0.0) {
            // Beständigkeit: Ein Titel, der an mehreren Tagen lief, zählt mehr als einer, der an einem Tag in Serie lief
            val dayBonus = Math.max(0, Math.min(songDays[s.id]?.size ?: 0, 5) - 1) * MIX_DAYS_WEIGHT
            w = 1.0 + (if (isFav) favW else 0.0) + Math.min(plays, 6.0) * playW + dayBonus + artistScore * 0.8 + similar + sound + era
            w /= 1.0 + 0.9 * (songSkips[s.id] ?: 0.0)
            w /= soundSkipDiv
            w /= 1.0 + 0.12 * Math.min(artistSkips[s.artist] ?: 0.0, 5.0)
            // Wiederholungsschutz: Was du gerade erst gehört hast, kommt seltener
            val last = lastPlay[s.id]
            if (last != null) {
                val since = nowMs - last
                if (since < 12 * HOUR_MS) w *= 0.35 else if (since < 48 * HOUR_MS) w *= 0.7
            }
            // gewichtetes Ziehen ohne Zurücklegen (Efraimidis-Spirakis) mit festem Zufallswert je Titel
            known += s to Math.pow(unitFor(seed, s.id), 1.0 / w)
        } else {
            val tot = totals[s.id]
            val count = tot?.count ?: 0
            val skipDiv = (1.0 + 0.9 * (songSkips[s.id] ?: 0.0)) * soundSkipDiv
            val connection = artistScore * 0.4 + similar   // Verbindung zu deinem Hören über Künstler, Album, Sitzungen
            val ageDays = if (s.dateAdded > 0L) Math.max(0.0, (nowSec - s.dateAdded) / 86400.0) else 365.0

            if (count >= REDISCOVER_MIN_PLAYS && tot != null) {
                // Wiederentdecken: früher oft gehört; je länger her, desto reizvoller (ab einem Jahr nicht mehr steigend)
                val gapDays = Math.max(0.0, (nowMs - tot.lastMs) / DAY_MS)
                val past = 0.5 + Math.log(count.toDouble())
                val gap = 0.6 + 0.8 * Math.min(gapDays, 365.0) / 365.0
                w = (past * gap + connection * 0.5 + sound * 0.5 + era * 0.5) / skipDiv
                w /= 1.0 + 0.12 * Math.min(artistSkips[s.artist] ?: 0.0, 5.0)
                rediscover += s to Math.pow(unitFor(seed + 101L, s.id), 1.0 / w)
            } else if (count <= NEW_MAX_PLAYS) {
                // Neu für dich: noch nie oder kaum gehört; Klang, Künstler, Sitzungen und frisch Hinzugekommenes zählen
                w = (0.5 + connection + sound + era + 1.5 * Math.exp(-ageDays / 45.0)) / skipDiv
                newToYou += s to Math.pow(unitFor(seed + 202L, s.id), 1.0 / w)
            }

            // Etwas anderes: Klang etwas außerhalb deines üblichen (mittlere Passung, nicht zu nah, nicht zu fern), aber mit
            // Verbindung zu deinem Hören. Braucht ein Klangprofil und einen gemessenen Titel.
            if (profile != null && f != null && !f.failed && connection >= 0.2 && (songSkips[s.id] ?: 0.0) < 1.5) {
                val band = gauss(match - 0.45, 0.18)
                w = (0.2 + connection) * band / skipDiv
                if (w > 0.02) stretch += s to Math.pow(unitFor(seed + 303L, s.id), 1.0 / w)
            }
        }
    }

    // Plätze für das Entdecken, gleichmäßig auf die drei Arten verteilt; fehlt einer Art die Auswahl, übernehmen die anderen
    val pools = listOf(
        MixKind.REDISCOVER to rediscover.sortedByDescending { it.second },
        MixKind.NEW_TO_YOU to newToYou.sortedByDescending { it.second },
        MixKind.STRETCH to stretch.sortedByDescending { it.second }
    )
    val distinct = HashSet<Long>()
    for ((_, list) in pools) for (c in list) distinct += c.first.id
    val discoverSlots = Math.min(Math.round(size * discoverShare).toInt(), distinct.size)

    val pickedIds = HashSet<Long>()
    val kinds = HashMap<Long, MixKind>()
    val discovered = ArrayList<Song>()
    fun take(kind: MixKind, list: List<Pair<Song, Double>>, n: Int): Int {
        var got = 0
        for (c in list) {
            if (got >= n) break
            if (!pickedIds.add(c.first.id)) continue
            kinds[c.first.id] = kind
            discovered += c.first
            got++
        }
        return got
    }
    // Erst der gleichmäßige Anteil je Art ...
    for ((i, p) in pools.withIndex()) {
        val share = discoverSlots / 3 + if (i < discoverSlots % 3) 1 else 0
        take(p.first, p.second, share)
    }
    // ... dann Restplätze reihum: zuerst "neu für dich" (die größte Auswahl), dann Wiederentdecken, dann Etwas anderes
    val restOrder = listOf(1, 0, 2)
    var progress = true
    while (discovered.size < discoverSlots && progress) {
        progress = false
        for (i in restOrder) {
            if (discovered.size >= discoverSlots) break
            if (take(pools[i].first, pools[i].second, 1) > 0) progress = true
        }
    }

    // Vielfalt: Von einem Künstler kommen höchstens ein paar Titel (nur wenn genug andere da sind, sonst wird aufgefüllt)
    val picked = ArrayList<Song>(size)
    val artistCount = HashMap<String, Int>()
    val artistCap = Math.max(2, Math.round(size * MIX_MAX_ARTIST_SHARE).toInt())
    fun addPicked(song: Song) {
        picked += song
        if (song.artist.isNotBlank()) artistCount[song.artist] = (artistCount[song.artist] ?: 0) + 1
    }
    for (song in discovered) addPicked(song)
    val knownNeeded = size - discovered.size
    var knownTaken = 0
    val deferred = ArrayList<Song>()
    for (c in known.sortedByDescending { it.second }) {
        if (knownTaken >= knownNeeded) break
        val song = c.first
        if (song.artist.isBlank() || (artistCount[song.artist] ?: 0) < artistCap) {
            addPicked(song)
            knownTaken++
        } else {
            deferred += song
        }
    }
    for (song in deferred) {
        if (knownTaken >= knownNeeded) break
        addPicked(song)
        knownTaken++
    }
    // Zu wenig bekannte Titel (kleine Bibliothek, wenig Verlauf): mit weiteren Entdecken-Kandidaten auffüllen
    if (picked.size < size) {
        val rest = pools.flatMap { (kind, list) -> list.map { Triple(kind, it.first, it.second) } }
            .sortedByDescending { it.third }
        for ((kind, song, _) in rest) {
            if (picked.size >= size) break
            if (!pickedIds.add(song.id)) continue
            kinds[song.id] = kind
            picked += song
        }
    }
    val ordered = flowOrder(picked, orderSeed, features)
    return MixResult(ordered, kinds)
}
