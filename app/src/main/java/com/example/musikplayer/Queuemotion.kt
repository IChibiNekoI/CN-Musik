package com.example.musikplayer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp

/**
 * Ruhelage der Player-Teile, die beim Öffnen der Warteschlange mitwandern (nur Hochformat).
 * Werte werden beim Layouten gesetzt und nur beim Zeichnen gelesen (kein Zustand, keine Neu-Komposition).
 */
class QueueGeo {
    var cover: Rect? = null
    var title: Rect? = null
    var headerBottom: Float = 0f
    var enabled: Boolean = true
}

/** Fertige Verschiebungen für einen Fortschrittswert der Warteschlange (alles in Pixeln) */
class QueueXform(
    val titleDx: Float,
    val titleDy: Float,
    val titleWidth: Float,
    val coverScale: Float,
    val coverDy: Float
)

/**
 * Berechnet, wie Titeltext und Cover vor der aufgehenden Warteschlange zurückweichen:
 *  1. Die Oberkante der Warteschlange erreicht den Text und schiebt ihn vor sich her nach oben.
 *  2. Sobald der Text das Cover berührt, schiebt er das Cover mit. Das Cover schrumpft dabei, sein oberer Rand
 *     wandert nach oben links, der Text rutscht gleichzeitig nach rechts und wird schmaler.
 *  3. Am Ende sitzt das kleine Cover links oben direkt über der Warteschlange, der Text steht rechts daneben,
 *     mittig zum Cover.
 * Alles ist eine reine Funktion des Fortschritts [progress] (0 = zu, 1 = offen), funktioniert also genauso beim Schließen
 * und beim Mitziehen mit dem Finger.
 */
class QueueMotion(
    private val geo: QueueGeo,
    private val progress: () -> Float,
    private val anchorBottom: () -> Float?,
    private val rootHeight: () -> Float,
    private val density: () -> Float
) {
    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun compute(): QueueXform? {
        if (!geo.enabled) return null
        val q = progress()
        if (q <= 0f) return null
        val cov = geo.cover ?: return null
        val ti = geo.title ?: return null
        val anchor = anchorBottom() ?: return null
        val d = density()
        val rootH = rootHeight()

        // Oberkante der Warteschlange (wie in QueuePanel/BubbleShape: von unter der Tastenreihe bis zur vollen Höhe)
        val originTop = anchor + 6f * d
        val finalTop = rootH * (1f - QUEUE_HEIGHT_FRACTION)
        if (originTop <= finalTop) return null
        val sheetTop = lerp(originTop, finalTop, q)

        // Zielgeometrie: Cover klein links oben über der Warteschlange, Text rechts daneben
        val coverFinalTop = geo.headerBottom - 6f * d
        val coverFinalBottom = finalTop - 6f * d
        val coverFinalSize = (coverFinalBottom - coverFinalTop).coerceAtLeast(48f * d)
        val coverFinalBottomSafe = coverFinalTop + coverFinalSize
        val titleH = ti.height
        val titleFinalTop = coverFinalTop + coverFinalSize / 2f - titleH / 2f
        val dxFull = (cov.left + coverFinalSize + 20f * d) - ti.left
        val gap = 8f * d

        // 1. Der Text wird von der Oberkante der Warteschlange hochgeschoben
        val carry = sheetTop - titleH - gap
        // Fortschritt, bei dem das Cover seine Endgröße erreicht hat (danach steigt der Text allein bis zur Mitte des Covers)
        val qb = ((originTop - (coverFinalBottomSafe + titleH + gap)) / (originTop - finalTop)).coerceIn(0.05f, 0.98f)
        var titleTop = minOf(ti.top, carry)
        if (q > qb) {
            val bl = smooth((q - qb) / (1f - qb))
            titleTop = lerp(carry, titleFinalTop, bl)
        }

        // 2. Der Text schiebt das Cover: dessen Unterkante liegt auf der Oberkante des Textes
        val coverBottomRest = maxOf(cov.bottom, coverFinalBottomSafe + 1f)
        val coverBottom = titleTop.coerceIn(coverFinalBottomSafe, coverBottomRest)
        val v = ((coverBottomRest - coverBottom) / (coverBottomRest - coverFinalBottomSafe)).coerceIn(0f, 1f)
        val coverTop = lerp(cov.top, coverFinalTop, v)
        val scale = ((coverBottom - coverTop) / cov.width.coerceAtLeast(1f)).coerceIn(0.05f, 1f)

        // Der Text rutscht nach rechts und wird schmaler, solange das Cover schrumpft
        val sv = smooth(v)
        return QueueXform(
            titleDx = sv * dxFull,
            titleDy = titleTop - ti.top,
            titleWidth = lerp(ti.width, ti.width - dxFull, sv),
            coverScale = scale,
            coverDy = coverTop - cov.top
        )
    }
}
