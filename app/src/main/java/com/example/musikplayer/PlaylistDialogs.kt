package com.example.musikplayer

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Update-Hinweis: "Herunterladen" öffnet den Link im Browser. */
@Composable
fun UpdateDialog(update: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.update_available)) },
        text = { DialogSystemBars(); Text(str(R.string.update_text, update.version)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                } catch (e: Exception) {
                    Toast.makeText(ctx, str(R.string.toast_link_failed), Toast.LENGTH_LONG).show()
                }
            }) { Text(str(R.string.download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.later)) } }
    )
}

/** Titel für eine Playlist auswählen. [onAdd] bekommt die gewählten IDs in der Reihenfolge der Bibliothek. */
@Composable
fun SongPickerDialog(
    songs: List<Song>,
    target: Playlist,
    onDismiss: () -> Unit,
    onAdd: (List<Long>) -> Unit
) {
    var chosen by remember(target.id) { mutableStateOf(setOf<Long>()) }
    var filter by remember(target.id) { mutableStateOf("") }
    val candidates = remember(songs, target.songIds) { songs.filter { it.id !in target.songIds } }
    val shown = remember(candidates, filter) {
        val q = filter.trim()
        if (q.isEmpty()) candidates
        else candidates.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }
    // Eigenes Fenster: gleich viel Rand links und rechts, oben und unten bleibt Platz
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogSystemBars()
        // Abstand zum Rand (links/rechts so groß wie unten) und Ecken, die zum Gerät passen:
        // Display-Rundung minus Abstand ergibt gleichmäßig verlaufende (konzentrische) Ecken
        val sideGap = 10.dp
        val ctxForCorner = LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val dialogCorner = remember(ctxForCorner) {
            val px = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val insets = ctxForCorner.findActivity()?.window?.decorView?.rootWindowInsets
                listOf(
                    android.view.RoundedCorner.POSITION_TOP_LEFT,
                    android.view.RoundedCorner.POSITION_TOP_RIGHT,
                    android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
                    android.view.RoundedCorner.POSITION_BOTTOM_RIGHT
                ).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull()
            } else null
            // Gerät ohne Angabe: normale Dialog-Rundung
            val dp = px?.let { with(density) { it.toDp() } - sideGap } ?: 28.dp
            dp.coerceIn(16.dp, 56.dp)
        }
        val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
        Surface(
            // fängt Tipps im Fenster selbst ab, damit nur Tipps daneben den Dialog schließen
            modifier = Modifier.fillMaxWidth().padding(horizontal = sideGap, vertical = 56.dp)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(dialogCorner),
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    str(R.string.add_songs),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(
                        str(R.string.no_results),
                        modifier = Modifier.weight(1f, fill = false).padding(24.dp)
                    )
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        items(shown, key = { it.id }) { s ->
                            val on = s.id in chosen
                            // Gleiche Optik wie die Titelübersicht: bei "Einheitlich" abgerundete, eingerückte Berührungswelle
                            val rounded = LocalCornerStyle.current == CornerStyle.UNIFORM
                            val pressSource = remember { MutableInteractionSource() }
                            val toggle = { chosen = if (on) chosen - s.id else chosen + s.id }
                            Box(Modifier.fillMaxWidth()) {
                                if (rounded) {
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .indication(pressSource, LocalIndication.current)
                                    )
                                }
                                ListItem(
                                    headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Cover(s, 48.dp) },
                                    trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                                    colors = clearColors(),
                                    modifier = if (rounded) {
                                        Modifier.clip(RoundedCornerShape(20.dp))
                                            .clickable(interactionSource = pressSource, indication = null) { toggle() }
                                    } else Modifier.clickable { toggle() }
                                )
                            }
                        }
                    }
                }
                // Suche direkt über den Tasten
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(str(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { SearchIcon() },
                    trailingIcon = {
                        if (filter.isNotEmpty()) {
                            BounceIconButton(onClick = { filter = "" }) {
                                CloseIcon(description = str(R.string.clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
                    TextButton(
                        enabled = chosen.isNotEmpty(),
                        onClick = {
                            // in der Reihenfolge der Bibliothek hinzufügen
                            val add = candidates.filter { it.id in chosen }.map { it.id }
                            onAdd(add)
                        }
                    ) { Text(str(R.string.add_n, chosen.size)) }
                }
            }
        }
        }   // Ende Box
    }

}

/** "Zu Playlist hinzufügen": neue Playlist anlegen oder eine bestehende wählen. */
@Composable
fun AddToPlaylistDialog(
    song: Song?,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onCreateNew: (Song) -> Unit,
    onAdd: (Song, Playlist) -> Unit
) {
    val s = rememberLastNonNull(song) ?: return
    BounceAlertDialog(
        visible = song != null,
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.add_to_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    headlineContent = { Text(str(R.string.playlist_new_dots)) },
                    leadingContent = { PlusIcon() },
                    colors = clearColors(),
                    modifier = Modifier.clickable { onCreateNew(s) }
                )
                playlists.forEach { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        colors = clearColors(),
                        modifier = Modifier.clickable { onAdd(s, p) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

/** Name für eine neue Playlist eingeben. */
@Composable
fun NewPlaylistDialog(
    visible: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit
) {
    BounceAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.playlist_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(str(R.string.name)) }
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = onCreate) { Text(str(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

/** Playlist-Bild: vom Speicher wählen oder (nur wenn vorhanden) löschen. */
@Composable
fun PlaylistImageDialog(
    target: Playlist?,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit
) {
    val t = rememberLastNonNull(target) ?: return
    BounceAlertDialog(
        visible = target != null,
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.playlist_image_title)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(str(R.string.playlist_image_pick)) },
                    leadingContent = { ImageEditIcon() },
                    colors = clearColors(),
                    modifier = Modifier.clickable { onPick(t) }
                )
                // Löschen gibt es nur, wenn die Playlist ein eigenes Bild hat
                if (t.cover != null) {
                    ListItem(
                        headlineContent = { Text(str(R.string.delete)) },
                        leadingContent = { TrashIcon() },
                        colors = clearColors(),
                        modifier = Modifier.clickable { onDelete(t) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

/** Playlist umbenennen. [onSave] bekommt den bereinigten, nicht leeren Namen. */
@Composable
fun RenamePlaylistDialog(
    target: Playlist?,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Playlist, String) -> Unit
) {
    val t = rememberLastNonNull(target) ?: return
    val doRename = {
        val n = text.trim()
        if (n.isNotEmpty()) onSave(t, n)
        onDismiss()
    }
    BounceAlertDialog(
        visible = target != null,
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.playlist_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                label = { Text(str(R.string.name)) }
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { doRename() }) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

/** Sicherheitsabfrage vor dem Löschen einer Playlist. */
@Composable
fun DeletePlaylistDialog(
    target: Playlist?,
    onDismiss: () -> Unit,
    onConfirm: (Playlist) -> Unit
) {
    val t = rememberLastNonNull(target) ?: return
    BounceAlertDialog(
        visible = target != null,
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.playlist_delete_title)) },
        text = { Text(str(R.string.playlist_delete_text, t.name)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm(t)
            }) { Text(str(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}
