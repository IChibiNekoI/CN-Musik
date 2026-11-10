package com.example.musikplayer

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.HashMap
import java.util.Locale
import java.util.TimeZone

/** Ein Titel mit seiner Anzahl an Wiedergaben (Ranglisten). */
class StatTrack(val song: Song, val count: Int)

/** Ein Name (Künstler, Album, Jahrzehnt) mit seiner Anzahl an Wiedergaben. */
class StatName(val name: String, val count: Int)

/** Der Titel mit den meisten Wiedergaben an einem einzigen Tag: [day] ist die Nummer des lokalen Tages. */
class StatObsession(val song: Song, val plays: Int, val day: Long)

/** Ein Album, dessen Titel im Zeitraum alle mindestens einmal liefen. */
class StatAlbum(val name: String, val tracks: Int, val plays: Int)

/** Zeitfenster der Statistik in lokalen Tagen. [days] = 0 steht für die gesamte Zeit. */
class StatsWindow(val fromSec: Long, val toSec: Long, val startDay: Long, val endDay: Long, val days: Int)

/**
 * Bitrate-Verteilung der Bibliothek: [buckets] zählt die Titel je Stufe (unter 128, 128 bis 255, 256 bis 319 und ab 320 kbps),
 * [lossless] die verlustfreien davon, [counted] alle Titel, deren Bitrate oder Format bekannt ist.
 */
class AudioFacts(val buckets: IntArray, val lossless: Int, val counted: Int)

/** Zahlen zur ganzen Bibliothek, unabhängig vom Zeitraum. */
class LibraryFacts(val tracks: Int, val albums: Int, val artists: Int, val durationMs: Long, val audio: AudioFacts)

/** Alles, was die Statistik für ein Zeitfenster zeigt. */
class StatsResult(
    val plays: Int,
    val listenMs: Long,
    val tracks: Int,
    val artists: Int,
    val activeDays: Int,
    val totalDays: Int,
    val streak: Int,
    val barValues: IntArray,
    val barLabels: List<String>,
    /** Je Balken der Text für den Tooltip: Datum, Zeitspanne, Monat oder Jahr. */
    val barTips: List<String>,
    /** Wiedergaben je Tag als Tabelle: Index = Woche * 7 + Wochentag (Montag = 0); -1 liegt außerhalb des Fensters. */
    val heat: IntArray,
    val heatWeeks: Int,
    /** Nummer des lokalen Tages, auf den die erste Spalte der Tabelle ([heat]) beginnt (ein Montag). */
    val heatStartDay: Long,
    /** Monatsname über einer Spalte der Tabelle, wo ein neuer Monat beginnt, sonst leer. */
    val heatMonths: List<String>,
    val hours: IntArray,
    val weekdays: IntArray,
    /** Wiedergaben je Wochentag und Stunde: Index = Wochentag (Montag = 0) * 24 + Stunde. */
    val weekHours: IntArray,
    val topTracks: List<StatTrack>,
    val topArtists: List<StatName>,
    val topAlbums: List<StatName>,
    val repeatRate: Int,
    val tracksTwice: Int,
    val tracksTen: Int,
    val libraryUse: Int,
    /** Früheste und späteste Uhrzeit einer Wiedergabe in Minuten seit Mitternacht; -1 ohne Wiedergaben. */
    val earliestMin: Int,
    val latestMin: Int,
    val obsession: List<StatObsession>,
    val discoveries: List<StatTrack>,
    val discoveryCount: Int,
    val completedAlbums: List<StatAlbum>,
    val decades: List<StatName>
)

private const val DAY_S = 86_400L

/** Nummer des lokalen Tages (Tage seit 1970) für einen Zeitpunkt in Sekunden. */
internal fun statDay(sec: Long): Long =
    Math.floorDiv(sec + TimeZone.getDefault().getOffset(sec * 1000L) / 1000L, DAY_S)

