package com.example.musikplayer

import android.content.Context
import android.net.Uri
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import android.provider.OpenableColumns
import java.util.Locale

/** Ergebnis eines M3U-Imports: Name der Playlist, gefundene Song-IDs, Anzahl der Einträge in der Datei. */
class M3uResult(val name: String, val ids: List<Long>, val total: Int)

private fun normKey(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim()

/** Liest eine M3U/M3U8-Datei und ordnet die Einträge Songs der Bibliothek zu. */
fun importM3u(ctx: Context, uri: Uri, songs: List<Song>): M3uResult? {
    val rawBytes: ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
    if (rawBytes == null) return null
    val bytes: ByteArray = rawBytes

    val decoded: String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }
    val text = decoded.removePrefix("\uFEFF")

    var displayName: String? = null
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) displayName = c.getString(0)
    }
    val name = displayName?.substringBeforeLast('.')?.trim().orEmpty().ifEmpty { str(R.string.imported_playlist) }

    val byFile = songs.filter { it.fileName.isNotEmpty() }.groupBy { normKey(it.fileName) }
    val byTitle = songs.groupBy { normKey(it.title) }

    val ids = mutableListOf<Long>()
    var total = 0
    var info: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            info = line.substringAfter(',', "").trim().ifEmpty { null }
            continue
        }
        if (line.startsWith("#")) continue
        total++

        var path = line.removePrefix("file://")
        if (path.contains('%')) {
            path = try { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { path }
        }
        path = path.replace('\\', '/')
        val fileName = path.substringAfterLast('/')
        val dir = normKey(path.substringBeforeLast('/', "").trim('/'))

        // 1) Dateiname (bei mehreren Treffern passt der Ordner)
        var match: Song? = null
        val candidates = byFile[normKey(fileName)]
        if (candidates != null) {
            match = if (candidates.size == 1) candidates[0]
            else candidates.firstOrNull {
                val f = normKey(it.folder.trim('/'))
                f.isNotEmpty() && dir.endsWith(f)
            } ?: candidates[0]
        }
        // 2) Dateiname ohne Endung = Titel
        if (match == null) {
            val base = normKey(fileName.substringBeforeLast('.'))
            byTitle[base]?.let { if (it.size == 1) match = it[0] }
        }
        // 3) #EXTINF "Künstler - Titel"
        if (match == null && info != null) {
            val i = info!!
            val artistPart = normKey(i.substringBefore(" - ", ""))
            val titlePart = normKey(i.substringAfter(" - ", i))
            val list = byTitle[titlePart] ?: byTitle[normKey(i)]
            if (list != null) {
                match = list.firstOrNull {
                    artistPart.isNotEmpty() && normKey(it.artist).contains(artistPart)
                } ?: list.firstOrNull()
            }
        }
        match?.let { if (it.id !in ids) ids.add(it.id) }
        info = null
    }
    return M3uResult(name, ids, total)
}
