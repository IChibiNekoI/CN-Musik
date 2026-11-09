package com.example.musikplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date

/** Karte im Stil der Mix-Karte: abgerundet, mit Symbol und Titel in der Kopfzeile. */
@Composable
private fun StatCard(
    title: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/** Kleine Pille mit Wert und Beschriftung. */
@Composable
private fun StatPill(value: String, label: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatPills(items: List<Pair<String, String>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in items) StatPill(value, label)
    }
}

/** Die Zahl der Wiedergaben als Marke, genau wie in der Titelzeile von "Meistens gespielt". */
@Composable
private fun CountBadge(count: Int) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1
        )
    }
}

@Composable
private fun TrackLine(rank: Int, song: Song, count: Int, sub: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "$rank", modifier = Modifier.width(20.dp), textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Cover(song, 40.dp, corner = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub ?: song.artist, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        CountBadge(count)
    }
}

/** Waagerechte Pillen-Balken mit Name und Zahl, der längste gleich voll. */
@Composable
private fun NameBars(items: List<StatName>) {
    val top = (items.firstOrNull()?.count ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((item.count.toFloat() / top).coerceIn(0.1f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                )
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.count}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Statistik: Bibliothek in Zahlen und der Hörverlauf in Diagrammen und Ranglisten, für einen wählbaren Zeitraum.
 * Alles wird im Hintergrund aus dem dauerhaften Hörverlauf ([PlayHistory]) gerechnet. Die Hördauer ist geschätzt
 * (Wiedergaben mal Titellänge), denn eine Wiedergabe zählt schon nach wenigen Sekunden.
 */
@Composable
fun StatsPage(songs: List<Song>, bottomExtra: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val locale = remember { statLocale() }
    val nf = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val dateFmt = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val ranges = remember { PlayRange.values().filter { it.days == 0 || it.days >= 7 } }
    var range by remember { mutableStateOf(PlayRange.MONTH) }
    val today = remember { statDay(System.currentTimeMillis() / 1000L) }
    var endDay by remember { mutableLongStateOf(today) }

    val snap by produceState<PlayHistory.Snapshot?>(null) { value = withContext(Dispatchers.IO) { PlayHistory.snapshot(ctx) } }
    val totals by produceState<Map<Long, PlayTotal>>(emptyMap()) { value = withContext(Dispatchers.IO) { readPlayTotals(ctx) } }
    val library by produceState<LibraryFacts?>(null, songs) { value = withContext(Dispatchers.Default) { libraryFacts(songs) } }

    // Zeitfenster: Das Fenster lässt sich nicht weiter zurückschieben, als der Verlauf reicht
    val firstDay = snap?.let { if (it.firstSec > 0L) minOf(statDay(it.firstSec), today) else today } ?: today
    val n = range.days.toLong()
    val minEnd = if (n == 0L) today else minOf(today, firstDay + n - 1)
    val end = endDay.coerceIn(minEnd, today)
    val win = if (n == 0L) {
        StatsWindow(0L, Long.MAX_VALUE, firstDay, today, 0)
    } else {
        StatsWindow(statDayStart(end - n + 1), statDayStart(end + 1), end - n + 1, end, range.days)
    }

    var result by remember { mutableStateOf<StatsResult?>(null) }
    LaunchedEffect(snap, totals, songs, range, end) {
        val s = snap ?: return@LaunchedEffect
        result = withContext(Dispatchers.Default) { computeStats(s, songs, totals, win, locale) }
    }

    val weekdayNames = remember(locale) {
        List(7) { LocalDate.of(2024, 1, 1 + it).dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".").take(2) }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Bibliothek in Zahlen
        library?.let { lib ->
            StatCard(str(R.string.stats_library), { FolderIcon() }) {
                StatPills(
                    listOf(
                        nf.format(lib.tracks) to str(R.string.stats_tracks),
                        nf.format(lib.albums) to str(R.string.stats_albums),
                        nf.format(lib.artists) to str(R.string.stats_artists),
                        statDuration(lib.durationMs) to str(R.string.stats_library_duration)
                    )
                )
            }
        }

        // Zeitraum wählen, durch die Zeit schieben, Kennzahlen
        StatCard(str(R.string.stats_period), { CalendarIcon() }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in ranges) {
                    FilterChip(
                        selected = r == range,
                        onClick = { range = r; endDay = today },
                        label = { Text(str(r.label)) }
                    )
                }
            }
            val from = if (n == 0L) firstDay else end - n + 1
            val to = if (n == 0L) today else end
            val label = if (from == to) dateFmt.format(Date(statDayStart(from) * 1000L))
            else dateFmt.format(Date(statDayStart(from) * 1000L)) + " – " + dateFmt.format(Date(statDayStart(to) * 1000L))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val canBack = n > 0L && end > minEnd
                val canForward = n > 0L && end < today
                BounceIconButton(
                    onClick = { if (canBack) endDay = maxOf(minEnd, end - n) },
                    modifier = Modifier.alpha(if (canBack) 1f else 0.3f)
                ) { ChevronRightIcon(modifier = Modifier.rotate(180f)) }
                Text(
                    label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                BounceIconButton(
                    onClick = { if (canForward) endDay = minOf(today, end + n) },
                    modifier = Modifier.alpha(if (canForward) 1f else 0.3f)
                ) { ChevronRightIcon() }
            }
            result?.let { r ->
                StatPills(
                    listOf(
                        nf.format(r.plays) to str(R.string.stats_plays),
                        "≈ " + statDuration(r.listenMs) to str(R.string.stats_listen_time),
                        nf.format(r.tracks) to str(R.string.stats_tracks),
                        nf.format(r.artists) to str(R.string.stats_artists),
                        "${r.activeDays} / ${r.totalDays}" to str(R.string.stats_days),
                        plural(R.plurals.n_days, r.streak) to str(R.string.stats_streak)
                    )
                )
            }
        }

        val r = result
        if (r != null && r.plays == 0) {
            Text(
                str(R.string.most_played_empty),
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (r != null && r.plays > 0) {
            StatCard(str(R.string.stats_plays), { MusicNoteIcon() }) {
                StatBars(r.barValues, r.barLabels)
            }
            StatCard(str(R.string.stats_cumulative), { ClockIcon() }) {
                StatCumulative(r.barValues, r.barLabels)
            }
            StatCard(str(R.string.stats_days_chart), { CalendarIcon() }) {
                StatHeatmap(r.heat, r.heatWeeks)
            }

            // Höruhr: zu welcher Tageszeit und an welchem Wochentag du hörst
            StatCard(str(R.string.stats_clock), { ClockIcon() }) {
                var peak = 0
                for (h in 1 until 24) if (r.hours[h] > r.hours[peak]) peak = h
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StatClock(r.hours, statClock(peak * 60), str(R.string.stats_peak))
                }
                StatBars(r.weekdays, weekdayNames, chartHeight = 80.dp)
            }

            StatCard(str(R.string.stats_habits), { TrendIcon() }) {
                val items = ArrayList<Pair<String, String>>()
                items.add("${r.repeatRate} %" to str(R.string.stats_repeat_rate))
                items.add(nf.format(r.tracksTwice) to str(R.string.stats_twice))
                items.add(nf.format(r.tracksTen) to str(R.string.stats_ten))
                items.add("${r.libraryUse} %" to str(R.string.stats_library_use))
                if (r.earliestMin >= 0) items.add(statClock(r.earliestMin) to str(R.string.stats_earliest))
                if (r.latestMin >= 0) items.add(statClock(r.latestMin) to str(R.string.stats_latest))
                StatPills(items)
            }

            if (r.obsession.isNotEmpty()) {
                StatCard(str(R.string.stats_obsession), { RepeatIcon() }) {
                    Text(str(R.string.stats_obsession_sub), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.obsession.forEachIndexed { i, o ->
                        val day = dateFmt.format(Date(statDayStart(o.day) * 1000L))
                        TrackLine(i + 1, o.song, o.plays, sub = o.song.artist + " · " + day)
                    }
                }
            }

            if (r.discoveryCount > 0) {
                StatCard(str(R.string.stats_discoveries), { SparkleIcon() }) {
                    Text(str(R.string.stats_discoveries_sub), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatPills(listOf(plural(R.plurals.n_tracks, r.discoveryCount) to ""))
                    r.discoveries.forEachIndexed { i, t -> TrackLine(i + 1, t.song, t.count) }
                }
            }

            StatCard(str(R.string.stats_top_tracks), { TrophyIcon() }) {
                var more by remember { mutableStateOf(false) }
                val shown = if (more) r.topTracks else r.topTracks.take(5)
                shown.forEachIndexed { i, t -> TrackLine(i + 1, t.song, t.count) }
                if (r.topTracks.size > 5) {
                    TextButton(onClick = { more = !more }) {
                        Text(str(if (more) R.string.stats_show_less else R.string.stats_show_more))
                    }
                }
            }

            // Meistens gespielt: der Künstler und das Album mit den meisten Wiedergaben (Genres gibt es hier nicht)
            val topArtist = r.topArtists.firstOrNull()
            val topAlbum = r.topAlbums.firstOrNull()
            if (topArtist != null || topAlbum != null) {
                StatCard(str(R.string.most_played_title), { AlbumDiscIcon() }) {
                    topArtist?.let { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(str(R.string.stats_artist), modifier = Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(a.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CountBadge(a.count)
                        }
                    }
                    topAlbum?.let { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(str(R.string.stats_album), modifier = Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(a.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CountBadge(a.count)
                        }
                    }
                }
            }

            if (r.topArtists.isNotEmpty()) {
                StatCard(str(R.string.stats_top_artists), { MicrophoneIcon() }) {
                    NameBars(r.topArtists)
                }
            }

            if (r.completedAlbums.isNotEmpty()) {
                StatCard(str(R.string.stats_completed_albums), { AlbumDiscIcon() }) {
                    for (a in r.completedAlbums) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(plural(R.plurals.n_tracks, a.tracks), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CountBadge(a.plays)
                        }
                    }
                }
            }

            // Hördauer im Vergleich zur Länge der ganzen Bibliothek
            val lib = library
            if (lib != null && lib.durationMs > 0L) {
                StatCard(str(R.string.stats_listen_total), { ClockIcon() }) {
                    val fraction = r.listenMs.toFloat() / lib.durationMs
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StatGauge(fraction, "≈ " + statDuration(r.listenMs), String.format(locale, "%.1f %%", fraction * 100f))
                    }
                    Text(
                        str(R.string.stats_library_duration) + ": " + statDuration(lib.durationMs),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (r.decades.isNotEmpty()) {
                StatCard(str(R.string.stats_decades), { CalendarIcon() }) {
                    StatBars(
                        IntArray(r.decades.size) { r.decades[it].count },
                        r.decades.map { it.name + "s" },
                        chartHeight = 90.dp
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp + bottomExtra))
    }
}

/**
 * Die Statistik als eigene Seite über der ganzen App: eigene Kopfzeile mit Zurück-Pfeil, darunter die Karten.
 * Der Miniplayer bleibt davor sichtbar; [bottomSpace] hält die letzte Karte frei davon.
 */
@Composable
fun StatsScreen(songs: List<Song>, bottomSpace: Dp, onBack: () -> Unit) {
    // Fängt Berührungen ab, damit die Seite darunter nicht mitreagiert
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        LibraryBackdrop()
        // Wie beim Scaffold der Hauptseite ausdrücklich setzen: Diese Seite liegt außerhalb davon, ohne Angabe wären Titel,
        // Zurück-Pfeil, Zahlen und Kartensymbole schwarz (die Standardfarbe, wenn keine Fläche eine Textfarbe vorgibt)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                DetailHeader(str(R.string.stats_title), onBack = onBack)
                StatsPage(songs, bottomSpace, Modifier.weight(1f))
            }
        }
    }
}