/** Beginn eines lokalen Tages in Sekunden. */
internal fun statDayStart(day: Long): Long {
    val guess = day * DAY_S
    return guess - TimeZone.getDefault().getOffset(guess * 1000L) / 1000L
}

/** Wochentag eines lokalen Tages: Montag = 0 bis Sonntag = 6 (der 1.1.1970 war ein Donnerstag). */
internal fun statWeekday(day: Long): Int = Math.floorMod(day + 3L, 7L).toInt()

/** Die Sprache der App (nicht unbedingt die des Geräts), für Datum und Zahlen. */
internal fun statLocale(): Locale = strCtx?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()

private fun usable(name: String) = name.isNotBlank() && !name.equals("<unknown>", ignoreCase = true)

/** "79h 48min", bei unter einer Stunde "48min". */
internal fun statDuration(ms: Long): String {
    val minutes = ms / 60_000L
    val h = minutes / 60
    return if (h > 0) "${h}h ${minutes % 60}min" else "${minutes}min"
}

/** Uhrzeit aus Minuten seit Mitternacht: "16:36". */
internal fun statClock(minute: Int): String = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

/** Bitrate eines Titels in kbps: laut MediaStore, sonst aus Dateigröße und Laufzeit geschätzt; 0 = unbekannt. */
internal fun statKbps(s: Song): Int {
    if (s.bitrate > 0) return (s.bitrate + 500) / 1000
    if (s.size > 0L && s.durationMs > 0L) return (s.size * 8L / s.durationMs).toInt()
    return 0
}

private val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "wave", "aif", "aiff", "aifc", "ape", "wv", "alac", "dsf", "dff")

/** Verlustfrei nach Dateiendung. ALAC steckt wie AAC in .m4a: AAC bleibt unter etwa 512 kbps, ALAC liegt meist deutlich darüber. */
internal fun statIsLossless(s: Song, kbps: Int): Boolean {
    val ext = s.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext in LOSSLESS_EXTENSIONS) return true
    return (ext == "m4a" || ext == "mp4") && kbps >= 600
}

/** Stufe der Bitrate: 0 = unter 128, 1 = 128 bis 255, 2 = 256 bis 319, 3 = ab 320 kbps. */
internal fun statBitrateBucket(kbps: Int): Int = when {
    kbps < 128 -> 0
    kbps < 256 -> 1
    kbps < 320 -> 2
    else -> 3
}

internal fun libraryFacts(songs: List<Song>): LibraryFacts {
    val albums = HashSet<String>()
    val artists = HashSet<String>()
    var duration = 0L
    val bitrates = IntArray(4)
    var lossless = 0
    var counted = 0
    for (s in songs) {
        if (s.album.isNotBlank()) albums.add(albumKeyOf(s))
        if (usable(s.artist)) artists.add(s.artist)
        duration += s.durationMs
        val kbps = statKbps(s)
        val isLossless = statIsLossless(s, kbps)
        if (kbps > 0 || isLossless) {
            counted++
            // Verlustfrei ohne bekannte Bitrate gehört zur höchsten Stufe
            bitrates[if (kbps > 0) statBitrateBucket(kbps) else 3]++
            if (isLossless) lossless++
        }
    }
    return LibraryFacts(songs.size, albums.size, artists.size, duration, AudioFacts(bitrates, lossless, counted))
}

private fun weekdayShort(day: Long, locale: Locale): String =
    LocalDate.ofEpochDay(day).dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".").take(2)

private fun monthShort(date: LocalDate, locale: Locale): String =
    date.month.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".").take(3)

/**
 * Balken des Wiedergaben-Diagramms: je nach Zeitraum je Tag, je Woche oder je Monat, mit sparsamen Beschriftungen.
 * Dazu je Balken der volle Text für den Tooltip (Datum, Zeitspanne, Monat oder Jahr).
 */
