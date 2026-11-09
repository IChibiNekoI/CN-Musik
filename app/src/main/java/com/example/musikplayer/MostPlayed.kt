package com.example.musikplayer

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Länge des Zeitfensters von "Meistens gespielt". [days] = 0 steht für die gesamte Zeit. */
enum class PlayRange(val days: Int, @StringRes val label: Int) {
    DAY(1, R.string.range_day),
    THREE_DAYS(3, R.string.range_3days),
    WEEK(7, R.string.range_week),
    MONTH(30, R.string.range_month),
    THREE_MONTHS(90, R.string.range_3months),
    SIX_MONTHS(180, R.string.range_6months),
    YEAR(365, R.string.range_year),
    ALL(0, R.string.range_all)
}

private const val DAY_SEC = 86_400L
private const val RANGE_KEY = "most_played_range"

/** So weit lässt sich das Zeitfenster mindestens zurückschieben, auch wenn der Verlauf noch jung ist (dann sind die älteren Fenster leer). */
private const val MIN_SLIDE_DAYS = 30L

/**
 * Das Element belegt im Layout nur [slot] Breite, wird aber um [shift] versetzt gezeichnet: So rückt ein Pfeil an die
 * Kante (bündig mit den Symbolen der Kopfzeile), und der Regler daneben bekommt den frei werdenden Platz.
 */
private fun Modifier.slot(slot: Dp, shift: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(slot.roundToPx(), placeable.height) { placeable.place(shift.roundToPx(), 0) }
}

/** Nummer des lokalen Tages (Tage seit 1970) für einen Zeitpunkt in Sekunden. */
private fun dayOf(sec: Long): Long {
    val off = TimeZone.getDefault().getOffset(sec * 1000L) / 1000L
    return Math.floorDiv(sec + off, DAY_SEC)
}

/** Beginn eines lokalen Tages in Sekunden. */
private fun startOfDay(day: Long): Long {
    val guess = day * DAY_SEC
    val off = TimeZone.getDefault().getOffset(guess * 1000L) / 1000L
    return guess - off
}

/**
 * "Meistens gespielt": die Titel nach Anzahl der Wiedergaben in einem wählbaren Zeitraum. Der Zeitraum (Tag bis Jahr,
 * oder gesamte Zeit) lässt sich mit den Pfeilen und dem Regler durch die Zeit schieben. Die Zahl rechts an jeder Zeile
 * sind die Wiedergaben im Zeitraum.
 */
