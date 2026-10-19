package com.example.musikplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    // zählt hoch, sobald der Player geöffnet werden soll (Tipp auf die Benachrichtigung)
    private var openPlayerSignal by mutableIntStateOf(0)

    // Sprache: Deutsch oder Englisch (automatisch nach Gerät oder in den Einstellungen gewählt)
    override fun attachBaseContext(newBase: android.content.Context) {
        val wrapped = Lang.wrap(newBase)
        strCtx = wrapped
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenPlayer(intent)
        setContent { AppTheme { MusikApp(openPlayerSignal) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenPlayer(intent)
    }

    private fun handleOpenPlayer(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            openPlayerSignal++
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    CornerSettings.load(ctx)
    val cornerStyle = CornerSettings.style
    CompositionLocalProvider(
        LocalCornerStyle provides cornerStyle,
        LocalCoverStyle provides CornerSettings.coverStyle
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = if (cornerStyle == CornerStyle.UNIFORM) UniformShapes else Shapes(),
            content = content
        )
    }
}
