package com.cypy.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Port of the typography half of image_service.py:
 * tulis_teks_di_balon / tulis_teks_jepang_vertikal / bungkus_teks_per_kata.
 */
class TextRenderer(ctx: Context) {

    private val fontManga: Typeface
    private val fontUniversal: Typeface

    init {
        fontManga = loadAssetFont(ctx, "komika.ttf")
        fontUniversal = loadAssetFont(ctx, "kosugi.ttf")
    }

    private fun loadAssetFont(ctx: Context, name: String): Typeface = try {
        Typeface.createFromAsset(ctx.assets, name)
    } catch (e: Exception) {
        val f = File(ctx.cacheDir, name)
        if (!f.exists()) ctx.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        Typeface.createFromFile(f)
    }

    private val nonLatin: Pattern = Pattern.compile(
        "[\\u3040-\\u309f\\u30a0-\\u30ff\\u4e00-\\u9fff\\uac00-\\ud7af\\u0e00-\\u0e7f\\u0400-\\u04ff\\u0600-\\u06ff]"
    )

    fun hasNonLatin(text: String): Boolean = nonLatin.matcher(text).find()

    private fun fontFor(text: String): Typeface =
        if (hasNonLatin(text)) fontUniversal else fontManga

    private data class Setting(
        val skalaW: Float, val skalaH: Float, val fontScale: Float,
        val spacingRatio: Float, val maxFont: Int, val minFont: Int
    )

    /** pilih_setting_teks */
    private fun chooseSetting(boxW: Int, boxH: Int, text: String): Setting {
        val clean = text.replace(" ", "").replace("\n", "")
        val chars = clean.length
        val area = boxW * boxH
        val bigBubble = boxW >= 150 && boxH >= 130 && area >= 30000
        val shortText = chars <= 55
        val veryShortText = chars <= 28

        return when {
            bigBubble && veryShortText -> Setting(0.85f, 0.78f, 0.95f, 0.055f, 86, 10)
            bigBubble && shortText -> Setting(0.82f, 0.78f, 0.94f, 0.060f, 82, 10)
            else -> Setting(0.76f, 0.76f, 0.92f, 0.075f, 76, 8)
        }
    }

    private fun makePaint(tf: Typeface, size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf
        textSize = size
        isSubpixelText = true
    }

