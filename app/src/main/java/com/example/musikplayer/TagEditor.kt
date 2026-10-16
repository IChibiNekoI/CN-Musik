package com.example.musikplayer

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wird erhöht, wenn Tags gespeichert wurden. Die Bibliothek lädt dann neu. */
object TagEvents {
    var tick by mutableIntStateOf(0)

    /** Pro Song hochgezählt, wenn das Cover geändert wurde. Cover und Farbthema laden dann neu. */
    val coverVersions = mutableStateMapOf<Long, Int>()

    fun bumpCover(id: Long) {
        coverVersions[id] = (coverVersions[id] ?: 0) + 1
    }
}

/** Schlüssel in TagLib (Reihenfolge = Reihenfolge im Dialog) */
private val TAG_FIELDS = listOf(
    "TITLE" to "Titel",
    "ARTIST" to "Künstler",
    "ALBUM" to "Album",
    "ALBUMARTIST" to "Albumkünstler"
)

/** Alle Schlüssel, die gelesen und geschrieben werden (Track und Jahr stehen oben neben dem Cover) */
private val TAG_KEYS = TAG_FIELDS.map { it.first } + listOf("TRACKNUMBER", "DATE")

/** Liest die aktuellen Tags direkt aus der Datei. null = nicht lesbar. */
private fun readTagValues(ctx: Context, song: Song): Map<String, String>? {
    return try {
        ctx.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
            val md = TagLib.getMetadata(pfd.dup().detachFd()) ?: return@use null
            TAG_KEYS.associateWith { key ->
                md.propertyMap[key]?.joinToString("; ") ?: ""
            }
        }
    } catch (e: Exception) {
        null
    }
}

private class WriteOutcome(val ok: Boolean, val security: SecurityException? = null)

/** Schreibt nur die Felder, die geändert wurden. Mehrfachwerte anderer Felder bleiben unberührt. */
private fun writeTagValues(
    ctx: Context,
    song: Song,
    original: Map<String, String>,
    edited: Map<String, String>,
    cover: ByteArray?
): WriteOutcome {
    return try {
        val ok = ctx.contentResolver.openFileDescriptor(song.uri, "rw")?.use { pfd ->
            val md = TagLib.getMetadata(pfd.dup().detachFd()) ?: return@use false
            val pm = md.propertyMap
            var propsChanged = false
            for (key in TAG_KEYS) {
                val new = edited[key]?.trim() ?: continue
                if (new == (original[key] ?: "")) continue
                if (new.isEmpty()) pm.remove(key) else pm[key] = arrayOf(new)
                propsChanged = true
            }
            var good = true
            if (propsChanged) good = TagLib.savePropertyMap(pfd.dup().detachFd(), pm)
            if (good && cover != null) {
                good = TagLib.savePictures(
                    pfd.dup().detachFd(),
                    arrayOf(Picture(cover, "", "Front Cover", "image/jpeg"))
                )
            }
            good
        } ?: false
        if (ok) rescanFile(ctx, song, coverChanged = cover != null)
        WriteOutcome(ok)
    } catch (e: SecurityException) {
        WriteOutcome(false, e)
    } catch (e: Exception) {
        WriteOutcome(false)
    }
}

/** Sagt Android Bescheid, dass sich die Datei geändert hat, und löst danach ein Neuladen aus. */
private fun rescanFile(ctx: Context, song: Song, coverChanged: Boolean) {
    try {
        val path = ctx.contentResolver.query(
            song.uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (path != null) {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, _ ->
                TagEvents.tick++
                if (coverChanged) TagEvents.bumpCover(song.id)   // erst nach dem Scan, sonst kommt das alte Bild
            }
        } else if (coverChanged) {
            TagEvents.bumpCover(song.id)
        }
    } catch (e: Exception) {
    }
}

/** Liest ein Bild, verkleinert es (max. 1200 px) und macht ein JPEG daraus. Gibt JPEG-Bytes und Vorschau zurück. */
private fun loadCoverImage(ctx: Context, uri: Uri): Pair<ByteArray, Bitmap>? {
    return try {
        val maxSide = 1200
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val scale = minOf(1f, maxSide / maxOf(raw.width, raw.height).toFloat())
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else raw
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        out.toByteArray() to bmp
    } catch (e: Exception) {
        null
    }
}