private fun buckets(dayCounts: IntArray, w: StatsWindow, locale: Locale): Triple<IntArray, List<String>, List<String>> {
    val n = dayCounts.size
    val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    fun date(day: Long): String = dateFmt.format(LocalDate.ofEpochDay(day))
    fun span(a: Long, b: Long): String = if (a >= b) date(a) else date(a) + " – " + date(b)
    if (w.days in 1..31) {
        // Woche und Monat: je Tag ein Balken
        val labels = List(n) { i ->
            val day = w.startDay + i
            when {
                n <= 7 -> weekdayShort(day, locale)
                i == 0 || i == n - 1 || (n - 1 - i) % 7 == 0 -> LocalDate.ofEpochDay(day).dayOfMonth.toString()
                else -> ""
            }
        }
        val tips = List(n) { i -> date(w.startDay + i) }
        return Triple(dayCounts.copyOf(), labels, tips)
    }
    if (w.days in 32..200) {
        // 3 und 6 Monate: je Woche ein Balken
        val weeks = (n + 6) / 7
        val v = IntArray(weeks)
        for (i in 0 until n) v[i / 7] += dayCounts[i]
        val labels = List(weeks) { k ->
            if (k % 4 == 0) monthShort(LocalDate.ofEpochDay(w.startDay + k * 7L), locale) else ""
        }
        val tips = List(weeks) { k -> span(w.startDay + k * 7L, minOf(w.startDay + k * 7L + 6L, w.endDay)) }
        return Triple(v, labels, tips)
    }
    if (w.days > 200) {
        // Jahr: zwölf Balken zu je gut 30 Tagen
        val v = IntArray(12)
        for (i in 0 until n) v[Math.min(11, i * 12 / n)] += dayCounts[i]
        val labels = List(12) { k ->
            if (k % 2 == 0) monthShort(LocalDate.ofEpochDay(w.startDay + k.toLong() * n / 12), locale) else ""
        }
        // Balken k deckt die Tage i mit i * 12 / n == k ab
        val tips = List(12) { k ->
            val from = (k * n + 11) / 12
            val to = ((k + 1) * n + 11) / 12 - 1
            span(w.startDay + from, w.startDay + to)
        }
        return Triple(v, labels, tips)
    }
    // Gesamte Zeit: je Monat, bei mehr als drei Jahren je Jahr
    val months = LinkedHashMap<Int, Int>()
    for (i in 0 until n) {
        val d = LocalDate.ofEpochDay(w.startDay + i)
        val key = d.year * 12 + d.monthValue - 1
        months[key] = (months[key] ?: 0) + dayCounts[i]
    }
    if (months.size > 36) {
        val years = LinkedHashMap<Int, Int>()
        for ((key, c) in months) years[key / 12] = (years[key / 12] ?: 0) + c
        val names = years.keys.map { it.toString() }
        return Triple(years.values.toIntArray(), names, names)
    }
    val keys = months.keys.toList()
    val labels = keys.mapIndexed { idx, key ->
        if (idx % 3 == 0) monthShort(LocalDate.of(key / 12, key % 12 + 1, 1), locale) else ""
    }
    val monthFmt = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val tips = keys.map { key -> monthFmt.format(LocalDate.of(key / 12, key % 12 + 1, 1)) }
    return Triple(months.values.toIntArray(), labels, tips)
}

/**
 * Rechnet die Statistik für ein Zeitfenster aus dem ganzen Hörverlauf. Gehört auf einen Hintergrund-Thread.
 * Titel, die nicht mehr in der Bibliothek sind, zählen nicht mit.
 */
