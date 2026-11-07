package com.example.musikplayer

import java.util.Locale

/*
 * Albumidentität: Ein Album ist nicht nur ein Name. Zwei Künstler können beide ein "Greatest Hits" haben, und
 * Compilations haben pro Titel andere Künstler. Deshalb gilt:
 *  - Mit Albuminterpret (Tag ALBUMARTIST): Albumname + Albuminterpret. Das fasst Compilations richtig zusammen.
 *  - Ohne Albuminterpret: Albumname + Ordner. Alben liegen in der Regel in einem Ordner; Unterordner wie "CD 2"
 *    zählen zum Ordner darüber. Liegt eine ganze Bibliothek flach in einem Ordner, gilt wie bisher nur der Name.
 *  - Ohne Albumname: alle Titel bilden gemeinsam "Unbekanntes Album".
 * Erscheinungsjahr und Disc-Nummer sind bewusst nicht Teil der Identität (uneinheitliche Tags würden ein Album
 * zerreißen). Die Disc steckt schon in der Tracknummer (Disc * 1000 + Titel) und bestimmt die Reihenfolge.
 */

private fun albumNorm(s: String): String = s.trim().lowercase(Locale.ROOT)

private val DISC_FOLDER = Regex("^(cd|disc|disk)[\\s._-]*\\d+.*$", RegexOption.IGNORE_CASE)

/** Ordner eines Albums; ein Disc-Unterordner ("CD 2", "Disc 1") zählt zum Ordner darüber. */
private fun albumFolderOf(folder: String): String {
    val parts = folder.trim('/').split('/').filter { it.isNotEmpty() }
    val album = if (parts.size > 1 && DISC_FOLDER.matches(parts.last().trim())) parts.dropLast(1) else parts
    return album.joinToString("/").lowercase(Locale.ROOT)
}

private fun cleanAlbumArtist(s: String): String =
    albumNorm(s).let { if (it == "<unknown>") "" else it }

/** Schlüssel der Albumidentität eines Titels (siehe oben). Leer = Titel ohne Album. */
fun albumKeyOf(s: Song): String {
    val name = albumNorm(s.album)
    if (name.isEmpty()) return ""
    val aa = cleanAlbumArtist(s.albumArtist)
    return if (aa.isNotEmpty()) "$name\u0000a\u0000$aa" else "$name\u0000f\u0000${albumFolderOf(s.folder)}"
}

/**
 * Interpret, der unter einem Album steht: der Albuminterpret, sonst der häufigste Künstler. Verteilen sich die
 * Titel auf viele Künstler (Compilation), steht "Verschiedene Interpreten" da. Ein einzelnes "feat." kippt das nicht.
 */
fun albumArtistOf(list: List<Song>): String {
    val tagged = list.firstOrNull { cleanAlbumArtist(it.albumArtist).isNotEmpty() }?.albumArtist?.trim()
    if (tagged != null) return tagged
    val counts = list.groupingBy { it.artist }.eachCount()
    val top = counts.maxByOrNull { it.value } ?: return ""
    return if (top.value * 2 >= list.size) top.key else str(R.string.various_artists)
}

/**
 * Gruppiert Titel zu Alben. Der erste Teil des Paares ist der Name des Albums und zugleich sein eindeutiger Schlüssel
 * in der Oberfläche: Gibt es zwei verschiedene Alben mit demselben Namen, hängt der Interpret in Klammern an
 * ("Greatest Hits (Queen)"). Sortiert nach Name.
 */
fun groupAlbums(songs: List<Song>): List<Pair<String, List<Song>>> {
    val unknown = str(R.string.unknown_album)
    val groups = songs.groupBy { albumKeyOf(it) }.values.toList()
    fun nameOf(g: List<Song>) = g.first().album.trim().ifBlank { unknown }
    val sameName = groups.groupBy { nameOf(it).lowercase(Locale.ROOT) }
    val used = HashSet<String>()
    val out = ArrayList<Pair<String, List<Song>>>(groups.size)
    for (g in groups) {
        val name = nameOf(g)
        val base = if ((sameName[name.lowercase(Locale.ROOT)]?.size ?: 1) > 1) {
            val who = albumArtistOf(g)
            if (who.isNotBlank()) "$name ($who)" else name
        } else name
        // Bleibt der Name trotzdem doppelt (gleicher Interpret, verschiedene Ordner), nummerieren
        var label = base
        var n = 2
        while (!used.add(label.lowercase(Locale.ROOT))) label = "$base ($n)".also { n++ }
        out += label to g
    }
    return out.sortedBy { it.first.lowercase(Locale.ROOT) }
}

/** Name (Schlüssel) des Albums, in dem [song] steht, oder null. */
fun albumLabelOf(song: Song, albums: List<Pair<String, List<Song>>>): String? =
    albums.firstOrNull { (_, list) -> list.any { it.id == song.id } }?.first