@Composable
fun MostPlayedScreen(
    songs: List<Song>,
    currentId: String?,
    favSet: Set<Long>,
    bottomSpace: Dp,
    onPlay: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val ranges = PlayRange.values()
    // Der zuletzt gewählte Zeitraum bleibt erhalten, auch nach dem Schließen der Ansicht und beim nächsten Start
    var rangeIdx by remember { mutableIntStateOf(prefs(ctx).getInt(RANGE_KEY, PlayRange.MONTH.ordinal)) }
    val range = ranges[rangeIdx.coerceIn(0, ranges.size - 1)]
    val today = remember { dayOf(System.currentTimeMillis() / 1000L) }
    var endDay by rememberSaveable { mutableLongStateOf(today) }
    var menu by remember { mutableStateOf(false) }

    // Ältester Eintrag im Verlauf: weiter zurück lässt sich das Fenster nicht schieben
    val firstSec by produceState(initialValue = 0L) {
        value = withContext(Dispatchers.IO) { PlayHistory.firstSec(ctx) }
    }
    val firstDay = if (firstSec > 0L) dayOf(firstSec) else today
    val n = range.days.toLong()
    val maxEnd = today
    // Der Regler lässt sich immer verschieben: weiter zurück als der Verlauf reicht, sind die Fenster eben leer
    val minEnd = if (n == 0L) today else minOf(firstDay + n - 1, today - MIN_SLIDE_DAYS)
    val end = endDay.coerceIn(minEnd, maxEnd)
    val fromSec = if (n == 0L) 0L else startOfDay(end - n + 1)
    val toSec = if (n == 0L) Long.MAX_VALUE else startOfDay(end + 1)

    var ranked by remember { mutableStateOf<List<PlayHistory.Ranked>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    // Mit jeder gezählten Wiedergabe neu laden, damit Zähler und Reihenfolge live stimmen
    val version = PlayHistory.version
    LaunchedEffect(range, end, version) {
        ranked = withContext(Dispatchers.IO) {
            if (range == PlayRange.ALL) PlayHistory.allTime(ctx) else PlayHistory.ranking(ctx, fromSec, toSec)
        }
        ready = true
    }

    val byId = remember(songs) { songs.associateBy { it.id } }
    // Der laufende Titel zählt schon ab dem Start mit (vorläufig), wenn das Zeitfenster die Gegenwart einschließt
    val pending = PlayHistory.pending
    val includesNow = range == PlayRange.ALL || end == maxEnd
    val shown = remember(ranked, pending, includesNow) {
        if (includesNow) PlayHistory.withPending(ranked, pending, System.currentTimeMillis() / 1000L) else ranked
    }
    val rows = remember(shown, byId) { shown.mapNotNull { r -> byId[r.id]?.let { it to r.count } } }
    val list = remember(rows) { rows.map { it.first } }
    val listState = rememberLazyListState()
    LaunchedEffect(range, end) { listState.scrollToItem(0) }

    Column(modifier) {
        DetailHeader(
            str(R.string.most_played_title),
            onBack = null,   // zurück per Zurück-Geste
            action = if (list.isNotEmpty()) {
                {
                    BounceIconButton(onClick = { onPlay(list, 0) }) {
                        PlayTriangleIcon(description = str(R.string.cd_play))
                    }
                    BounceIconButton(onClick = { onShufflePlay(list) }) {
                        ShuffleIcon(description = str(R.string.cd_shuffle))
                    }
                }
            } else null
        )

        // Zeitraum wählen, mit den Pfeilen und dem Regler durch die Zeit schieben
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = {
                        Box(contentAlignment = Alignment.CenterStart) {
                            // Unsichtbar: Der längste Zeitraum-Name bestimmt die Breite, damit der Schalter bei jedem Zeitraum gleich breit ist
                            for (r in ranges) {
                                Text(
                                    str(r.label), maxLines = 1,
                                    modifier = Modifier.alpha(0f).clearAndSetSemantics { }
                                )
                            }
                            Text(str(range.label), maxLines = 1)
                        }
                    },
                    leadingIcon = { CalendarIcon(iconSize = 18.dp) },
                    trailingIcon = { ChevronRightIcon(iconSize = 18.dp, modifier = Modifier.rotate(90f)) }
                )
                BounceMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (r in ranges) {
                        DropdownMenuItem(
                            text = { Text(str(r.label)) },
                            leadingIcon = { CalendarIcon() },
                            trailingIcon = if (r == range) {
                                { CheckIcon() }
                            } else null,
                            onClick = {
                                menu = false
                                rangeIdx = r.ordinal
                                prefs(ctx).edit().putInt(RANGE_KEY, r.ordinal).apply()
                                endDay = today
                            }
                        )
                    }
                }
            }
            if (n > 0L) {
                BounceIconButton(
                    onClick = { endDay = maxOf(minEnd, end - n) },
                    modifier = Modifier.slot(slot = 40.dp, shift = (-8).dp)
                ) {
                    ChevronRightIcon(modifier = Modifier.rotate(180f))
                }
                // Derselbe Balken wie im Player: ganz rechts = heute, nach links geht es zurück in die Vergangenheit
                Box(Modifier.weight(1f)) {
                    PillProgress(
                        progress = { if (maxEnd > minEnd) (end - minEnd).toFloat() / (maxEnd - minEnd).toFloat() else 1f },
                        onChange = { f -> if (maxEnd > minEnd) endDay = minEnd + (f * (maxEnd - minEnd)).roundToInt() },
                        onFinished = {}
                    )
                }
                BounceIconButton(
                    onClick = { endDay = minOf(maxEnd, end + n) },
                    modifier = Modifier.slot(slot = 38.dp, shift = 0.dp)
                ) {
                    ChevronRightIcon()
                }
            }
        }

        // Von-bis in der gewählten Sprache und Zahl der Titel
        val dates = remember(fromSec, toSec, n) {
            if (n == 0L) null else {
                val fmt = DateFormat.getDateInstance(
                    DateFormat.MEDIUM,
                    strCtx?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
                )
                val from = fmt.format(Date(fromSec * 1000L))
                val to = fmt.format(Date((toSec - 1L) * 1000L))
                if (n == 1L) from else "$from – $to"
            }
        }
        Text(
            listOfNotNull(dates, plural(R.plurals.n_tracks, list.size)).joinToString(" · "),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (ready && rows.isEmpty()) {
            Text(
                str(R.string.most_played_empty),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HapticLazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomSpace)
        ) {
            itemsIndexed(rows) { i, row ->
                val s = row.first
                SongRow(
                    s, s.id.toString() == currentId,
                    onClick = { onPlay(list, i) },
                    onAddToPlaylist = { onAddToPlaylist(s) },
                    isFavorite = s.id in favSet,
                    onToggleFavorite = { onToggleFavorite(s) },
                    playCount = row.second
                )
            }
        }
    }
}

/** Karte der Startseite: führt in "Meistens gespielt". Das Cover zeigt die meistgehörten Titel der letzten 30 Tage. */
@Composable
fun MostPlayedCard(top: List<Song>, onClick: () -> Unit) {
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.97f, hapticSnap = true) { onClick() }
            .clip(RoundedCornerShape(24.dp))
            .background(card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistCover(top, 96.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                str(R.string.most_played_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (top.isEmpty()) str(R.string.most_played_empty) else plural(R.plurals.n_tracks, top.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        ChevronRightIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
