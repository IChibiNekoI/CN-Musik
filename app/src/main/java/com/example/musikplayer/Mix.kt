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
    val confidence: Double
)

/** Wie gut passt ein Titel zu deinem typischen Klang? 0 bis 1; ohne Messwert neutral. */
private fun soundMatch(f: TrackFeatures?, p: MixProfile?): Double {
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
    return 0.4 * gT + 0.35 * gE + 0.25 * gB
}

/** Abstand zweier Titel im Klang: 0 = sehr ähnlich, etwa 1 = sehr verschieden; ohne Messwerte neutral. */
private fun soundDistance(a: TrackFeatures?, b: TrackFeatures?): Double {
    if (a == null || b == null || a.failed || b.failed) return 0.4
    val t = if (a.hasTempo && b.hasTempo) 2.0 * tempoDist(tempoPos(a.bpm), tempoPos(b.bpm)) else 0.4
    return 0.5 * t + 0.3 * Math.abs((a.energy - b.energy).toDouble()) + 0.2 * Math.abs((a.brightness - b.brightness).toDouble())
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
 *  - Klang: Tempo, Energie und Helligkeit im Vergleich zu deinem typischen Klang ([features], falls schon gemessen)
 *  - Wiederholungsschutz: Was du gerade erst gehört hast, steht seltener drin
 * Rund ein Fünftel der Plätze gehört Titeln, die du in letzter Zeit nicht gehört hast: neu hinzugekommene und
 * solche, die zu deinem Geschmack passen. Die Mischung wird dynamisch: Je mehr Hörverlauf da ist, desto mehr zählen
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
    features: (Long) -> TrackFeatures? = { null }
): List<Song> {
    if (songs.isEmpty()) return emptyList()
    val nowMs = System.currentTimeMillis()
    val nowSec = nowMs / 1000L
    val byId = songs.associateBy { it.id }

    // Wiedergaben der letzten 30 Tage, jüngere zählen mehr
    val songPlays = HashMap<Long, Double>()
    val artistPlays = HashMap<String, Double>()
    val lastPlay = HashMap<Long, Long>()
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
    }
    val favArtists = HashMap<String, Int>()
    for (id in favorites) {
        val a = byId[id]?.artist?.takeIf { it.isNotBlank() } ?: continue
        favArtists[a] = (favArtists[a] ?: 0) + 1
    }
    // Wie viel Hörverlauf da ist (0 bis 1): Mit wenig Verlauf zählen Favoriten mehr, mit viel die tatsächlichen Wiedergaben
    val confidence = Math.min(1.0, events.size / 150.0)

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

    // Dein typischer Klang (erst ab 5 gemessenen Ankertiteln)
    var pW = 0.0; var pE = 0.0; var pB = 0.0
    var tW = 0.0; var tC = 0.0; var tS = 0.0
    var measured = 0
    for (id in seedIds) {
        val f = features(id) ?: continue
        if (f.failed) continue
        val w = (if (id in favorites) 2.0 else 1.0) + Math.min(songPlays[id] ?: 0.0, 5.0) * 0.5
        measured++
        pW += w; pE += w * f.energy; pB += w * f.brightness
        if (f.hasTempo) {
            val ang = 2.0 * Math.PI * tempoPos(f.bpm)
            tC += w * Math.cos(ang); tS += w * Math.sin(ang); tW += w
        }
    }
    val profile = if (measured >= 5 && pW > 0.0) {
        val hasT = tW > 0.0 && tC * tC + tS * tS > 1e-9
        var pos = if (hasT) Math.atan2(tS, tC) / (2.0 * Math.PI) else 0.0
        if (pos < 0.0) pos += 1.0
        MixProfile(pos, hasT, pE / pW, pB / pW, Math.min(1.0, measured / 15.0))
    } else null

    val favW = 3.0 - confidence            // Favoriten: 3,0 (wenig Verlauf) bis 2,0 (viel Verlauf)
    val playW = 0.3 + 0.3 * confidence     // Wiedergaben: 0,3 bis 0,6 je Wiedergabe
    val known = ArrayList<Pair<Song, Double>>()   // Favoriten und kürzlich gehörte
    val fresh = ArrayList<Pair<Song, Double>>()   // in 30 Tagen nicht gehört, keine Favoriten
    for (s in songs) {
        val isFav = s.id in favorites
        val plays = songPlays[s.id] ?: 0.0
        val artistScore = Math.min((artistPlays[s.artist] ?: 0.0) * 0.4 + (favArtists[s.artist] ?: 0) * 0.5, 5.0)
        val similar = Math.min(coListen[s.id] ?: 0.0, 4.0) * 0.5 + Math.min(if (seedAlbums.isEmpty() || s.album.isBlank()) 0 else (seedAlbums[albumKeyOf(s)] ?: 0), 3) * 0.25
        val sound = soundMatch(features(s.id), profile) * 2.0 * (profile?.confidence ?: 0.0)
        var w: Double
        if (isFav || plays > 0.0) {
            w = 1.0 + (if (isFav) favW else 0.0) + Math.min(plays, 6.0) * playW + artistScore * 0.8 + similar + sound
            w /= 1.0 + 0.9 * (songSkips[s.id] ?: 0.0)
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
            val ageDays = if (s.dateAdded > 0L) Math.max(0.0, (nowSec - s.dateAdded) / 86400.0) else 365.0
            w = 0.5 + artistScore * 0.4 + similar + sound + 1.5 * Math.exp(-ageDays / 45.0)
            w /= 1.0 + 0.9 * (songSkips[s.id] ?: 0.0)
            fresh += s to Math.pow(unitFor(seed, s.id), 1.0 / w)
        }
    }

    val freshSlots = Math.min(Math.round(size * MIX_DISCOVER_SHARE).toInt(), fresh.size)
    val picked = ArrayList<Song>(size)
    for (c in known.sortedByDescending { it.second }.take(size - freshSlots)) picked += c.first
    for (c in fresh.sortedByDescending { it.second }.take(size - picked.size)) picked += c.first
    return flowOrder(picked, orderSeed, features)
}
