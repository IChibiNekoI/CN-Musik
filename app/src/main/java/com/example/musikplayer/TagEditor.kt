package com.example.musikplayer

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.taglib.TagLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wird erhöht, wenn Tags gespeichert wurden. Die Bibliothek lädt dann neu. */
object TagEvents {
    var tick by mutableIntStateOf(0)
}

/** Schlüssel in TagLib (Reihenfolge = Reihenfolge im Dialog) */
private val TAG_FIELDS = listOf(
    "TITLE" to "Titel",
    "ARTIST" to "Künstler",
    "ALBUM" to "Album",
    "ALBUMARTIST" to "Albumkünstler"
)

/** Liest die aktuellen Tags direkt aus der Datei. null = nicht lesbar. */
private fun readTagValues(ctx: Context, song: Song): Map<String, String>? {
    return try {
        ctx.contentResolver.openFileDescriptor(song.uri, "r")?.use { pfd ->
            val md = TagLib.getMetadata(pfd.dup().detachFd()) ?: return@use null
            TAG_FIELDS.associate { (key, _) ->
                key to (md.propertyMap[key]?.joinToString("; ") ?: "")
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
    edited: Map<String, String>
): WriteOutcome {
    return try {
        val ok = ctx.contentResolver.openFileDescriptor(song.uri, "rw")?.use { pfd ->
            val md = TagLib.getMetadata(pfd.dup().detachFd()) ?: return@use false
            val pm = md.propertyMap
            for ((key, _) in TAG_FIELDS) {
                val new = edited[key]?.trim() ?: continue
                if (new == (original[key] ?: "")) continue
                if (new.isEmpty()) pm.remove(key) else pm[key] = arrayOf(new)
            }
            TagLib.savePropertyMap(pfd.dup().detachFd(), pm)
        } ?: false
        if (ok) rescanFile(ctx, song)
        WriteOutcome(ok)
    } catch (e: SecurityException) {
        WriteOutcome(false, e)
    } catch (e: Exception) {
        WriteOutcome(false)
    }
}

/** Sagt Android Bescheid, dass sich die Datei geändert hat, und löst danach ein Neuladen aus. */
private fun rescanFile(ctx: Context, song: Song) {
    try {
        val path = ctx.contentResolver.query(
            song.uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (path != null) {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, _ -> TagEvents.tick++ }
        }
    } catch (e: Exception) {
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
            val out = withContext(Dispatchers.IO) { writeTagValues(ctx, song, orig, fields.toMap()) }
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
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Tags bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    failedToLoad -> Text("Die Tags dieser Datei konnten nicht gelesen werden.")
                    original == null -> Text("Lade …")
                    else -> TAG_FIELDS.forEachIndexed { i, (key, label) ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fields[key] ?: "",
                            onValueChange = { fields[key] = it },
                            label = { Text(label) },
                            singleLine = true,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth()
                        )
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