@Composable
fun TagEditDialog(song: Song, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Map<String, String>?>(null) }
    var failedToLoad by remember { mutableStateOf(false) }
    val fields = remember { mutableStateMapOf<String, String>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var permTick by remember { mutableIntStateOf(0) }
    var newCover by remember { mutableStateOf<ByteArray?>(null) }
    var newCoverPreview by remember { mutableStateOf<Bitmap?>(null) }

    // Bild für das neue Cover auswählen
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { loadCoverImage(ctx, uri) }
            if (r == null) {
                error = "Das Bild konnte nicht geladen werden."
            } else {
                error = null
                newCover = r.first
                newCoverPreview = r.second
            }
        }
    }

    LaunchedEffect(song.id) {
        val v = withContext(Dispatchers.IO) { readTagValues(ctx, song) }
        if (v == null) {
            failedToLoad = true
        } else {
            original = v
            fields.putAll(v)
        }
    }

    // Android verlangt für fremde Dateien eine Freigabe durch den Nutzer
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            permTick++
        } else {
            saving = false
            error = "Keine Schreibberechtigung erteilt."
        }
    }

    fun save(askIfNeeded: Boolean) {
        val orig = original ?: return
        saving = true
        error = null
        scope.launch {
            val out = withContext(Dispatchers.IO) { writeTagValues(ctx, song, orig, fields.toMap(), newCover) }
            val sec = out.security
            if (out.ok) {
                Toast.makeText(ctx, "Tags gespeichert", Toast.LENGTH_SHORT).show()
                TagEvents.tick++
                onDismiss()
            } else if (sec != null && askIfNeeded) {
                val sender = when {
                    Build.VERSION.SDK_INT >= 30 ->
                        MediaStore.createWriteRequest(ctx.contentResolver, listOf(song.uri)).intentSender
                    Build.VERSION.SDK_INT >= 29 && sec is RecoverableSecurityException ->
                        sec.userAction.actionIntent.intentSender
                    else -> null
                }
                if (sender != null) {
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    saving = false
                    error = "Keine Schreibberechtigung für diese Datei."
                }
            } else {
                saving = false
                error = "Speichern fehlgeschlagen. Das Dateiformat wird evtl. nicht unterstützt."
            }
        }
    }

    LaunchedEffect(permTick) {
        if (permTick > 0) save(false)
    }

    AlertDialog(
        // breiter als Standard, damit das Datum neben dem Cover nicht abgeschnitten wird
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Tags bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    failedToLoad -> Text("Die Tags dieser Datei konnten nicht gelesen werden.")
                    original == null -> Text("Lade …")
                    else -> {
                        val fieldShape = RoundedCornerShape(20.dp)
                        // Oben: Cover links, Track-Nummer und Jahr rechts daneben
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    // Das Label des ersten Feldes ragt 8 dp über dessen Rahmen: das Cover beginnt erst
                                    // am Rahmen und endet am unteren Rahmen von "Jahr" (56 + 8 + 8 + 56 = 128 dp)
                                    .padding(top = 8.dp)
                                    .size(128.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(enabled = !saving) { coverPicker.launch("image/*") }
                            ) {
                                val preview = newCoverPreview
                                if (preview != null) {
                                    Image(
                                        preview.asImageBitmap(), contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Cover(song, 128.dp, corner = 0.dp)
                                }
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(topStart = 16.dp))
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.Edit, contentDescription = "Cover ändern",
                                        tint = Color.White
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = fields["TRACKNUMBER"] ?: "",
                                    onValueChange = { fields["TRACKNUMBER"] = it },
                                    label = { Text("Track Nummer") },
                                    trailingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
                                    singleLine = true,
                                    enabled = !saving,
                                    shape = fieldShape,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = fields["DATE"] ?: "",
                                    onValueChange = { fields["DATE"] = it },
                                    label = { Text("Jahr") },
                                    trailingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                                    singleLine = true,
                                    enabled = !saving,
                                    shape = fieldShape,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        TAG_FIELDS.forEach { (key, label) ->
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = fields[key] ?: "",
                                onValueChange = { fields[key] = it },
                                label = { Text(label) },
                                singleLine = true,
                                enabled = !saving,
                                shape = fieldShape,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(true) }, enabled = original != null && !saving) {
                Text(if (saving) "Speichere …" else "Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Abbrechen") }
        }
    )
}
