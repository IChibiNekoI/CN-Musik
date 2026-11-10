package com.example.musikplayer

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Gespeicherte Kopie der letzten Titelliste. Beim Start steht sie sofort da (Startseite, Listen), während die Bibliothek
 * im Hintergrund neu aus der Android-Datenbank gelesen wird; ändert sich dabei nichts, ändert sich auch nichts auf dem Bildschirm.
 * Gespeichert wird die Liste so, wie sie angezeigt wird (mit den Tags aus den Dateien).
 */
object LibrarySnapshot {
    private const val FILE = "library_snapshot.bin"

    /** Bei jeder Änderung der Felder von [Song] erhöhen: Eine Kopie in einer anderen Form wird dann verworfen. */
    private const val VERSION = 1

    private var lastHash = 0

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Liest die Kopie; leer, wenn es keine gibt oder sie nicht lesbar ist. Schnell genug für den Start des Bildschirms. */
    fun read(ctx: Context): List<Song> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(f.inputStream(), 64 * 1024)).use { d ->
                if (d.readInt() != VERSION) return emptyList()
                val n = d.readInt()
                if (n < 0 || n > 500_000) return emptyList()
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val out = ArrayList<Song>(n)
                repeat(n) {
                    val id = d.readLong()
                    out.add(
                        Song(
                            id = id,
                            title = d.readUTF(),
                            artist = d.readUTF(),
                            uri = ContentUris.withAppendedId(collection, id),
                            folder = d.readUTF(),
                            album = d.readUTF(),
                            albumArtist = d.readUTF(),
                            dateAdded = d.readLong(),
                            track = d.readInt(),
                            fileName = d.readUTF(),
                            dateModified = d.readLong(),
                            year = d.readInt(),
                            durationMs = d.readLong(),
                            size = d.readLong(),
                            bitrate = d.readInt()
                        )
                    )
                }
                lastHash = out.hashCode()
                out
            }
        } catch (e: Exception) {
            emptyList()   // beschädigt oder eine ältere Form: dann gibt es einfach keine Kopie
        }
    }

    /** Speichert die Liste, aber nur, wenn sie sich seit der letzten Kopie geändert hat. Gehört auf einen Hintergrund-Thread. */
    fun write(ctx: Context, songs: List<Song>) {
        val hash = songs.hashCode()
        if (hash == lastHash && file(ctx).exists()) return
        val f = file(ctx)
        try {
            if (songs.isEmpty()) {
                f.delete()
                lastHash = hash
                return
            }
            val tmp = File(f.parentFile, "$FILE.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { d ->
                d.writeInt(VERSION)
                d.writeInt(songs.size)
                for (s in songs) {
                    d.writeLong(s.id)
                    d.writeUTF(cut(s.title))
                    d.writeUTF(cut(s.artist))
                    d.writeUTF(cut(s.folder))
                    d.writeUTF(cut(s.album))
                    d.writeUTF(cut(s.albumArtist))
                    d.writeLong(s.dateAdded)
                    d.writeInt(s.track)
                    d.writeUTF(cut(s.fileName))
                    d.writeLong(s.dateModified)
                    d.writeInt(s.year)
                    d.writeLong(s.durationMs)
                    d.writeLong(s.size)
                    d.writeInt(s.bitrate)
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            lastHash = hash
        } catch (e: Exception) {
            // keine Kopie: beim nächsten Start lädt die Bibliothek eben wie bisher
        }
    }

    /** writeUTF kennt höchstens 65535 Bytes; so lange Namen gibt es nicht, aber ein Fehler würde die ganze Kopie verhindern. */
    private fun cut(s: String): String = if (s.length > 4000) s.substring(0, 4000) else s
}
