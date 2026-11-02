package com.example.musikplayer

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ---------------------------------------------------------------------------------------------------------------
 * Welche Ordner zur Bibliothek zählen
 * --------------------------------------------------------------------------------------------------------------- */

/** Ordnerpfad wie MediaStore ihn liefert, vereinheitlicht: ohne Anfangs-Schrägstrich, mit Schrägstrich am Ende, "" = oberste Ebene. */
fun normFolder(path: String): String {
    val t = path.trim().trim('/')
    return if (t.isEmpty()) "" else "$t/"
}

/** Der Ordner eine Ebene höher ("Music/Pop/" -> "Music/", "Music/" -> ""). */
fun parentFolder(path: String): String {
    val t = path.trimEnd('/')
    val i = t.lastIndexOf('/')
    return if (i < 0) "" else t.substring(0, i + 1)
}

/**
 * Ordner der Bibliothek: Ist die Ordnerliste ([include]) leer, zählen alle Ordner mit Musik, sonst nur diese Ordner
 * samt Unterordnern. Ausgeschlossene Ordner ([exclude]) fallen samt Unterordnern immer heraus.
 */
class FolderRules(val include: Set<String> = emptySet(), val exclude: Set<String> = emptySet()) {

    fun accepts(folder: String): Boolean {
        val f = normFolder(folder)
        if (include.isNotEmpty() && include.none { f.startsWith(it) }) return false
        return exclude.none { f.startsWith(it) }
    }

    val isDefault: Boolean get() = include.isEmpty() && exclude.isEmpty()

    fun filter(songs: List<Song>): List<Song> = if (isDefault) songs else songs.filter { accepts(it.folder) }

    /** Fügt einen Ordner hinzu. Ist er schon über einen übergeordneten Ordner enthalten, ändert sich nichts. */
    fun withInclude(path: String): FolderRules {
        val p = normFolder(path)
        if (p.isEmpty() || include.any { p.startsWith(it) }) return this
        return FolderRules(include.filterNot { it.startsWith(p) }.toSet() + p, exclude)
    }

    fun withoutInclude(path: String): FolderRules = FolderRules(include - path, exclude)

    fun withExclude(path: String): FolderRules {
        val p = normFolder(path)
        if (p.isEmpty() || exclude.any { p.startsWith(it) }) return this
        return FolderRules(include, exclude.filterNot { it.startsWith(p) }.toSet() + p)
    }

    fun withoutExclude(path: String): FolderRules = FolderRules(include, exclude - path)
}

/**
 * Liest die Regeln. Die frühere Auswahl (Liste einzelner Ordner zum Anhaken) wird einmalig übernommen: Die
 * angehakten Ordner stehen danach in der Ordnerliste.
 */
fun readFolderRules(ctx: Context): FolderRules {
    val p = appPrefs(ctx)
    if (!p.contains("folder_include") && !p.contains("folder_exclude")) {
        val old = p.getStringSet("folders", null)
        if (old != null) {
            val all = old.map { normFolder(it) }.filter { it.isNotEmpty() }.toSet()
            val roots = all.filter { a -> all.none { b -> b != a && a.startsWith(b) } }.toSet()
            val rules = FolderRules(roots, emptySet())
            saveFolderRules(ctx, rules)
            p.edit().remove("folders").apply()
            return rules
        }
    }
    return FolderRules(
        p.getStringSet("folder_include", null).orEmpty().toSet(),
        p.getStringSet("folder_exclude", null).orEmpty().toSet()
    )
}

fun saveFolderRules(ctx: Context, rules: FolderRules) {
    appPrefs(ctx).edit()
        .putStringSet("folder_include", HashSet(rules.include))
        .putStringSet("folder_exclude", HashSet(rules.exclude))
        .apply()
}

/** Wie viele Titel kommen durch eine Änderung der Regeln dazu und wie viele fallen heraus? */
fun countFolderChange(songs: List<Song>, before: FolderRules, after: FolderRules): Pair<Int, Int> {
    var added = 0
    var removed = 0
    for (s in songs) {
        val was = before.accepts(s.folder)
        val now = after.accepts(s.folder)
        if (now && !was) added++ else if (was && !now) removed++
    }
    return added to removed
}

/* ---------------------------------------------------------------------------------------------------------------
 * Ordnerbaum aus der Bibliothek
 * --------------------------------------------------------------------------------------------------------------- */

