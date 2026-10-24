package com.example.musikplayer

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
        val folder: String,
    val album: String = "",
    val dateAdded: Long = 0L,
    val track: Int = 0,
    val fileName: String = "",
    val dateModified: Long = 0L,   // Sekunden, zuletzt geändert
    val year: Int = 0,             // Erscheinungsjahr laut Tag, 0 = unbekannt
    val durationMs: Long = 0L      // Laufzeit in Millisekunden, 0 = unbekannt
)
