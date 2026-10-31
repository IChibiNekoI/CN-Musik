package com.example.musikplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Widgets für den Startbildschirm, zwei Varianten:
 *  - Cover-Hintergrund: verschwommenes Cover als Hintergrund, Wellenform bzw. Balken, Zeiten, Tasten
 *  - Kompakt: kleines Cover neben Titel und Interpret, Tasten, Fortschrittsbalken, Zeiten
 *
 * Die Tasten und der Fortschrittsbalken werden mit derselben Geometrie wie im Player in kleine Bilder gezeichnet,
 * weil Widgets keine eigene Oberfläche zeichnen können. Der Player-Dienst hält die Widgets auf dem Laufenden.
 */

/** Zustand des Players für die Widgets. [songId] null = es läuft nichts. */
class WidgetSnapshot(
    val songId: Long?,
    val title: String,
    val artist: String,
    val uri: Uri?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val favorite: Boolean
) {
    val idle: Boolean get() = songId == null

    companion object {
        val IDLE = WidgetSnapshot(null, "", "", null, false, 0L, 0L, false, false)
    }
}

/** Gemeinsame Verweise zwischen Player-Dienst, Widgets und Tasten-Empfänger (alles im selben Prozess). */
object WidgetHub {
    @Volatile var player: Player? = null
    @Volatile var updater: WidgetUpdater? = null

    /** Zeichnet alle Widgets neu: mit dem Player, falls der Dienst läuft, sonst im Ruhezustand. */
    fun requestRefresh(ctx: Context) {
        val u = updater
        if (u != null) {
            u.refresh(true)
        } else {
            val app = ctx.applicationContext
            Thread {
                try { WidgetRenderer.update(app, WidgetSnapshot.IDLE, true) } catch (e: Exception) {}
            }.start()
        }
    }
}

abstract class BaseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle
    ) {
        refreshNow(context)
    }

    /** Läuft der Player-Dienst, aktualisiert er die Widgets. Sonst wird der Ruhezustand gezeichnet, und zwar so, dass
     *  das System den Prozess bis zum Ende am Leben hält (goAsync). */
    private fun refreshNow(context: Context) {
        val u = WidgetHub.updater
        if (u != null) {
            u.refresh(true)
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                WidgetRenderer.update(app, WidgetSnapshot.IDLE, true)
            } catch (e: Exception) {
                // Das Widget bleibt dann beim Startlayout
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class WideWidgetProvider : BaseWidgetProvider()
class CompactWidgetProvider : BaseWidgetProvider()

/** Nimmt die Tasten der Widgets entgegen und steuert den Player. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Läuft der Dienst nicht, zeigen die Widgets "App öffnen" statt dieser Tasten
        val p = WidgetHub.player ?: return
        when (intent.action) {
            ACTION_PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
            ACTION_NEXT -> skipNext(p)
            ACTION_PREV -> skipPrev(p)
            ACTION_SHUFFLE -> {
                val on = !p.shuffleModeEnabled
                p.shuffleModeEnabled = on
                // Auch speichern, falls die App-Oberfläche gerade nicht läuft (Mix und normales Profil getrennt)
                if (readMixActive(context)) saveMixShuffle(context, on) else saveShuffle(context, on)
            }
            ACTION_FAVORITE -> {
                val id = p.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                val favs = readFavorites(context)
                saveFavorites(context, if (id in favs) favs - id else listOf(id) + favs)
            }
        }
        WidgetHub.updater?.refresh(true)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.musikplayer.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.musikplayer.widget.NEXT"
        const val ACTION_PREV = "com.example.musikplayer.widget.PREV"
        const val ACTION_SHUFFLE = "com.example.musikplayer.widget.SHUFFLE"
        const val ACTION_FAVORITE = "com.example.musikplayer.widget.FAVORITE"
    }
}

/**
 * Lebt im Player-Dienst: Beobachtet den Player und hält die Widgets aktuell. Der Fortschritt läuft sekündlich weiter,
 * solange Musik spielt und der Bildschirm an ist. Gezeichnet wird in einem Hintergrund-Thread.
 */
class WidgetUpdater(private val ctx: Context, private val player: Player) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scheduled = AtomicBoolean(false)
    private val pendingFull = AtomicBoolean(false)
    @Volatile private var latest: WidgetSnapshot? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!player.isPlaying) return
            if (power.isInteractive) refresh(false)
            main.postDelayed(this, 1000L)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) {
                refresh(true)
                main.removeCallbacks(tick)
                if (player.isPlaying) main.postDelayed(tick, 1000L)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refresh(true)
            main.removeCallbacks(tick)
            if (player.isPlaying) main.postDelayed(tick, 1000L)
        }
    }

    // Änderungen in der App, die die Widgets zeigen: Favoriten, Fortschrittsstil, Sprache
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "favorites" || key == "wave_progress" || key == "language") main.post { refresh(true) }
    }

    init {
        player.addListener(listener)
        ctx.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        appPrefs(ctx).registerOnSharedPreferenceChangeListener(prefsListener)
        refresh(true)
        if (player.isPlaying) main.postDelayed(tick, 1000L)
    }

    fun release() {
        player.removeListener(listener)
        main.removeCallbacks(tick)
        try { ctx.unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        appPrefs(ctx).unregisterOnSharedPreferenceChangeListener(prefsListener)
        worker.shutdown()
        // Dienst beendet: Widgets zeigen wieder den Ruhezustand
        val app = ctx.applicationContext
        Thread {
            try { WidgetRenderer.update(app, WidgetSnapshot.IDLE, true) } catch (e: Exception) {}
        }.start()
    }

    private fun snapshot(): WidgetSnapshot {
        val item = player.currentMediaItem
        val id = item?.mediaId?.toLongOrNull() ?: return WidgetSnapshot.IDLE
        val dur = player.duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it }
        return WidgetSnapshot(
            songId = id,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            uri = item.localConfiguration?.uri,
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = dur,
            shuffle = player.shuffleModeEnabled,
            favorite = id in readFavorites(ctx)
        )
    }

    /** [full] = alles neu zeichnen (Titel-, Zustandswechsel); sonst nur Fortschritt und Zeiten. */
    fun refresh(full: Boolean) {
        if (!WidgetRenderer.hasWidgets(ctx)) return
        latest = snapshot()
        if (full) pendingFull.set(true)
        if (scheduled.compareAndSet(false, true)) {
            try {
                worker.execute {
                    scheduled.set(false)
                    val s = latest ?: return@execute
                    val f = pendingFull.getAndSet(false)
                    try { WidgetRenderer.update(ctx, s, f) } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                scheduled.set(false)
            }
        }
    }
}

