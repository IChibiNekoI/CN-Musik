package com.example.musikplayer

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/*
 * "Menü-Design einheitlich": Die Hauptseite (Titel, Album, Playlists, Favoriten) bekommt denselben Hintergrund
 * wie der Player, wenn dort "Unschärfe" gewählt ist: das stark verschwommene Cover des laufenden Titels
 * mit Schleier in der Hintergrundfarbe.
 * In allen anderen Fällen (Einfarbig, Farbverlauf, Schalter aus, kein Titel aktiv) bleibt alles wie Standard.
 */
object MenuBackdrop {
    /** Das verschwommene Cover des laufenden Titels (wird in MusikApp aktuell gehalten) */
    var bitmap by mutableStateOf<Bitmap?>(null)

    val active: Boolean
        get() = CornerSettings.playerBg == PlayerBackground.BLUR && CornerSettings.menuUnified && bitmap != null
}

/** Hintergrund der Hauptseite. Blendet weich ein, beim Titelwechsel weich über. */
@Composable
fun LibraryBackdrop() {
    val scheme = MaterialTheme.colorScheme
    val bmp = if (MenuBackdrop.active) MenuBackdrop.bitmap else null
    // Die normale Hintergrundfarbe bleibt darunter, damit beim Einblenden nichts aufblitzt
    Box(Modifier.fillMaxSize().background(scheme.background))
    Crossfade(
        targetState = bmp,
        modifier = Modifier.fillMaxSize(),
        animationSpec = tween(Motion.SLOW),
        label = "libraryBackdrop"
    ) { b ->
        if (b != null) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    b.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High
                )
                // gleicher Schleier wie im Player: Listen und Texte bleiben gut lesbar
                Box(Modifier.fillMaxSize().background(scheme.surface.copy(alpha = 0.5f)))
            }
        }
    }
}
