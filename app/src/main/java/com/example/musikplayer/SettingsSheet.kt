package com.example.musikplayer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.media3.common.Player

@Composable
private fun SheetHeader(title: String, trailing: (@Composable () -> Unit)? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BounceIconButton(onClick = onBack) {
            BackIcon(description = str(R.string.back))
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            // 8 dp mehr Abstand zum Rand: so steht der Knopf genau über den Symbolen der Listenzeilen darunter (z. B. dem Auge)
            Box(Modifier.padding(end = 8.dp)) { trailing() }
        }
    }
}

@Composable
private fun SortOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { onClick() }
    )
}

@Composable
private fun ViewStyleOptions(style: ViewStyle, onChange: (ViewStyle) -> Unit) {
    SortOption(str(R.string.style_list), str(R.string.style_list_sub), !style.grid) {
        onChange(style.copy(grid = false))
    }
    SortOption(str(R.string.style_tiles), str(R.string.style_tiles2_sub), style.grid && style.cols == 2) {
        onChange(ViewStyle(grid = true, cols = 2))
    }
    SortOption(str(R.string.style_tiles), str(R.string.style_tiles3_sub), style.grid && style.cols == 3) {
        onChange(ViewStyle(grid = true, cols = 3))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(
    allSongs: List<Song>,
    hiddenSongs: List<Song>,
    onUnhide: (Song) -> Unit,
    folderRules: FolderRules,
    sort: SortMode,
    onFolderRules: (FolderRules) -> Unit,
    onSort: (SortMode) -> Unit,
    showAudioInfo: Boolean,
    onShowAudioInfo: (Boolean) -> Unit,
    albumStyle: ViewStyle,
    onAlbumStyle: (ViewStyle) -> Unit,
    playlistStyle: ViewStyle,
    onPlaylistStyle: (ViewStyle) -> Unit,
    headerStyle: HeaderStyle,
    foxUnlocked: Boolean,
    onHeaderStyle: (HeaderStyle) -> Unit,
    cornerStyle: CornerStyle,
    onCornerStyle: (CornerStyle) -> Unit,
    playerBg: PlayerBackground,
    onPlayerBg: (PlayerBackground) -> Unit,
    menuUnified: Boolean,
    onMenuUnified: (Boolean) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    language: AppLang,
    onLanguage: (AppLang) -> Unit,
    rescanning: Boolean,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf("main") }

    // Backup: Datei zum Speichern bzw. Auswählen über das Android-Dateisystem-Fenster
    val backupCtx = LocalContext.current
    val backupSongs = remember(allSongs, hiddenSongs) { (allSongs + hiddenSongs).distinctBy { it.id } }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = try {
                backupCtx.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(Backup.create(backupCtx, backupSongs).toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                false
            }
            android.widget.Toast.makeText(
                backupCtx, if (ok) str(R.string.backup_done) else str(R.string.backup_failed), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                backupCtx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
            val result = if (text != null) Backup.restore(backupCtx, text, backupSongs) else null
            if (result != null && result.ok) {
                android.widget.Toast.makeText(
                    backupCtx, str(R.string.backup_restored, result.matched, result.total), android.widget.Toast.LENGTH_LONG
                ).show()
                // Activity neu aufbauen, damit alle Einstellungen und Listen frisch aus dem Backup geladen werden
                var c: android.content.Context = backupCtx
                while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                (c as? android.app.Activity)?.recreate()
            } else {
                android.widget.Toast.makeText(backupCtx, str(R.string.backup_invalid), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)

    // Ordner: Ordnerliste und ausgeschlossene Ordner, Ordner-Browser, Bestätigung von Änderungen
    val folderTree = remember(allSongs) { lazy { FolderTree(allSongs) } }   // erst beim ersten Blättern aufgebaut
    var browseExclude by remember { mutableStateOf(false) }
    var browsePath by remember { mutableStateOf("") }
    var includeOpen by remember { mutableStateOf(true) }
    var excludeOpen by remember { mutableStateOf(false) }
    var pendingChange by remember { mutableStateOf<FolderChange?>(null) }
    // Aus dem Ordner-Browser zurück zum Index, die passende Liste klappt auf
    val leaveBrowser = {
        if (browseExclude) excludeOpen = true else includeOpen = true
        browsePath = ""
        page = "index"
    }
    // Ändert sich dadurch etwas an der Bibliothek, fragt ein Dialog nach; sonst wird still übernommen
    val requestRules: (FolderRules) -> Unit = { next ->
        val (added, removed) = countFolderChange(allSongs, folderRules, next)
        if (added == 0 && removed == 0) {
            if (next.include != folderRules.include || next.exclude != folderRules.exclude) onFolderRules(next)
            Toast.makeText(backupCtx, str(R.string.changes_none), Toast.LENGTH_SHORT).show()
            if (page == "browse") leaveBrowser()
        } else {
            pendingChange = FolderChange(next, added, removed)
        }
    }
    // Zurück-Taste im Ordner-Browser: erst eine Ebene hoch, dann zum Index
    androidx.activity.compose.BackHandler(enabled = page == "browse") {
        if (browsePath.isNotEmpty()) browsePath = parentFolder(browsePath) else page = "index"
    }

    // Stark gerundete Geräteecken (ab 40 dp): ganz unten eine leere Zeile, damit nichts in der Ecke abgeschnitten wirkt
    val cornerView = androidx.compose.ui.platform.LocalView.current
    val cornerDensity = androidx.compose.ui.platform.LocalDensity.current
    val deviceCorner = remember(cornerView) {
        val insets = cornerView.rootWindowInsets
        val px = listOf(
            android.view.RoundedCorner.POSITION_TOP_LEFT,
            android.view.RoundedCorner.POSITION_TOP_RIGHT,
            android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
            android.view.RoundedCorner.POSITION_BOTTOM_RIGHT
        ).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull() ?: 0
        with(cornerDensity) { px.toDp() }
    }
    // Zusätzliche leere Zeile am Ende des Inhalts (scrollt mit, schneidet nichts ab)
    val bottomExtra = if (deviceCorner >= 40.dp) 56.dp else 0.dp

    BounceSheet(onDismissRequest = onDismiss) {
        // Beim Wechsel zwischen den Seiten gleiten Inhalte weich hinein und heraus, die Höhe des Menüs passt sich an
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val ease = Motion.Ease
                if (targetState == "main" || (targetState == "index" && initialState == "browse")) {
                    // zurück: neue Seite kommt von links
                    (slideInHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> -w / 5 } +
                        fadeIn(tween(Motion.MEDIUM - 60, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> w / 5 } +
                            fadeOut(tween(Motion.FAST))) using
                        SizeTransform(clip = true) { _, _ -> tween(Motion.MEDIUM, easing = ease) }
                } else {
                    // weiter: neue Seite kommt von rechts
                    (slideInHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> w / 5 } +
                        fadeIn(tween(Motion.MEDIUM - 60, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM, easing = ease)) { w -> -w / 5 } +
                            fadeOut(tween(Motion.FAST))) using
                        SizeTransform(clip = true) { _, _ -> tween(Motion.MEDIUM, easing = ease) }
                }
            },
            label = "settingsPage"
        ) { p ->
        Column {
        when (p) {
            "main" -> {
                Text(
                    str(R.string.settings),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                // Die Hauptseite ist inzwischen höher als das Menü erlaubt: Der Inhalt scrollt, die Überschrift bleibt stehen
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                Text(
                    str(R.string.settings_library),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.index)) },
                    leadingContent = { HashIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "index" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.sort)) },
                    supportingContent = {
                        Text(
                            when (sort) {
                                SortMode.NAME -> str(R.string.sort_name_az)
                                SortMode.MODIFIED -> str(R.string.sort_modified_summary)
                                SortMode.DATE -> str(R.string.sort_added_summary)
                            }
                        )
                    },
                    leadingContent = { CalendarIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "sort" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.hidden_tracks)) },
                    supportingContent = {
                        Text(
                            if (hiddenSongs.isEmpty()) str(R.string.hidden_tracks_none)
                            else plural(R.plurals.n_tracks, hiddenSongs.size)
                        )
                    },
                    leadingContent = { EyeOffIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "hidden" }
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    str(R.string.settings_appearance),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                val homeCtx = LocalContext.current
                val homeOn = CornerSettings.showHome
                ListItem(
                    headlineContent = { Text(str(R.string.home_setting_title)) },
                    leadingContent = { HomeIcon() },
                    trailingContent = {
                        Switch(checked = homeOn, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !homeOn
                        CornerSettings.showHome = on
                        saveShowHome(homeCtx, on)
                    }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.album_view)) },
                    supportingContent = { Text(albumStyle.label()) },
                    leadingContent = { AlbumDiscIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "albumView" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.playlist_view)) },
                    supportingContent = { Text(playlistStyle.label()) },
                    leadingContent = { GridIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "playlistView" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.design)) },
                    supportingContent = { Text(str(R.string.design_summary)) },
                    leadingContent = { CornersIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "design" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.header)) },
                    supportingContent = { Text(
                            when (headerStyle) {
                                HeaderStyle.LOGO -> str(R.string.header_logo)
                                HeaderStyle.FOX -> str(R.string.header_fox)
                                HeaderStyle.TEXT -> str(R.string.header_summary_default)
                            }
                        ) },
                    leadingContent = { TitleTIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "headerView" }
                )
                val swipeCtx = LocalContext.current
                val swipeOn = CornerSettings.swipeHeader
                ListItem(
                    headlineContent = { Text(str(R.string.swipe_header)) },
                    leadingContent = { SwipeVerticalIcon() },
                    trailingContent = {
                        Switch(checked = swipeOn, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !swipeOn
                        CornerSettings.swipeHeader = on
                        saveSwipeHeader(swipeCtx, on)
                    }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.progress_bar)) },
                    supportingContent = { Text(if (CornerSettings.waveProgress) str(R.string.progress_wave) else str(R.string.progress_bar_style)) },
                    leadingContent = { WaveBarsIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "progressStyle" }
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    str(R.string.settings_playback),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.audio_info)) },
                    leadingContent = { InfoIcon() },
                    trailingContent = {
                        Switch(checked = showAudioInfo, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) { onShowAudioInfo(!showAudioInfo) }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.haptics)) },
                    leadingContent = { VibrationIcon() },
                    trailingContent = {
                        Switch(checked = haptics, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) { onHaptics(!haptics) }
                )
                val gainCtx = LocalContext.current
                val gainOn = CornerSettings.autoGain
                ListItem(
                    headlineContent = { Text(str(R.string.auto_gain)) },
                    leadingContent = { WaveBarsIcon() },
                    trailingContent = {
                        Switch(checked = gainOn, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !gainOn
                        CornerSettings.autoGain = on
                        saveAutoGain(gainCtx, on)
                    }
                )
                val outputCtx = LocalContext.current
                val outputOn = CornerSettings.showOutputDevice
                ListItem(
                    headlineContent = { Text(str(R.string.output_device)) },
                    supportingContent = { Text(str(R.string.output_device_summary)) },
                    leadingContent = { HeadphonesIcon() },
                    trailingContent = {
                        Switch(checked = outputOn, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !outputOn
                        CornerSettings.showOutputDevice = on
                        saveShowOutputDevice(outputCtx, on)
                    }
                )
                val durationCtx = LocalContext.current
                val durationOn = CornerSettings.showSongDuration
                ListItem(
                    headlineContent = { Text(str(R.string.song_duration)) },
                    supportingContent = { Text(str(R.string.song_duration_summary)) },
                    leadingContent = { ClockIcon() },
                    trailingContent = {
                        Switch(checked = durationOn, onCheckedChange = null)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !durationOn
                        CornerSettings.showSongDuration = on
                        saveShowSongDuration(durationCtx, on)
                    }
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(
                    str(R.string.settings_general),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                )
                ListItem(
                    headlineContent = { Text(str(R.string.language)) },
                    supportingContent = {
                        Text(
                            when (language) {
                                AppLang.SYSTEM -> str(R.string.language_system)
                                AppLang.DE -> "Deutsch"
                                AppLang.EN -> "English"
                                AppLang.ES -> "Español"
                            }
                        )
                    },
                    leadingContent = { GlobeIcon() },
                    trailingContent = {
                        ChevronRightIcon()
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "language" }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.backup_title)) },
                    leadingContent = { BackupIcon() },
                    trailingContent = { ChevronRightIcon() },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { page = "backup" }
                )
                Spacer(Modifier.height(32.dp + bottomExtra))
                }
            }
            "index" -> {
                SheetHeader(str(R.string.index)) { page = "main" }
                // Die Ordnerlisten können lang werden: Der Inhalt scrollt, die Überschrift bleibt stehen
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    FolderListSection(
                        title = str(R.string.folder_list),
                        summary = if (folderRules.include.isEmpty()) str(R.string.folder_list_all)
                        else plural(R.plurals.n_folders, folderRules.include.size),
                        paths = folderRules.include,
                        expanded = includeOpen,
                        onExpanded = { includeOpen = it },
                        emptyHint = str(R.string.folder_list_hint),
                        onAdd = { browseExclude = false; browsePath = ""; page = "browse" },
                        onRemove = { requestRules(folderRules.withoutInclude(it)) }
                    )
                    FolderListSection(
                        title = str(R.string.folder_excluded),
                        summary = if (folderRules.exclude.isEmpty()) str(R.string.hidden_tracks_none)
                        else plural(R.plurals.n_folders, folderRules.exclude.size),
                        paths = folderRules.exclude,
                        expanded = excludeOpen,
                        onExpanded = { excludeOpen = it },
                        emptyHint = str(R.string.folder_excluded_hint),
                        onAdd = { browseExclude = true; browsePath = ""; page = "browse" },
                        onRemove = { requestRules(folderRules.withoutExclude(it)) }
                    )
                    ListItem(
                        headlineContent = { Text(str(R.string.rescan)) },
                        supportingContent = {
                            Text(if (rescanning) str(R.string.rescan_busy) else str(R.string.rescan_summary))
                        },
                        leadingContent = { RefreshIcon() },
                        trailingContent = {
                            if (rescanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        },
                        colors = clear,
                        modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { onRescan() }
                    )
                    Spacer(Modifier.height(32.dp + bottomExtra))
                }
            }
            "hidden" -> {
                var hiddenInfo by remember { mutableStateOf(false) }
                SheetHeader(
                    str(R.string.hidden_tracks),
                    onBack = { page = "main" },
                    trailing = {
                        // Info-Knopf: klappt einen kurzen Erklärtext aus, wie das Drei-Punkte-Menü
                        Box {
                            BounceIconButton(onClick = { hiddenInfo = true }) {
                                InfoIcon(description = str(R.string.hidden_tracks_info_button))
                            }
                            BounceMenu(expanded = hiddenInfo, onDismissRequest = { hiddenInfo = false }) {
                                Text(
                                    str(R.string.hidden_tracks_info),
                                    modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                )
                if (hiddenSongs.isEmpty()) {
                    Text(
                        str(R.string.hidden_tracks_empty),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Scrollbar und träge geladen: auch mit vielen ausgeblendeten Titeln bleibt die Seite flüssig
                    LazyColumn(
                        Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(bottom = 32.dp + bottomExtra)
                    ) {
                        items(hiddenSongs, key = { it.id }) { s ->
                            ListItem(
                                headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Cover(s, 48.dp) },
                                trailingContent = {
                                    BounceIconButton(onClick = { onUnhide(s) }) {
                                        EyeIcon(description = str(R.string.show_track))
                                    }
                                },
                                colors = clear
                            )
                        }
                    }
                }
            }
            "sort" -> {
                SheetHeader(str(R.string.sort)) { page = "main" }
                SortOption(str(R.string.sort_added), str(R.string.sort_added_sub), sort == SortMode.DATE) {
                    onSort(SortMode.DATE)
                }
                SortOption(str(R.string.sort_modified), str(R.string.sort_modified_sub), sort == SortMode.MODIFIED) {
                    onSort(SortMode.MODIFIED)
                }
                SortOption(str(R.string.sort_name), str(R.string.sort_name_sub), sort == SortMode.NAME) {
                    onSort(SortMode.NAME)
                }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "backup" -> {
                SheetHeader(str(R.string.backup_title)) { page = "main" }
                ListItem(
                    headlineContent = { Text(str(R.string.backup_create)) },
                    supportingContent = { Text(str(R.string.backup_create_sub)) },
                    leadingContent = { BackupIcon() },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        createBackup.launch("CN-Musik-Backup-$day.json")
                    }
                )
                ListItem(
                    headlineContent = { Text(str(R.string.backup_restore)) },
                    supportingContent = { Text(str(R.string.backup_restore_sub)) },
                    leadingContent = { RestoreIcon() },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        restoreBackup.launch(arrayOf("application/json", "*/*"))
                    }
                )
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "language" -> {
                SheetHeader(str(R.string.language)) { page = "main" }
                SortOption(str(R.string.language_system), null, language == AppLang.SYSTEM) {
                    onLanguage(AppLang.SYSTEM)
                }
                SortOption("Deutsch", null, language == AppLang.DE) { onLanguage(AppLang.DE) }
                SortOption("English", null, language == AppLang.EN) { onLanguage(AppLang.EN) }
                SortOption("Español", null, language == AppLang.ES) { onLanguage(AppLang.ES) }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "progressStyle" -> {
                val progressCtx = LocalContext.current
                SheetHeader(str(R.string.progress_bar)) { page = "main" }
                SortOption(str(R.string.progress_bar_style), str(R.string.progress_bar_style_sub), !CornerSettings.waveProgress) {
                    CornerSettings.waveProgress = false
                    saveWaveProgress(progressCtx, false)
                }
                SortOption(str(R.string.progress_wave), str(R.string.progress_wave_sub), CornerSettings.waveProgress) {
                    CornerSettings.waveProgress = true
                    saveWaveProgress(progressCtx, true)
                }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "headerView" -> {
                SheetHeader(str(R.string.header)) { page = "main" }
                SortOption(str(R.string.standard), str(R.string.header_option_text), headerStyle == HeaderStyle.TEXT) {
                    onHeaderStyle(HeaderStyle.TEXT)
                }
                SortOption(str(R.string.header_logo), str(R.string.header_option_logo_sub), headerStyle == HeaderStyle.LOGO) {
                    onHeaderStyle(HeaderStyle.LOGO)
                }
                if (foxUnlocked) {
                    SortOption(str(R.string.header_fox), str(R.string.header_fox_sub), headerStyle == HeaderStyle.FOX) {
                        onHeaderStyle(HeaderStyle.FOX)
                    }
                }
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "design" -> {
                SheetHeader(str(R.string.design)) { page = "main" }
                Text(
                    str(R.string.corners),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                SortOption(str(R.string.standard), null, cornerStyle == CornerStyle.STANDARD) {
                    onCornerStyle(CornerStyle.STANDARD)
                }
                SortOption(str(R.string.corners_uniform), null, cornerStyle == CornerStyle.UNIFORM) {
                    onCornerStyle(CornerStyle.UNIFORM)
                }
                Text(
                    str(R.string.player_bg),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
                )
                SortOption(str(R.string.bg_solid), null, playerBg == PlayerBackground.SOLID) {
                    onPlayerBg(PlayerBackground.SOLID)
                }
                SortOption(str(R.string.bg_gradient), null, playerBg == PlayerBackground.GRADIENT) {
                    onPlayerBg(PlayerBackground.GRADIENT)
                }
                SortOption(
                    str(R.string.bg_blur),
                    str(R.string.bg_blur_sub),
                    playerBg == PlayerBackground.BLUR
                ) { onPlayerBg(PlayerBackground.BLUR) }
                // Kippschalter: Menüs, Dialoge und Einstellungen wie der Player (nur bei Unschärfe)
                val menuSwitchEnabled = playerBg == PlayerBackground.BLUR
                ListItem(
                    headlineContent = { Text(str(R.string.menu_unified)) },
                    // Der lange Text bleibt immer (unsichtbar) im Layout und reserviert seine Zeilen: Der kurze Hinweis
                    // "Nur bei Unschärfe verfügbar" liegt darüber. So springt die Seite beim Wechseln nicht.
                    supportingContent = {
                        Box {
                            Text(
                                str(R.string.menu_unified_on),
                                modifier = if (menuSwitchEnabled) Modifier
                                else Modifier
                                    .alpha(0f)
                                    .clearAndSetSemantics { }
                            )
                            if (!menuSwitchEnabled) Text(str(R.string.menu_unified_off))
                        }
                    },
                    trailingContent = {
                        Switch(checked = menuUnified, onCheckedChange = null, enabled = menuSwitchEnabled)
                    },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f, enabled = menuSwitchEnabled) { onMenuUnified(!menuUnified) }
                )
                // Schatten unter dem Cover im Player
                val shadowCtx = LocalContext.current
                val shadowOn = CornerSettings.coverShadow
                ListItem(
                    headlineContent = { Text(str(R.string.shadow)) },
                    trailingContent = { Switch(checked = shadowOn, onCheckedChange = null) },
                    colors = clear,
                    modifier = Modifier.bounceClick(pressedScale = 0.97f) {
                        val on = !shadowOn
                        CornerSettings.coverShadow = on
                        saveCoverShadow(shadowCtx, on)
                    }
                )
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "albumView" -> {
                SheetHeader(str(R.string.album_view)) { page = "main" }
                ViewStyleOptions(albumStyle, onAlbumStyle)
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "playlistView" -> {
                SheetHeader(str(R.string.playlist_view)) { page = "main" }
                ViewStyleOptions(playlistStyle, onPlaylistStyle)
                Spacer(Modifier.height(32.dp + bottomExtra))
            }
            "browse" -> {
                SheetHeader(str(if (browseExclude) R.string.folder_pick_exclude else R.string.folder_pick)) {
                    browsePath = ""
                    page = "index"
                }
                FolderBrowser(
                    tree = folderTree.value,
                    rules = folderRules,
                    forExclude = browseExclude,
                    path = browsePath,
                    onPath = { browsePath = it },
                    onPick = { picked ->
                        requestRules(
                            if (browseExclude) folderRules.withExclude(picked) else folderRules.withInclude(picked)
                        )
                    },
                    bottomExtra = bottomExtra
                )
            }
        }
        }   // Ende Column
        }   // Ende AnimatedContent
    }

    // Bestätigung, bevor Ordner-Änderungen die Bibliothek verändern
    FolderChangeDialog(
        change = pendingChange,
        onConfirm = { rules ->
            onFolderRules(rules)
            pendingChange = null
            Toast.makeText(backupCtx, str(R.string.changes_done), Toast.LENGTH_SHORT).show()
            if (page == "browse") leaveBrowser()
        },
        onCancel = { pendingChange = null }
    )
}
