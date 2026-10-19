package com.example.musikplayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** Sprache der App: automatisch (Deutsch, wenn das Gerät auf Deutsch steht, sonst Englisch) oder fest gewählt. */
enum class AppLang(val key: String) { SYSTEM("system"), DE("de"), EN("en") }

object Lang {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context): AppLang {
        val k = prefs(ctx).getString("language", "system")
        return AppLang.values().firstOrNull { it.key == k } ?: AppLang.SYSTEM
    }

    fun save(ctx: Context, lang: AppLang) {
        prefs(ctx).edit().putString("language", lang.key).apply()
    }

    /** Sprachcode, der tatsächlich verwendet wird: "de" oder "en". */
    fun effectiveCode(ctx: Context): String = when (load(ctx)) {
        AppLang.DE -> "de"
        AppLang.EN -> "en"
        // Nur die erste Sprache des Geräts zählt: Deutsch -> Deutsch, alles andere -> Englisch
        AppLang.SYSTEM ->
            if (Resources.getSystem().configuration.locales[0].language == "de") "de" else "en"
    }

    /** Verpackt den Context so, dass alle Texte in der gewählten Sprache geladen werden. */
    fun wrap(base: Context): Context {
        val locale = Locale(effectiveCode(base))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/** Context mit der gewählten Sprache. Wird in MainActivity.attachBaseContext gesetzt (vor dem ersten Zeichnen). */
@Volatile
var strCtx: Context? = null

/** Text aus den Ressourcen in der gewählten Sprache, überall aufrufbar (auch in Klick-Lambdas). */
fun str(@StringRes id: Int, vararg args: Any): String {
    val c = strCtx ?: error("Sprache noch nicht initialisiert")
    return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
}

/** Text mit Einzahl/Mehrzahl, die Zahl wird automatisch als erstes Argument eingesetzt. */
fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String {
    val c = strCtx ?: error("Sprache noch nicht initialisiert")
    return c.resources.getQuantityString(id, n, n, *args)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