    /** pecah_kata_hyphen_jika_panjang */
    private fun splitHyphen(word: String, paint: Paint, maxW: Float): List<String> {
        if (paint.measureText(word) <= maxW) return listOf(word)
        if (!word.contains("-")) return listOf(word)
        val parts = word.split("-")
        val tokens = ArrayList<String>()
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            tokens.add(if (i < parts.size - 1) "$part-" else part)
        }
        return if (tokens.isEmpty()) listOf(word) else tokens
    }

    /** bungkus_teks_per_kata */
    private fun wrapText(text: String, paint: Paint, maxW: Float): List<String> {
        val cjkMode = !text.contains(" ") && hasNonLatin(text)
        val rawWords: List<String> =
            if (cjkMode) text.map { it.toString() } else text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (rawWords.isEmpty()) return listOf("")

        val words = ArrayList<String>()
        for (w in rawWords) words.addAll(splitHyphen(w, paint, maxW))

        val lines = ArrayList<String>()
        var current = ""
        for (w in words) {
            val candidate = when {
                current.isEmpty() -> w
                cjkMode -> current + w
                else -> "$current $w"
            }
            if (paint.measureText(candidate) <= maxW) {
                current = candidate
            } else {
                if (current.isNotEmpty()) { lines.add(current); current = w }
                else { lines.add(w); current = "" }
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return if (lines.isEmpty()) listOf("") else lines
    }

    private data class Block(val width: Float, val height: Float, val lineHeight: Float)

    private fun measureBlock(lines: List<String>, paint: Paint, spacing: Float): Block {
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent)
        var w = 0f
        for (l in lines) w = max(w, paint.measureText(l))
        val h = lines.size * lineH + max(0, lines.size - 1) * spacing
        return Block(w, h, lineH)
    }

    /**
     * tulis_teks_di_balon — clears the bubble then fits the translation into it.
     * Coordinates are in bitmap space.
     */
    fun drawInBubble(
        canvas: Canvas, bmp: Bitmap, text0: String,
        x1: Int, y1: Int, x2: Int, y2: Int,
        backgroundPatch: Boolean, targetLanguage: String?,
        maskMarginRatio: Float,
        colors: Palette.Colors = Palette.DEFAULT
    ) {
        var text = text0.trim()
        if (text.isEmpty()) return

        val boxW = max(1, x2 - x1)
        val boxH = max(1, y2 - y1)
        val setting = chooseSetting(boxW, boxH, text)
        val langKey = (targetLanguage ?: "").lowercase()

        if (!backgroundPatch) {
            // White rounded overlay with soft edge, matching the PIL overlay+blur step.
            val marginX = (boxW * maskMarginRatio).toInt()
            val marginY = (boxH * maskMarginRatio).toInt()
            val radius = max(6, min(boxW, boxH) / 3).toFloat()
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.background
                style = Paint.Style.FILL
                maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(
                RectF(
                    (x1 + marginX).toFloat(), (y1 + marginY).toFloat(),
                    (x2 - marginX).toFloat(), (y2 - marginY).toFloat()
                ), radius, radius, clear
            )
        }

        if (langKey == "japanese" || langKey == "jepang") {
            drawVerticalJapanese(canvas, text, x1, y1, x2, y2, setting, backgroundPatch, colors)
            return
        }

        if (!hasNonLatin(text)) text = text.uppercase()

        val maxW = boxW * setting.skalaW
        val maxH = boxH * setting.skalaH
        val tf = fontFor(text)

        var bestFontSize = setting.minFont
        for (size in setting.maxFont downTo setting.minFont) {
            val paint = makePaint(tf, size.toFloat())
            val spacing = max(1f, size * setting.spacingRatio)
            val lines = wrapText(text, paint, maxW)
            val block = measureBlock(lines, paint, spacing)
            if (block.width <= maxW && block.height <= maxH) { bestFontSize = size; break }
        }

        val finalSize = max(setting.minFont, (bestFontSize * setting.fontScale).toInt())
        val paint = makePaint(tf, finalSize.toFloat())
        val spacing = max(1f, finalSize * setting.spacingRatio)
        val lines = wrapText(text, paint, maxW)
        val block = measureBlock(lines, paint, spacing)

        val centerX = x1 + boxW / 2f
        val startY = y1 + (boxH - block.height) / 2f

        val strokeW = max(1f, finalSize / 11f)

        if (backgroundPatch) {
            val pad = max(6f, finalSize / 2f)
            val patch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.background }
            val radius = max(4f, finalSize / 2f)
            canvas.drawRoundRect(
                RectF(
                    centerX - block.width / 2f - pad, startY - pad,
                    centerX + block.width / 2f + pad, startY + block.height + pad
                ), radius, radius, patch
            )
        }

        val strokePaint = makePaint(tf, finalSize.toFloat()).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 2f
            strokeJoin = Paint.Join.ROUND
            color = colors.background
            textAlign = Paint.Align.CENTER
        }
        val fillPaint = makePaint(tf, finalSize.toFloat()).apply {
            style = Paint.Style.FILL
            color = colors.foreground
            textAlign = Paint.Align.CENTER
        }

        val fm = paint.fontMetrics
        var baseline = startY - fm.ascent
        for ((i, line) in lines.withIndex()) {
            // Warna per baris kalau balon aslinya memang memakai lebih dari
            // satu warna (ronde 16). Jumlah baris terjemahan jarang sama
            // dengan aslinya, jadi pemetaannya proporsional: baris ke-i dari
            // n baris terjemahan memakai warna baris yang posisinya sepadan
            // pada teks asli. Untuk kasus lazim 2 lawan 2 hasilnya persis.
            fillPaint.color = warnaBarisKe(i, lines.size, colors)
            canvas.drawText(line, centerX, baseline, strokePaint)
            canvas.drawText(line, centerX, baseline, fillPaint)
            baseline += block.lineHeight + spacing
        }
    }

    /**
     * Memetakan baris terjemahan ke warna baris aslinya secara proporsional.
     * Mengembalikan [Palette.Colors.foreground] bila teks aslinya satu warna.
     */
    internal fun warnaBarisKe(indeks: Int, jumlahBaris: Int, colors: Palette.Colors): Int {
        val w = colors.warnaBaris
        if (w.isEmpty()) return colors.foreground
        if (jumlahBaris <= 1) return w[0]
        val pos = indeks.toFloat() / (jumlahBaris - 1).toFloat()
        val idx = Math.round(pos * (w.size - 1)).coerceIn(0, w.size - 1)
        return w[idx]
    }

    /** tulis_teks_jepang_vertikal */
    private fun drawVerticalJapanese(
        canvas: Canvas, text0: String,
        x1: Int, y1: Int, x2: Int, y2: Int,
        setting: Setting, backgroundPatch: Boolean,
        colors: Palette.Colors = Palette.DEFAULT
    ) {
        val text = text0.replace(" ", "").replace("\n", "")
        if (text.isEmpty()) return

        val boxW = max(1, x2 - x1)
        val boxH = max(1, y2 - y1)
        val maxW = boxW * setting.skalaW
        val maxH = boxH * setting.skalaH

        var bestFontSize = setting.minFont
        var bestColumns: List<String> = emptyList()

        for (size in setting.maxFont downTo setting.minFont) {
            val charsPerCol = max(1, (maxH / size).toInt())
            val columns = text.chunked(charsPerCol)
            if (columns.size * size <= maxW) {
                bestFontSize = size
                bestColumns = columns
                break
            }
        }
        if (bestColumns.isEmpty()) {
            val charsPerCol = max(1, (maxH / setting.minFont).toInt())
            bestColumns = text.chunked(charsPerCol)
        }

        val fontSize = max(setting.minFont, (bestFontSize * setting.fontScale).toInt())
        val tf = fontUniversal

        val actualW = bestColumns.size * fontSize
        val actualH = (bestColumns.maxOfOrNull { it.length } ?: 0) * fontSize

        var startX = x1 + (boxW + actualW) / 2f - fontSize
        val startY = y1 + (boxH - actualH) / 2f
        val strokeW = max(1f, fontSize / 11f)

        if (backgroundPatch) {
            val pad = max(6f, fontSize / 2f)
            val patch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.background }
            canvas.drawRect(
                RectF(
                    startX - actualW + fontSize - pad, startY - pad,
                    startX + fontSize + pad, startY + actualH + pad
                ), patch
            )
        }

        val strokePaint = makePaint(tf, fontSize.toFloat()).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 2f
            strokeJoin = Paint.Join.ROUND
            color = colors.background
        }
        val fillPaint = makePaint(tf, fontSize.toFloat()).apply {
            style = Paint.Style.FILL
            color = colors.foreground
        }
        val fm = strokePaint.fontMetrics

        for (col in bestColumns) {
            var currY = startY
            for (ch0 in col) {
                var ch = ch0.toString()
                var offX = 0f
                var offY = 0f
                if (ch == "。" || ch == "、" || ch == ".") {
                    offX = fontSize * 0.6f; offY = -fontSize * 0.6f
                } else if (ch == "ー") {
                    ch = "︱"
                }
                val cx = startX + offX
                val cy = currY + offY - fm.ascent
                canvas.drawText(ch, cx, cy, strokePaint)
                canvas.drawText(ch, cx, cy, fillPaint)
                currY += fontSize
            }
            startX -= fontSize
        }
    }

    /** Red mosaic ID numbers use the manga font, like ImageFont.truetype(FONT_MANGA, 40). */
    fun mosaicNumberPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = fontManga
        textSize = 40f
        color = Color.RED
        style = Paint.Style.FILL
    }
}