internal fun computeStats(
    snap: PlayHistory.Snapshot,
    songs: List<Song>,
    totals: Map<Long, PlayTotal>,
    w: StatsWindow,
    locale: Locale
): StatsResult {
    val byId = HashMap<Long, Song>(songs.size * 2)
    for (s in songs) byId[s.id] = s

    // Erste Wiedergabe und Anzahl je Titel im ganzen Verlauf: Grundlage für die Entdeckungen
    val firstSec = HashMap<Long, Long>()
    val histCount = HashMap<Long, Int>()
    for (i in snap.ids.indices) {
        val id = snap.ids[i]
        val t = snap.secs[i]
        val f = firstSec[id]
        if (f == null || t < f) firstSec[id] = t
        histCount[id] = (histCount[id] ?: 0) + 1
    }

    val spanDays = (w.endDay - w.startDay + 1).toInt().coerceAtLeast(1)
    val dayCounts = IntArray(spanDays)
    val countById = HashMap<Long, Int>()
    val perDayById = HashMap<Long, Int>()   // Schlüssel: Titel * 20000 + Tag im Fenster
    val hours = IntArray(24)
    val weekdays = IntArray(7)
    val weekHours = IntArray(7 * 24)
    var plays = 0
    var listenMs = 0L
    var earliest = -1
    var latest = -1
    val tz = TimeZone.getDefault()
    for (i in snap.ids.indices) {
        val t = snap.secs[i]
        if (t < w.fromSec || t >= w.toSec) continue
        val id = snap.ids[i]
        val song = byId[id] ?: continue
        val local = t + tz.getOffset(t * 1000L) / 1000L
        val day = Math.floorDiv(local, DAY_S)
        val idx = (day - w.startDay).toInt()
        if (idx < 0 || idx >= spanDays) continue
        plays++
        listenMs += song.durationMs
        countById[id] = (countById[id] ?: 0) + 1
        dayCounts[idx]++
        val secOfDay = Math.floorMod(local, DAY_S).toInt()
        hours[secOfDay / 3600]++
        weekdays[statWeekday(day)]++
        weekHours[statWeekday(day) * 24 + secOfDay / 3600]++
        val minute = secOfDay / 60
        if (earliest < 0 || minute < earliest) earliest = minute
        if (minute > latest) latest = minute
        val key = id * 20_000L + idx
        perDayById[key] = (perDayById[key] ?: 0) + 1
    }

    // Tage mit Wiedergaben und längste Serie aufeinanderfolgender Tage
    var active = 0
    var streak = 0
    var run = 0
    for (c in dayCounts) {
        if (c > 0) {
            active++
            run++
            if (run > streak) streak = run
        } else run = 0
    }

    val (barValues, barLabels, barTips) = buckets(dayCounts, w, locale)

    // Tage-Tabelle (Wochen als Spalten); bei gesamter Zeit nur die letzten gut 53 Wochen
    var heatStart = w.startDay
    if (w.days == 0 && w.endDay - heatStart > 371) heatStart = w.endDay - 371
    val firstMonday = heatStart - statWeekday(heatStart)
    val heatWeeks = ((w.endDay - firstMonday) / 7 + 1).toInt()
    val heat = IntArray(heatWeeks * 7) { -1 }
    var d = heatStart
    while (d <= w.endDay) {
        heat[((d - firstMonday) / 7).toInt() * 7 + statWeekday(d)] = dayCounts[(d - w.startDay).toInt()]
        d++
    }

    // Monatsnamen über der Tabelle: dort, wo eine Spalte in einem neuen Monat beginnt
    val heatMonths = List(heatWeeks) { wk ->
        val date = LocalDate.ofEpochDay(firstMonday + wk * 7L)
        if (wk == 0 || LocalDate.ofEpochDay(firstMonday + wk * 7L - 7L).monthValue != date.monthValue) monthShort(date, locale) else ""
    }

    // Ranglisten
    val ranked = ArrayList<StatTrack>(countById.size)
    for ((id, c) in countById) byId[id]?.let { ranked.add(StatTrack(it, c)) }
    ranked.sortWith(compareByDescending<StatTrack> { it.count }.thenBy { it.song.title })
    val artistCounts = HashMap<String, Int>()
    val albumCounts = HashMap<String, Int>()
    val decadeCounts = HashMap<Int, Int>()
    val artistSet = HashSet<String>()
    for (t in ranked) {
        val s = t.song
        if (usable(s.artist)) {
            artistCounts[s.artist] = (artistCounts[s.artist] ?: 0) + t.count
            artistSet.add(s.artist)
        }
        if (s.album.isNotBlank()) albumCounts[s.album] = (albumCounts[s.album] ?: 0) + t.count
        if (s.year in 1900..2100) {
            val decade = s.year / 10 * 10
            decadeCounts[decade] = (decadeCounts[decade] ?: 0) + t.count
        }
    }
    fun names(m: Map<String, Int>) = m.entries.map { StatName(it.key, it.value) }
        .sortedWith(compareByDescending<StatName> { it.count }.thenBy { it.name })

    // Gewohnheiten
    val distinct = ranked.size
    val repeatRate = if (plays > 0) Math.round((plays - distinct) * 100f / plays) else 0
    val libraryUse = if (songs.isNotEmpty()) Math.round(distinct * 100f / songs.size) else 0

    // Obsession: die meisten Wiedergaben eines Titels an einem einzigen Tag
    val best = HashMap<Long, LongArray>()
    for ((key, c) in perDayById) {
        val id = key / 20_000L
        val dayIdx = key % 20_000L
        val b = best[id]
        if (b == null || c > b[0]) best[id] = longArrayOf(c.toLong(), dayIdx)
    }
    val obsession = ArrayList<StatObsession>()
    for ((id, b) in best) {
        val s = byId[id] ?: continue
        if (b[0] >= 2L) obsession.add(StatObsession(s, b[0].toInt(), w.startDay + b[1]))
    }
    obsession.sortWith(compareByDescending<StatObsession> { it.plays }.thenBy { it.song.title })

    // Entdeckungen: im Zeitraum zum ersten Mal gehört. Ältere Wiedergaben vor dem Verlauf kennt nur die Langzeit-Statistik:
    // hat sie mehr Wiedergaben als der Verlauf, war der Titel schon vorher bekannt.
    val discoveries = ranked.filter { t ->
        val first = firstSec[t.song.id] ?: 0L
        first >= w.fromSec && first < w.toSec && (totals[t.song.id]?.count ?: 0) <= (histCount[t.song.id] ?: 0)
    }

    // Abgeschlossene Alben: alle Titel (mindestens zwei) liefen im Zeitraum
    val byAlbum = HashMap<String, ArrayList<Song>>()
    for (s in songs) if (s.album.isNotBlank()) byAlbum.getOrPut(albumKeyOf(s)) { ArrayList() }.add(s)
    val completed = ArrayList<StatAlbum>()
    for (list in byAlbum.values) {
        if (list.size < 2) continue
        var all = true
        var sum = 0
        for (s in list) {
            val c = countById[s.id] ?: 0
            if (c == 0) { all = false; break }
            sum += c
        }
        if (all) completed.add(StatAlbum(list[0].album, list.size, sum))
    }
    completed.sortWith(compareByDescending<StatAlbum> { it.plays }.thenBy { it.name })

    return StatsResult(
        plays = plays,
        listenMs = listenMs,
        tracks = distinct,
        artists = artistSet.size,
        activeDays = active,
        totalDays = spanDays,
        streak = streak,
        barValues = barValues,
        barLabels = barLabels,
        barTips = barTips,
        heat = heat,
        heatWeeks = heatWeeks,
        heatStartDay = firstMonday,
        heatMonths = heatMonths,
        hours = hours,
        weekdays = weekdays,
        weekHours = weekHours,
        topTracks = ranked.take(20),
        topArtists = names(artistCounts).take(10),
        topAlbums = names(albumCounts).take(10),
        repeatRate = repeatRate,
        tracksTwice = ranked.count { it.count >= 2 },
        tracksTen = ranked.count { it.count >= 10 },
        libraryUse = libraryUse,
        earliestMin = earliest,
        latestMin = latest,
        obsession = obsession.take(5),
        discoveries = discoveries.take(5),
        discoveryCount = discoveries.size,
        completedAlbums = completed.take(5),
        decades = decadeCounts.entries.map { StatName(it.key.toString(), it.value) }.sortedBy { it.name }.takeLast(6)
    )
}