/** Zeichnet die Widgets: Tasten, Fortschritt, Hintergründe, und baut die RemoteViews. */
object WidgetRenderer {
    private class Assets(
        val songId: Long, val version: Int,
        val cover: Bitmap?, val art: Bitmap?, val palette: WidgetPalette
    )

    @Volatile private var assets: Assets? = null

    private val providers = listOf(WideWidgetProvider::class.java, CompactWidgetProvider::class.java)

    fun hasWidgets(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.any { mgr.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }
    }

    /** Cover (klein für die Kachel, groß als Hintergrund) und Farben für den Titel; bleibt für den zuletzt gezeigten Titel im Speicher. */
    private fun assetsFor(ctx: Context, snap: WidgetSnapshot): Assets {
        val id = snap.songId
        val uri = snap.uri
        if (id == null || uri == null) return Assets(-1L, 0, null, null, widgetPaletteFor(null))
        val ver = TagEvents.coverVersions[id] ?: 0
        assets?.let { if (it.songId == id && it.version == ver) return it }
        // Nur Kennung und Adresse werden gebraucht, um das Cover zu laden
        val song = Song(id = id, title = snap.title, artist = snap.artist, uri = uri, folder = "")
        val cover = runBlocking { loadCoverBitmap(ctx, song, ver, 256) }
        val small = runBlocking { loadCoverBitmap(ctx, song, ver, 96) }
        val art = runBlocking { loadCoverBitmap(ctx, song, ver, 512) }
        val a = Assets(id, ver, cover, art, widgetPaletteFor(small ?: cover))
        assets = a
        return a
    }

