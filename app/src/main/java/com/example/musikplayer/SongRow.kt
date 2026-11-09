package com.example.musikplayer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Dauer als m:ss, ab einer Stunde h:mm:ss */
private fun formatSongDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: (() -> Unit)? = null,
    // Titel aus der Bibliothek ausblenden (nur im Titel-Tab), null = Eintrag fehlt im Menü
    onHide: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    // Tracknummer links vor dem Cover (nur in der Albenansicht), null = keine
    trackNumber: Int? = null,
    // Anzahl der Wiedergaben als kleine Marke vor der Dauer (nur in "Meistens gespielt"), null = keine
    playCount: Int? = null,
    modifier: Modifier = Modifier
) {
    // Die Hervorhebung des laufenden Titels erscheint sofort (kein Aufleuchten) und federt dabei mit kleinem
    // Überschwinger an ihren Platz. Beim bisherigen Titel verschwindet sie fast sofort, damit nie zwei Zeilen leuchten.
    val rowColor by animateColorAsState(
        if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0f),
        animationSpec = if (isCurrent) snap() else tween(80, easing = Motion.Ease),
        label = "rowColor"
    )
    val highlightScale = animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "rowHighlightScale"
    )
    var expanded by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    val rounded = LocalCornerStyle.current == CornerStyle.UNIFORM
    // Gemeinsame Quelle für Drücken: der Klick liegt auf dem ListItem, die Welle wird dahinter
    // im eingerückten Rechteck gezeichnet (gleiche Breite wie die Hervorhebung des laufenden Titels)
    val pressSource = remember { MutableInteractionSource() }
    val hapticCtx = LocalContext.current
    // Gedrückt: die ganze Zeile drückt sich leicht ein und federt zurück
    val rowPress = rememberPressProgress(pressSource)
    Box(
        modifier.fillMaxWidth().graphicsLayer {
            val s = 1f - 0.03f * rowPress.value
            scaleX = s
            scaleY = s
        }
    ) {
    if (rounded) {
        // Einheitlich: abgerundete, leicht eingerückte Hervorhebung. Der Inhalt bleibt an seinem gewohnten Platz.
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .graphicsLayer {
                    scaleX = highlightScale.value
                    scaleY = highlightScale.value
                }
                .clip(RoundedCornerShape(20.dp))
                .background(rowColor)
                .indication(pressSource, LocalIndication.current)
        )
    }
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            if (trackNumber != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$trackNumber",
                        modifier = Modifier.widthIn(min = 22.dp),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(10.dp))
                    Cover(song, 48.dp)
                }
            } else {
                Cover(song, 48.dp)
            }
        },
        trailingContent = {
            // Optional: Dauer des Liedes links neben dem Drei-Punkte-Menü
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (playCount != null) {
                Box(
                    Modifier
                        .padding(end = 14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$playCount",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1
                    )
                }
            }
            if (CornerSettings.showSongDuration && song.durationMs > 0L) {
                Text(
                    formatSongDuration(song.durationMs),
                    // Der Button hat 8 dp Versatz nach rechts, die Dauer rückt entsprechend mit
                    modifier = Modifier.offset(x = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            // 8 dp nach rechts: die drei Punkte stehen genau unter dem Zahnrad und dem Plus der Kopfzeile
            Box(Modifier.offset(x = 8.dp)) {
                BounceIconButton(onClick = { expanded = true }) {
                    MoreVertIcon(description = str(R.string.more))
                }
                BounceMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(str(R.string.track_info)) },
                        leadingIcon = { InfoIcon() },
                        onClick = { expanded = false; showInfo = true }
                    )
                    DropdownMenuItem(
                        text = { Text(str(R.string.edit_tags)) },
                        leadingIcon = { EditIcon() },
                        onClick = { expanded = false; showTags = true }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (isFavorite) str(R.string.fav_remove) else str(R.string.fav_add))
                        },
                        leadingIcon = { HeartIcon(filled = isFavorite) },
                        onClick = { expanded = false; onToggleFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text(str(R.string.add_to_playlist)) },
                        leadingIcon = {
                            PlaylistAddIcon()
                        },
                        onClick = { expanded = false; onAddToPlaylist() }
                    )
                    if (onHide != null) {
                        DropdownMenuItem(
                            text = { Text(str(R.string.hide_track)) },
                            leadingIcon = { EyeOffIcon() },
                            onClick = { expanded = false; onHide() }
                        )
                    }
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text(str(R.string.remove_from_playlist)) },
                            leadingIcon = { TrashIcon() },
                            onClick = { expanded = false; onRemove() }
                        )
                    }
                }
                if (showInfo) SongInfoDialog(song) { showInfo = false }
                if (showTags) TagEditDialog(song) { showTags = false }
            }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (rounded) Color.Transparent else rowColor),
        // Der Klick muss auf dem ListItem selbst liegen: dessen Surface fängt alle Berührungen ab,
        // ein Klick auf der Box dahinter käme nie an.
        modifier = if (rounded) {
            Modifier.clip(RoundedCornerShape(20.dp))
                .clickable(interactionSource = pressSource, indication = null) {
                    Haptics.snap(hapticCtx)   // dieselbe Vibration wie beim Ziehen des Fortschrittsbalkens
                    onClick()
                }
        } else Modifier.clickable(interactionSource = pressSource, indication = null) { onClick() }
    )
    }
}
