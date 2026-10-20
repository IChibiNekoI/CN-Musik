package com.example.musikplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Merkt sich den letzten Wert, der nicht null war. So bleibt der Inhalt eines Dialogs sichtbar,
 * während er sich schließt (der Auslöser ist dann schon wieder null).
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null && value !== last) last = value
    return value ?: last
}

/**
 * Dialog im Stil der übrigen App: poppt mit kleinem Überschwinger auf (wie das Menü) und schließt sich schnell
 * ohne Nachschwingen. Der Hintergrund dunkelt weich mit. [visible] steuert Öffnen und Schließen; der Dialog bleibt
 * bis zum Ende der Schließ-Animation bestehen, deshalb muss der Inhalt auch dann noch verfügbar sein
 * (siehe [rememberLastNonNull]).
 */
@Composable
fun BounceAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            // leicht unterdämpft: kleiner, weicher Überschwinger beim Aufpoppen
            progress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
        } else {
            // zu: schnell und ohne Nachschwingen
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f))
        }
    }
    if (!visible && progress.value <= 0.001f) return
    val noRipple = remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = visible,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Eigene Abdunkelung und Animation statt der des Systems (sonst blendet das Fenster doppelt aus)
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(-1)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * progress.value.coerceIn(0f, 1f)))
                .clickable(interactionSource = noRipple, indication = null, enabled = visible) { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(min = 280.dp, max = 560.dp)
                    .graphicsLayer {
                        val p = progress.value
                        val s = 0.8f + 0.2f * p
                        scaleX = s
                        scaleY = s
                        alpha = (p * 2.2f).coerceIn(0f, 1f)
                    }
                    // fängt Tipps im Dialog selbst ab, damit nur Tipps daneben ihn schließen
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(Modifier.padding(24.dp)) {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                    }
                    if (text != null) {
                        Spacer(Modifier.height(16.dp))
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
