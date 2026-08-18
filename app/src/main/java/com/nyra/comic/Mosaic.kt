package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max

/**
 * Port of the mosaic-stitching helpers in translator.py:
 * mask_luar_box_utama / shrink_crop_list_if_mosaic_too_tall / mosaic canvas build.
 */
object Mosaic {

    class Crop(val id: String, val bitmap: Bitmap)

    /**
     * Margin masker efektif (piksel), diukur dari sisi kotak yang LEBIH PANJANG.
     * Balon 335x111 milik pengguna butuh 29 px; rumus sisi-pendek hanya memberi
     * 6 px sehingga ekor huruf tertinggal. Balon 986x1053 butuh 77 px.
     */
    fun effectiveMaskMargin(boxW: Int, boxH: Int, base: Int, ratio: Float, cap: Int): Int {
        val fromSize = (max(boxW, boxH) * ratio).toInt()
        return minOf(cap, max(base, fromSize))
    }

    /** mask_luar_box_utama — whiten everything outside the main box (+margin). */
    fun maskOutsideMainBox(
        crop: Bitmap, cropX1: Int, cropY1: Int,
        x1: Int, y1: Int, x2: Int, y2: Int, margin: Int
    ): Bitmap {
        val localX1 = x1 - cropX1
        val localY1 = y1 - cropY1
        val localX2 = x2 - cropX1
        val localY2 = y2 - cropY1

        val mx1 = max(0, localX1 - margin)
        val my1 = max(0, localY1 - margin)
        val mx2 = minOf(crop.width, localX2 + margin)
        val my2 = minOf(crop.height, localY2 + margin)

        if (mx2 <= mx1 || my2 <= my1) return crop

        val out = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val src = Rect(mx1, my1, mx2, my2)
        canvas.drawBitmap(crop, src, Rect(mx1, my1, mx2, my2), null)
        return out
    }

    fun scale(bmp: Bitmap, factor: Float): Bitmap {
        if (factor == 1f) return bmp
        val w = max(1, (bmp.width * factor).toInt())
        val h = max(1, (bmp.height * factor).toInt())
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** shrink_crop_list_if_mosaic_too_tall */
    fun shrinkIfTooTall(
        crops: List<Crop>, maxHeight: Int, spacing: Int, padTopBottom: Int
    ): List<Crop> {
        if (crops.isEmpty()) return crops
        val totalImageHeight = crops.sumOf { it.bitmap.height }
        val totalSpace = crops.size * spacing + padTopBottom
        if (totalImageHeight + totalSpace <= maxHeight) return crops

        val targetH = max(1, maxHeight - totalSpace)
        val ratio = targetH.toFloat() / totalImageHeight.toFloat()

        return crops.map { c ->
            val nw = max(1, (c.bitmap.width * ratio).toInt())
            val nh = max(1, (c.bitmap.height * ratio).toInt())
            Crop(c.id, Bitmap.createScaledBitmap(c.bitmap, nw, nh, true))
        }
    }

    /** Builds the numbered vertical mosaic sent to the LLM. */
    fun build(crops: List<Crop>, cfg: Config, numberPaint: Paint): Bitmap {
        val leftMargin = cfg.marginKiriNomor
        val rightMargin = cfg.marginKanan
        val spacing = cfg.jarakAntarPotongan

        val width = max(
            cfg.lebarMosaikMin,
            (crops.maxOfOrNull { it.bitmap.width } ?: 1) + leftMargin + rightMargin
        )
        val height = crops.sumOf { it.bitmap.height } + crops.size * spacing + 20

        val canvasBmp = Bitmap.createBitmap(width, max(1, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.WHITE)

        var yOffset = 10
        val fm = numberPaint.fontMetrics
        for (c in crops) {
            val textY = yOffset + (c.bitmap.height / 2) - 20
            canvas.drawText(c.id, 5f, textY - fm.ascent, numberPaint)
            canvas.drawBitmap(c.bitmap, leftMargin.toFloat(), yOffset.toFloat(), null)
            yOffset += c.bitmap.height + spacing
        }
        return canvasBmp
    }
}