    fun update(ctx: Context, snap: WidgetSnapshot, full: Boolean) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val wave = readWaveProgress(ctx)
        val a = assetsFor(ctx, snap)
        // Ohne vorherigen vollen Aufbau gibt es nichts, wozu ein Teil-Update passen könnte
        val doFull = full || assets == null && !snap.idle
        for (cls in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            val isWide = cls == WideWidgetProvider::class.java
            for (id in ids) {
                val size = sizeFor(ctx, mgr, id, isWide)
                val rv = if (doFull) buildFull(ctx, snap, a, isWide, size, wave) else buildPartial(ctx, snap, a, isWide, size, wave)
                if (doFull) mgr.updateAppWidget(id, rv) else mgr.partiallyUpdateAppWidget(id, rv)
            }
        }
    }

    /* ---------- Größen ---------- */

    private class WSize(val widthDp: Int, val heightDp: Int)

    private fun sizeFor(ctx: Context, mgr: AppWidgetManager, id: Int, wide: Boolean): WSize {
        val o = mgr.getAppWidgetOptions(id)
        val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val h = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return WSize(if (w > 0) w else 320, if (h > 0) h else if (wide) 160 else 150)
    }

    private const val PAD_DP = 10

    /* ---------- RemoteViews ---------- */

    private fun layoutFor(wide: Boolean) = if (wide) R.layout.widget_wide else R.layout.widget_compact

    private fun fmt(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        }
        return PendingIntent.getActivity(ctx, 700, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, action: String, code: Int): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildFull(ctx: Context, snap: WidgetSnapshot, a: Assets, wide: Boolean, size: WSize, wave: Boolean): RemoteViews {
        val d = ctx.resources.displayMetrics.density
        val rv = RemoteViews(ctx.packageName, layoutFor(wide))
        val p = a.palette
        val sc = Lang.wrap(ctx)

        // Texte
        val title = if (snap.idle) sc.getString(R.string.widget_idle_title) else snap.title
        val artist = if (snap.idle) sc.getString(R.string.widget_idle_hint) else snap.artist
        rv.setTextViewText(R.id.title, title)
        rv.setTextViewText(R.id.artist, artist)
        rv.setTextColor(R.id.title, p.onSurface)
        rv.setTextColor(R.id.artist, p.onSurfaceVariant)
        rv.setTextColor(R.id.time_cur, p.onSurfaceVariant)
        rv.setTextColor(R.id.time_total, p.onSurfaceVariant)

        // Hintergrund
        if (wide) {
            // Das Cover füllt das ganze Widget, der Verlauf darüber (widget_scrim) hält Schrift und Tasten lesbar.
            // Ohne Cover steht die Kartenfarbe dahinter.
            rv.setInt(R.id.bg, "setBackgroundColor", p.card)
            rv.setImageViewBitmap(R.id.bg, a.art)
        } else {
            rv.setInt(R.id.card, "setBackgroundColor", p.card)
            rv.setImageViewBitmap(R.id.cover, a.cover)
        }

        // Fortschritt und Zeiten
        applyProgress(ctx, rv, snap, a, wide, size, wave)

        // Tasten
        val btn = (44 * d).roundToInt()
        rv.setImageViewBitmap(R.id.btn_shuffle, shuffleIcon(btn, if (snap.shuffle) p.primary else p.onSurfaceVariant))
        rv.setImageViewBitmap(R.id.btn_prev, skipIcon(btn, false, p.onSurface))
        rv.setImageViewBitmap(R.id.btn_play, playIcon(btn, snap.playing, p.primary, p.onSurface))
        rv.setImageViewBitmap(R.id.btn_next, skipIcon(btn, true, p.onSurface))
        rv.setImageViewBitmap(R.id.btn_fav, heartIcon(btn, snap.favorite, p.primary))
        rv.setContentDescription(R.id.btn_shuffle, sc.getString(R.string.cd_shuffle))
        rv.setContentDescription(R.id.btn_prev, sc.getString(R.string.back))
        rv.setContentDescription(R.id.btn_play, sc.getString(if (snap.playing) R.string.cd_pause else R.string.cd_play))
        rv.setContentDescription(R.id.btn_next, sc.getString(R.string.cd_next))
        rv.setContentDescription(R.id.btn_fav, sc.getString(if (snap.favorite) R.string.fav_remove else R.string.fav_add))

        // Klicks: Fläche öffnet den Player; die Tasten steuern, im Ruhezustand öffnen sie die App
        val open = openApp(ctx)
        rv.setOnClickPendingIntent(R.id.root, open)
        if (snap.idle) {
            for (b in intArrayOf(R.id.btn_shuffle, R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.btn_fav)) {
                rv.setOnClickPendingIntent(b, open)
            }
        } else {
            rv.setOnClickPendingIntent(R.id.btn_shuffle, action(ctx, WidgetActionReceiver.ACTION_SHUFFLE, 1))
            rv.setOnClickPendingIntent(R.id.btn_prev, action(ctx, WidgetActionReceiver.ACTION_PREV, 2))
            rv.setOnClickPendingIntent(R.id.btn_play, action(ctx, WidgetActionReceiver.ACTION_PLAY_PAUSE, 3))
            rv.setOnClickPendingIntent(R.id.btn_next, action(ctx, WidgetActionReceiver.ACTION_NEXT, 4))
            rv.setOnClickPendingIntent(R.id.btn_fav, action(ctx, WidgetActionReceiver.ACTION_FAVORITE, 5))
        }
        return rv
    }

    private fun buildPartial(ctx: Context, snap: WidgetSnapshot, a: Assets, wide: Boolean, size: WSize, wave: Boolean): RemoteViews {
        val rv = RemoteViews(ctx.packageName, layoutFor(wide))
        applyProgress(ctx, rv, snap, a, wide, size, wave)
        return rv
    }

    private fun applyProgress(ctx: Context, rv: RemoteViews, snap: WidgetSnapshot, a: Assets, wide: Boolean, size: WSize, wave: Boolean) {
        val d = ctx.resources.displayMetrics.density
        val wPx = ((size.widthDp - 2 * PAD_DP) * d).roundToInt().coerceAtLeast(64)
        val hPx = ((if (wide && wave) 24 else 20) * d).roundToInt()
        val frac = if (snap.durationMs > 0L) (snap.positionMs.toFloat() / snap.durationMs).coerceIn(0f, 1f) else 0f
        rv.setImageViewBitmap(
            R.id.progress,
            progressBitmap(wPx, hPx, frac, wave, snap.songId ?: 0L, a.palette.onSurface, d)
        )
        rv.setTextViewText(R.id.time_cur, fmt(snap.positionMs))
        rv.setTextViewText(R.id.time_total, fmt(snap.durationMs))
    }

    /* ---------- Zeichnen: Fortschritt ---------- */

    /** Kleine, feste Wellenform pro Titel (wie im Player). */
    private fun waveHeights(seed: Long, count: Int): FloatArray {
        val r = java.util.Random(seed * 1103515245L + 12345L)
        val p1 = r.nextFloat() * 6.2832f
        val p2 = r.nextFloat() * 6.2832f
        val p3 = r.nextFloat() * 6.2832f
        val f1 = 0.10f + r.nextFloat() * 0.06f
        val f2 = 0.22f + r.nextFloat() * 0.08f
        val f3 = 0.45f + r.nextFloat() * 0.15f
        return FloatArray(count) { i ->
            val s = kotlin.math.sin(i * f1 + p1) * 0.5f + kotlin.math.sin(i * f2 + p2) * 0.3f + kotlin.math.sin(i * f3 + p3) * 0.2f
            val jitter = (r.nextFloat() - 0.5f) * 0.18f
            var v = 0.58f + 0.34f * s + jitter
            val edge = minOf(i, count - 1 - i)
            if (edge < 2) v *= 0.55f + 0.225f * edge
            v.coerceIn(0.18f, 1f)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255f).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun progressBitmap(w: Int, h: Int, frac: Float, wave: Boolean, seed: Long, base: Int, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(base, 0.22f) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(base, 0.92f) }
        if (wave) {
            // Wellenform aus einzelnen Balken: hell bis zur Position, danach gedämpft
            val pitch = 6f * d
            val count = max(8, (w / pitch).toInt())
            val step = w.toFloat() / count
            val bw = step * 0.55f
            val heights = waveHeights(seed, count)
            val x = w * frac
            for (i in 0 until count) {
                val cx = step * (i + 0.5f)
                val bh = max(bw, h * heights[i])
                val rect = RectF(cx - bw / 2f, (h - bh) / 2f, cx + bw / 2f, (h + bh) / 2f)
                c.drawRoundRect(rect, bw / 2f, bw / 2f, if (cx <= x) fill else track)
            }
        } else {
            // Runder Balken: Füllung beginnt mit einer kleinen Kugel und wächst nach rechts
            val barH = 8f * d
            val top = (h - barH) / 2f
            val full = RectF(0f, top, w.toFloat(), top + barH)
            c.drawRoundRect(full, barH / 2f, barH / 2f, track)
            val fw = max(barH, w * frac)
            c.drawRoundRect(RectF(0f, top, fw, top + barH), barH / 2f, barH / 2f, fill)
        }
        return bmp
    }

    /* ---------- Zeichnen: Tasten (gleiche Geometrie wie PlayerIcons.kt) ---------- */

    private fun strokePaint(color: Int, width: Float, corner: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(corner)
    }

    private fun roundedTriangle(c: Canvas, left: Float, tip: Float, top: Float, bottom: Float, paint: Paint) {
        val p = Path().apply {
            moveTo(left, top)
            lineTo(tip, (top + bottom) / 2f)
            lineTo(left, bottom)
            close()
        }
        c.drawPath(p, paint)
    }

    private fun chevron(c: Canvas, tipX: Float, tipY: Float, dir: Float, size: Float, paint: Paint) {
        val p = Path().apply {
            moveTo(tipX - dir * size, tipY - size)
            lineTo(tipX, tipY)
            lineTo(tipX - dir * size, tipY + size)
        }
        c.drawPath(p, paint)
    }

    private fun skipIcon(box: Int, forward: Boolean, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = box * 0.625f   // 30 von 48
        c.translate((box - s) / 2f, (box - s) / 2f)
        if (!forward) c.scale(-1f, 1f, s / 2f, s / 2f)
        val tri = strokePaint(color, s * 0.085f, s * 0.12f)
        roundedTriangle(c, s * 0.14f, s * 0.70f, s * 0.20f, s * 0.80f, tri)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.085f; strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(s * 0.90f, s * 0.20f, s * 0.90f, s * 0.80f, line)
        return bmp
    }

    private fun playIcon(box: Int, playing: Boolean, circle: Int, icon: Int): Bitmap {
        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dia = box * 0.92f
        val off = (box - dia) / 2f
        c.drawCircle(box / 2f, box / 2f, dia / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(circle, 0.45f) })
        val s = dia * 0.5f
        c.translate(off + (dia - s) / 2f, off + (dia - s) / 2f)
        if (playing) {
            val barW = s * 0.26f
            val gap = s * 0.08f
            val barH = s * 0.74f
            val left = (s - (2 * barW + gap)) / 2f
            val top = (s - barH) / 2f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = icon }
            c.drawRoundRect(RectF(left, top, left + barW, top + barH), barW / 2f, barW / 2f, fill)
            c.drawRoundRect(RectF(left + barW + gap, top, left + 2 * barW + gap, top + barH), barW / 2f, barW / 2f, fill)
        } else {
            roundedTriangle(c, s * 0.24f, s * 0.90f, s * 0.13f, s * 0.87f, strokePaint(icon, s * 0.09f, s * 0.14f))
        }
        return bmp
    }

    private fun shuffleIcon(box: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = box * (26f / 48f)
        c.translate((box - s) / 2f, (box - s) / 2f)
        val w = s * 0.085f
        val line = strokePaint(color, w, s * 0.10f)
        val head = strokePaint(color, w, s * 0.03f)
        val a = Path().apply {
            moveTo(s * 0.10f, s * 0.30f); lineTo(s * 0.30f, s * 0.30f)
            lineTo(s * 0.64f, s * 0.70f); lineTo(s * 0.86f, s * 0.70f)
        }
        val b = Path().apply {
            moveTo(s * 0.10f, s * 0.70f); lineTo(s * 0.30f, s * 0.70f)
            lineTo(s * 0.64f, s * 0.30f); lineTo(s * 0.86f, s * 0.30f)
        }
        c.drawPath(a, line)
        c.drawPath(b, line)
        chevron(c, s * 0.88f, s * 0.70f, 1f, s * 0.11f, head)
        chevron(c, s * 0.88f, s * 0.30f, 1f, s * 0.11f, head)
        return bmp
    }

    private fun heartIcon(box: Int, filled: Boolean, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = box * (28f / 48f)
        c.translate((box - s) / 2f, (box - s) / 2f)
        val k = s / 24f
        val heart = Path().apply {
            moveTo(12f * k, 20.2f * k)
            cubicTo(4.8f * k, 15.4f * k, 2.4f * k, 11f * k, 2.4f * k, 8.2f * k)
            cubicTo(2.4f * k, 4.8f * k, 5.3f * k, 3.4f * k, 7.7f * k, 3.4f * k)
            cubicTo(10.1f * k, 3.4f * k, 11.5f * k, 4.8f * k, 12f * k, 6.7f * k)
            cubicTo(12.5f * k, 4.8f * k, 13.9f * k, 3.4f * k, 16.3f * k, 3.4f * k)
            cubicTo(18.7f * k, 3.4f * k, 21.6f * k, 4.8f * k, 21.6f * k, 8.2f * k)
            cubicTo(21.6f * k, 11f * k, 19.2f * k, 15.4f * k, 12f * k, 20.2f * k)
            close()
        }
        if (filled) c.drawPath(heart, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        c.drawPath(heart, strokePaint(color, 1.8f * k, 1.6f * k))
        return bmp
    }
}