/** Ein Ordner mit Zahlen über alles darin (Unterordner eingerechnet). */
class FolderNode(
    val path: String,
    /** Titel im Ordner und allen Unterordnern */
    val songs: Int,
    /** Titel direkt in diesem Ordner */
    val direct: Int,
    /** Unterordner (eine Ebene), die Musik enthalten */
    val folders: Int,
    /** zuletzt hinzugefügter Titel (Sekunden) */
    val newest: Long
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/**
 * Baum der Ordner, in denen Musik liegt (aus den Pfaden der Bibliothek, ohne zusätzliche Berechtigung).
 * Wird in einem Durchlauf über alle Titel aufgebaut.
 */
class FolderTree(songs: List<Song>) {

    private class Acc {
        var songs = 0
        var direct = 0
        var newest = 0L
        val kids = LinkedHashSet<String>()
    }

    private val nodes = HashMap<String, FolderNode>()
    private val kids = HashMap<String, List<String>>()

    init {
        val acc = HashMap<String, Acc>()
        val root = Acc()
        acc[""] = root
        for (s in songs) {
            root.songs++
            if (s.dateAdded > root.newest) root.newest = s.dateAdded
            val f = normFolder(s.folder)
            if (f.isEmpty()) {
                root.direct++
                continue
            }
            val parts = f.trimEnd('/').split('/')
            var parent = ""
            var path = ""
            for (i in parts.indices) {
                path = "$path${parts[i]}/"
                var a = acc[path]
                if (a == null) {
                    a = Acc()
                    acc[path] = a
                    acc.getValue(parent).kids.add(path)
                }
                a.songs++
                if (s.dateAdded > a.newest) a.newest = s.dateAdded
                if (i == parts.lastIndex) a.direct++
                parent = path
            }
        }
        for ((path, a) in acc) {
            nodes[path] = FolderNode(path, a.songs, a.direct, a.kids.size, a.newest)
            kids[path] = a.kids.toList()
        }
    }

    fun node(path: String): FolderNode? = nodes[path]

    fun children(path: String): List<FolderNode> = kids[path].orEmpty().mapNotNull { nodes[it] }
}

/* ---------------------------------------------------------------------------------------------------------------
 * Ordnerliste und Ausgeschlossene Ordner (im Index-Menü)
 * --------------------------------------------------------------------------------------------------------------- */

/** Kleine Taste "Hinzufügen" mit Plus. */
@Composable
private fun AddPill(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.bounceClick(pressedScale = 0.92f) { onClick() }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlusIcon(iconSize = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(str(R.string.folder_add), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Eine aufklappbare Liste von Ordnern mit "Hinzufügen" und je Eintrag "Entfernen".
 * [summary] steht unter dem Titel (z. B. "2 Ordner"), [emptyHint] erscheint, wenn die Liste leer ist.
 */
@Composable
fun FolderListSection(
    title: String,
    summary: String,
    paths: Set<String>,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    emptyHint: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) -90f else 90f,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.Ease),
        label = "folderListChevron"
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { FolderIcon() },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AddPill(onAdd)
                Spacer(Modifier.width(4.dp))
                ChevronRightIcon(Modifier.graphicsLayer { rotationZ = rotation })
            }
        },
        colors = clear,
        modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { onExpanded(!expanded) }
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Ease)) + fadeIn(tween(Motion.MEDIUM)),
        exit = shrinkVertically(tween(Motion.MEDIUM, easing = Motion.Ease)) + fadeOut(tween(Motion.FAST))
    ) {
        Column {
            if (paths.isEmpty()) {
                Text(
                    emptyHint,
                    modifier = Modifier.padding(start = 72.dp, end = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                paths.sorted().forEach { path ->
                    ListItem(
                        headlineContent = { Text(path.trimEnd('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            TextButton(onClick = { onRemove(path) }) {
                                Text(str(R.string.folder_remove).uppercase())
                            }
                        },
                        colors = clear,
                        modifier = Modifier.padding(start = 40.dp)
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------------------------
 * Ordner wählen: eigener Browser
 * --------------------------------------------------------------------------------------------------------------- */

private enum class FolderSort { NAME, COUNT, DATE }

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (current) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.bounceClick(pressedScale = 0.94f, haptic = false) { onClick() }
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/** Pfad als Brotkrumen: Home › Music › Pop. Ein Tipp springt zu dieser Ebene. */
@Composable
private fun Breadcrumb(path: String, onPath: (String) -> Unit) {
    val crumbs = remember(path) {
        val out = ArrayList<Pair<String, String>>()
        var acc = ""
        if (path.isNotEmpty()) {
            for (part in path.trimEnd('/').split('/')) {
                acc += "$part/"
                out += part to acc
            }
        }
        out
    }
    val scroll = rememberScrollState()
    // zeigt immer die letzte Ebene
    LaunchedEffect(path, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crumb(str(R.string.folder_home), path.isEmpty()) { onPath("") }
        for ((name, p) in crumbs) {
            ChevronRightIcon(iconSize = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Crumb(name, p == path) { onPath(p) }
        }
    }
}

/**
 * Durch die Ordner der Bibliothek blättern und einen Ordner wählen: mit der Taste unten rechts den aktuellen Ordner,
 * mit dem Plus in der Zeile einen Unterordner direkt. [forExclude] wählt die Liste, zu der hinzugefügt wird (nur für die Anzeige,
 * welche Ordner schon dort stehen).
 */
@Composable
fun ColumnScope.FolderBrowser(
    tree: FolderTree,
    rules: FolderRules,
    forExclude: Boolean,
    path: String,
    onPath: (String) -> Unit,
    onPick: (String) -> Unit,
    bottomExtra: Dp
) {
    var sort by remember { mutableStateOf(FolderSort.NAME) }
    var ascending by remember { mutableStateOf(true) }
    val here = tree.node(path)
    val listed = if (forExclude) rules.exclude else rules.include
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)

    Breadcrumb(path, onPath)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            plural(R.plurals.n_folders, here?.folders ?: 0) + " | " + plural(R.plurals.n_tracks, here?.direct ?: 0),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.bounceClick(pressedScale = 0.92f) {
                sort = FolderSort.values()[(sort.ordinal + 1) % FolderSort.values().size]
                // Name von A bis Z, Anzahl und Datum mit dem Größten bzw. Neuesten zuerst
                ascending = sort == FolderSort.NAME
            }
        ) {
            Text(
                when (sort) {
                    FolderSort.NAME -> str(R.string.folder_sort_name)
                    FolderSort.COUNT -> str(R.string.folder_sort_count)
                    FolderSort.DATE -> str(R.string.folder_sort_date)
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        BounceIconButton(onClick = { ascending = !ascending }) {
            ChevronRightIcon(
                Modifier.graphicsLayer { rotationZ = if (ascending) -90f else 90f },
                description = str(R.string.folder_sort_dir)
            )
        }
    }

    Box(Modifier.weight(1f, fill = false)) {
        AnimatedContent(
            targetState = path,
            transitionSpec = {
                // tiefer hinein: neue Ebene kommt von rechts, sonst von links
                val deeper = targetState.length > initialState.length && targetState.startsWith(initialState)
                val dir = if (deeper) 1 else -1
                (slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease)) { w -> dir * w / 5 } +
                    fadeIn(tween(Motion.MEDIUM - 60, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Ease)) { w -> -dir * w / 5 } +
                        fadeOut(tween(Motion.FAST))) using
                    SizeTransform(clip = true) { _, _ -> tween(Motion.MEDIUM, easing = Motion.Ease) }
            },
            label = "folderBrowserLevel"
        ) { p ->
            val kids = remember(tree, p, sort, ascending) {
                val base = tree.children(p)
                val sorted = when (sort) {
                    FolderSort.NAME -> base.sortedBy { it.name.lowercase() }
                    FolderSort.COUNT -> base.sortedBy { it.songs }
                    FolderSort.DATE -> base.sortedBy { it.newest }
                }
                if (ascending) sorted else sorted.reversed()
            }
            if (kids.isEmpty()) {
                Text(
                    str(R.string.folder_no_subfolders),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp + bottomExtra)) {
                    items(kids, key = { it.path }) { node ->
                        ListItem(
                            headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(plural(R.plurals.n_tracks, node.songs) + " | " + plural(R.plurals.n_folders, node.folders))
                            },
                            leadingContent = { FolderIcon() },
                            trailingContent = {
                                if (node.path in listed) {
                                    CheckIcon(tint = MaterialTheme.colorScheme.primary)
                                } else {
                                    BounceIconButton(onClick = { onPick(node.path) }) {
                                        PlusIcon(description = str(R.string.folder_add))
                                    }
                                }
                            },
                            colors = clear,
                            modifier = Modifier.bounceClick(pressedScale = 0.97f, haptic = false) { onPath(node.path) }
                        )
                    }
                }
            }
        }

        // Aktuellen Ordner übernehmen (auf der obersten Ebene gibt es nichts zu übernehmen)
        if (path.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 16.dp + bottomExtra)
                    .size(56.dp)
                    .bounceClick(pressedScale = 0.9f) { onPick(path) }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CheckIcon(description = str(R.string.folder_select_this))
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------------------------
 * Änderungen bestätigen
 * --------------------------------------------------------------------------------------------------------------- */

/** Eine anstehende Änderung der Ordner-Regeln samt Zahlen für die Bestätigung. */
class FolderChange(val rules: FolderRules, val added: Int, val removed: Int)

@Composable
private fun ChangeChip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** "Die folgenden Änderungen wurden erkannt. Aktualisierung bestätigen?" mit Zahlen der neuen und der entfallenden Titel. */
@Composable
fun FolderChangeDialog(
    change: FolderChange?,
    onConfirm: (FolderRules) -> Unit,
    onCancel: () -> Unit
) {
    val shown = rememberLastNonNull(change) ?: return
    BounceAlertDialog(
        visible = change != null,
        onDismissRequest = onCancel,
        title = { Text(str(R.string.changes_title)) },
        text = {
            Column {
                Text(str(R.string.changes_text))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChangeChip(
                        str(R.string.changes_new) + ": " + plural(R.plurals.n_tracks, shown.added),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    ChangeChip(
                        str(R.string.changes_filtered) + ": " + plural(R.plurals.n_tracks, shown.removed),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(shown.rules) }) { Text(str(R.string.changes_apply)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(str(R.string.cancel)) } }
    )
}
