package com.example.musikplayer

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/* ---------- Update-Prüfung über GitHub-Releases ---------- */

const val UPDATE_REPO = "IChibiNekoI/CN-Musik"

/** Neuere Version auf GitHub: Versionsnummer, Download-Link (APK, sonst Release-Seite). */
class UpdateInfo(val version: String, val url: String)

private fun versionParts(v: String): List<Int> =
    v.trim().removePrefix("v").removePrefix("V")
        .split(Regex("[^0-9]+"))
        .filter { it.isNotEmpty() }
        .map { it.toIntOrNull() ?: 0 }

private fun isNewerVersion(latest: String, current: String): Boolean {
    val a = versionParts(latest)
    val b = versionParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Über F-Droid installiert? Dann aktualisiert F-Droid die App, sie sucht selbst nicht nach Updates. */
private val FDROID_INSTALLERS = setOf(
    "org.fdroid.fdroid", "org.fdroid.basic", "com.machiav3lli.fdroid", "eu.bubu1.fdroidclassic"
)

private fun isInstalledFromFDroid(ctx: Context): Boolean = try {
    val installer = ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
    installer != null && installer in FDROID_INSTALLERS
} catch (e: Exception) {
    false
}

/** Fragt das neueste GitHub-Release ab. Gibt null zurück, wenn kein Update da ist oder die Abfrage scheitert. */
fun checkForUpdate(ctx: Context): UpdateInfo? {
    if (isInstalledFromFDroid(ctx)) return null
    return try {
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        if (current == null) return null
        val conn = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CN-Musik")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            if (tag.isEmpty() || !isNewerVersion(tag, current)) return null
            var url = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        url = a.optString("browser_download_url", url)
                        break
                    }
                }
            }
            if (url.isEmpty()) return null
            UpdateInfo(tag.removePrefix("v").removePrefix("V"), url)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
