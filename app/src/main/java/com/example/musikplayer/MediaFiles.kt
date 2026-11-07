package com.example.musikplayer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import java.io.File

/**
 * Zugriff auf Audiodateien über Android-Mittel statt über feste Dateipfade (Scoped Storage).
 *
 * Der Pfad aus MediaStore.Audio.Media.DATA ist seit Android 10 nur noch ein Hinweis und kann fehlen oder nicht lesbar sein
 * (z. B. auf SD-Karten oder anderen Speichern). Die App nutzt ihn deshalb nur noch als schnellen Weg und fällt sonst auf
 * ContentResolver und Uri zurück. Zum Schreiben (Tags) und Lesen von Inhalten wird ohnehin nur die Uri verwendet.
 */

/** Größe und Änderungszeit einer Audiodatei. */
class FileInfo(val size: Long, val modifiedMs: Long)

/**
 * Liest Größe und Änderungszeit der Datei, wie sie gerade auf dem Speicher liegt (nicht, was MediaStore noch von früher
 * weiß). Schneller Weg: der Pfad aus [pathHint], falls lesbar. Sonst wird die Datei über die [uri] geöffnet und
 * direkt am Dateideskriptor abgefragt. null = nicht lesbar.
 */
fun statAudio(ctx: Context, uri: Uri, pathHint: String?): FileInfo? {
    if (!pathHint.isNullOrEmpty()) {
        val f = File(pathHint)
        val m = f.lastModified()
        if (m > 0L) return FileInfo(f.length(), m)
    }
    return try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val st = Os.fstat(pfd.fileDescriptor)
            if (st.st_mtime > 0L) FileInfo(st.st_size, st.st_mtime * 1000L) else null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Dateipfad zu einer Uri, nur als Hinweis für den Media-Scanner (der noch Pfade verlangt). null = Android nennt keinen.
 * Der einzige Ort neben der Bibliotheksabfrage, an dem die App den Pfad-Eintrag von MediaStore liest.
 */
fun audioPathOf(ctx: Context, uri: Uri): String? = try {
    @Suppress("DEPRECATION")
    ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}
