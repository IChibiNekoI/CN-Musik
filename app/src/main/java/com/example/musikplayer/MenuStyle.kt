package com.example.musikplayer

import android.graphics.Bitmap
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize

/*
 * "Menü-Design einheitlich": Menüs, Dialoge und die Einstellungen bekommen denselben Hintergrund wie der Player,
 * wenn dort "Unschärfe" gewählt ist: das stark verschwommene Cover des laufenden Titels mit dunklem Schleier.
 * In allen anderen Fällen (Einfarbig, Farbverlauf, Schalter aus, kein Titel aktiv) bleibt alles wie Standard.
 */
object MenuBackdrop {
    /** Das verschwommene Cover des laufenden Titels (wird in MusikApp aktuell gehalten) */
    var bitmap by mutableStateOf<Bitmap?>(null)

    val active: Boolean
        get() = CornerSettings.playerBg == PlayerBackground.BLUR && CornerSettings.menuUnified && bitmap != null
}

private fun Modifier.drawBlurBackdrop(bitmap: Bitmap, veil: Color): Modifier = drawBehind {
    drawImage(
        bitmap.asImageBitmap(),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.High
    )
    // gleicher dunkler Schleier wie im Player: Text und Tasten bleiben gut lesbar
    drawRect(veil)
}

/** Für Dialoge: zeichnet das Menü-Design hinter den Dialog, sonst bleibt der Modifier unverändert. */
@Composable
fun dialogModifier(base: Modifier = Modifier): Modifier {
    val bmp = MenuBackdrop.bitmap
    if (!MenuBackdrop.active || bmp == null) return base
    return base
        .clip(MaterialTheme.shapes.extraLarge)
        .drawBlurBackdrop(bmp, MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
}

@Composable
fun dialogContainerColor(): Color =
    if (MenuBackdrop.active) Color.Transparent else AlertDialogDefaults.containerColor

/** Für das Einstellungs-Sheet von unten */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun sheetModifier(): Modifier {
    val bmp = MenuBackdrop.bitmap
    if (!MenuBackdrop.active || bmp == null) return Modifier
    return Modifier
        .clip(BottomSheetDefaults.ExpandedShape)
        .drawBlurBackdrop(bmp, MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun sheetContainerColor(): Color =
    if (MenuBackdrop.active) Color.Transparent else BottomSheetDefaults.ContainerColor

/** Aufklappmenü (drei Punkte). Gleiche Bedienung wie DropdownMenu, nur mit dem Menü-Design. */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val bmp = MenuBackdrop.bitmap
    if (!MenuBackdrop.active || bmp == null) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, content = content)
        return
    }
    val scheme = MaterialTheme.colorScheme
    // Die Fläche des Menüs wird durchsichtig, das verschwommene Cover scheint dahinter durch
    MaterialTheme(colorScheme = scheme.copy(surfaceContainer = Color.Transparent)) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = Modifier.drawBlurBackdrop(bmp, scheme.surface.copy(alpha = 0.5f)),
            content = content
        )
    }
}
