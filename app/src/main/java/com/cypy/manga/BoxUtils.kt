package com.cypy.manga

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Port of the box-filtering half of cypy/core/services/image_service.py.
 * Box = IntArray(4) as [x1, y1, x2, y2].
 */
object BoxUtils {

    fun area(b: IntArray): Int = max(0, b[2] - b[0]) * max(0, b[3] - b[1])

    fun inter(a: IntArray, b: IntArray): Int {
        val ix1 = max(a[0], b[0]); val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2]); val iy2 = min(a[3], b[3])
        return max(0, ix2 - ix1) * max(0, iy2 - iy1)
    }

    private fun perluDigabung(a: IntArray, b: IntArray): Boolean {
        val areaA = area(a); val areaB = area(b)
        if (areaA == 0 || areaB == 0) return false
        val i = inter(a, b)
        if (i == 0) return false
        val iou = i.toFloat() / (areaA + areaB - i).toFloat()
        val coverSmall = i.toFloat() / min(areaA, areaB).toFloat()
        return iou >= 0.28f || coverSmall >= 0.82f
    }

    private fun overlap1d(a1: Int, a2: Int, b1: Int, b2: Int): Int =
        max(0, min(a2, b2) - max(a1, b1))

    /** buang_kotak_raksasa_palsu */
    const val MIN_BOX_SIDE = 4

    /** Ambang kerataan interior yang menandai sebuah kotak sebagai balon sah. */
    const val FLAT_BUBBLE = 0.60f

    /**
     * Buang kotak yang jelas-jelas cacat sebelum filter apa pun menyentuhnya.
     *
     * Decoder YOLO bisa menghasilkan kotak berlebar/tinggi NEGATIF ketika pusat
     * prediksi jatuh di luar tepi letterbox. Kotak begitu ber-area 0, dan area 0
     * meracuni setiap perbandingan rasio di hilir: pada dropFakeGiants,
     * "area(besar) > 2.5 * 0" dan "irisan >= 0.8 * 0" dua-duanya selalu benar,
     * sehingga SATU kotak cacat menghapus seluruh balon sah di halaman itu.
     */
    fun sanitize(boxes: List<IntArray>, imgW: Int, imgH: Int): List<IntArray> {
        val out = ArrayList<IntArray>(boxes.size)
        for (b in boxes) {
            val x1 = b[0].coerceIn(0, imgW); val y1 = b[1].coerceIn(0, imgH)
            val x2 = b[2].coerceIn(0, imgW); val y2 = b[3].coerceIn(0, imgH)
            if (x2 - x1 >= MIN_BOX_SIDE && y2 - y1 >= MIN_BOX_SIDE) {
                out.add(intArrayOf(x1, y1, x2, y2))
            }
        }
        return out
    }

    /**
     * Buang kotak raksasa palsu -- kotak yang cuma membungkus beberapa balon lain.
     *
     * Aturan lamanya "kalau menelan SATU kotak yang 2,5x lebih kecil, buang" salah
     * untuk balon berduri (SCRIIII, STOOOP!, ARGH!). Duri di tepi balon terdeteksi
     * sebagai kotak kecil tersendiri, lalu balon induknya yang sah ikut dibuang
     * gara-gara menelan durinya sendiri. Di halaman STOOOP! pengguna, balon
     * 587x968 lenyap persis karena ini.
     *
     * Raksasa palsu yang sungguhan membungkus balon-balon yang SALING TERPISAH.
     * Jadi kotak hanya dibuang kalau menelan >=2 kotak yang tidak saling tumpang
     * tindih. Balon berduri menelan duri yang semuanya menempel di dirinya
     * sendiri, jadi ia selamat.
     */
    fun dropFakeGiants(boxes: List<IntArray>): List<IntArray> {
        if (boxes.isEmpty()) return emptyList()
        val keep = ArrayList<IntArray>(boxes.size)
        for (i in boxes.indices) {
            val bi = boxes[i]; val areaI = area(bi)
            val ditelan = ArrayList<IntArray>()
            for (j in boxes.indices) {
                if (i == j) continue
                val bj = boxes[j]; val areaJ = area(bj)
                if (areaJ <= 0) continue
                if (areaI > 2.5 * areaJ && inter(bi, bj) >= 0.8 * areaJ) ditelan.add(bj)
            }
            var adaYangTerpisah = false
            outer@ for (a in ditelan.indices) {
                for (b in a + 1 until ditelan.size) {
                    val kecil = minOf(area(ditelan[a]), area(ditelan[b]))
                    if (kecil > 0 && inter(ditelan[a], ditelan[b]) < 0.3 * kecil) {
                        adaYangTerpisah = true; break@outer
                    }
                }
            }
            if (!adaYangTerpisah) keep.add(bi)
        }
        return keep
    }

    /** gabung_kotak_tumpang_tindih */
    fun mergeOverlapping(input: List<IntArray>): List<IntArray> {
        if (input.isEmpty()) return emptyList()
        var boxes = input.map { intArrayOf(it[0], it[1], it[2], it[3]) }.sortedBy { it[0] }
        var changed = true
        while (changed) {
            changed = false
            val result = ArrayList<IntArray>()
            val used = BooleanArray(boxes.size)
            for (i in boxes.indices) {
                if (used[i]) continue
                var x1 = boxes[i][0]; var y1 = boxes[i][1]
                var x2 = boxes[i][2]; var y2 = boxes[i][3]
                for (j in i + 1 until boxes.size) {
                    if (used[j]) continue
                    if (boxes[j][0] > x2) break
                    if (perluDigabung(intArrayOf(x1, y1, x2, y2), boxes[j])) {
                        x1 = min(x1, boxes[j][0]); y1 = min(y1, boxes[j][1])
                        x2 = max(x2, boxes[j][2]); y2 = max(y2, boxes[j][3])
                        used[j] = true; changed = true
                    }
                }
                result.add(intArrayOf(x1, y1, x2, y2))
                used[i] = true
            }
            boxes = result
        }
        return boxes.sortedWith(compareBy({ it[1] }, { it[0] }))
    }

    /** buang_kotak_ngawur */
    /**
     * buang_kotak_ngawur — drops boxes whose SHAPE cannot be a speech bubble
     * (page-wide banners, sound-effect bands, and similar artefacts).
     *
     * Two of the three rules are relative to the page height, which only makes
     * sense while a page is roughly page-shaped. On a webtoon strip
     * (1080x11700) "h <= 16% of the page" means "shorter than 1872px", so
     * ordinary bubbles were classed as flat banners and thrown away: a strip
     * with 15 correctly detected bubbles came out of this filter with 10.
     *
     * On a genuine strip the height-relative rules therefore use a page-sized
     * reference height instead of the raw strip height. Any page below that
     * threshold keeps Python's exact arithmetic, so the oracle parity in
     * BoxParityTest still holds byte for byte.
     */
    fun dropAbsurd(boxes: List<IntArray>, imgW: Int, imgH: Int): List<IntArray> {
        // Only genuine strips get a substituted reference height; ordinary and
        // merely-tall pages keep Python's exact arithmetic.
        val isStrip = imgH > imgW * DetectMath.STRIP_SHAPE_RATIO
        val refH = if (isStrip)
            (imgW * DetectMath.TILE_WINDOW_RATIO).toInt().coerceAtLeast(1)
        else imgH
        val refArea = max(1, imgW * refH).toFloat()
        return boxes.filter { b ->
            val w = max(1, b[2] - b[0]); val h = max(1, b[3] - b[1])
            val ratio = w.toFloat() / h.toFloat()
            val areaRatio = (w * h) / refArea
            val tooWide = ratio >= 3.2f && w >= imgW * 0.35f
            val tooFlatBig = w >= imgW * 0.50f && h <= refH * 0.16f
            val tooBigThin = areaRatio >= 0.035f && ratio >= 2.8f
            !(tooWide || tooFlatBig || tooBigThin)
        }
    }

    /** buang_kotak_sfx_dan_gambar — uses grayscale/edge statistics on the bitmap. */
    fun dropSfxAndArt(bmp: Bitmap, boxes: List<IntArray>, mode: String): List<IntArray> {
        val imgH = bmp.height; val imgW = bmp.width
        val imgArea = max(1, imgH * imgW).toFloat()

        val blackThr: Float; val edgeThr: Float; val whiteSafe: Float
        when (mode.lowercase()) {
            "relaxed" -> { blackThr = 0.20f; edgeThr = 0.14f; whiteSafe = 0.58f }
            "strict"  -> { blackThr = 0.13f; edgeThr = 0.09f; whiteSafe = 0.68f }
            else      -> { blackThr = 0.16f; edgeThr = 0.11f; whiteSafe = 0.62f }
        }

        val out = ArrayList<IntArray>()
        for (b in boxes) {
            val x1 = b[0].coerceIn(0, imgW); val y1 = b[1].coerceIn(0, imgH)
            val x2 = b[2].coerceIn(0, imgW); val y2 = b[3].coerceIn(0, imgH)
            val w = max(1, x2 - x1); val h = max(1, y2 - y1)
            if (x2 <= x1 || y2 <= y1) continue

            val areaRatio = (w * h) / imgArea
            val ratio = w.toFloat() / h.toFloat()

            val smallBox = w < imgW * 0.18f && h < imgH * 0.18f && areaRatio < 0.020f
            if (smallBox) { out.add(b); continue }

            val stats = grayStats(bmp, x1, y1, w, h)
            if (stats.whiteRatio >= whiteSafe) { out.add(b); continue }

            // Penyelamat balon non-putih. Aturan SFX di bawah semuanya
            // mengandaikan balon itu PUTIH: apa pun yang gelap dan bertepi
            // banyak dianggap efek suara. Itu menghapus balon hitam, balon
            // biru gelap, dan balon berduri (ARGH!, STOOOP!) di halaman
            // pengguna. Interior yang rata membedakan balon dari artwork
            // tanpa peduli warnanya.
            if (stats.flatRatio >= FLAT_BUBBLE) { out.add(b); continue }

            val sfxOrArt = areaRatio > 0.018f && stats.blackRatio > blackThr && stats.edgeRatio > edgeThr
            val flatSuspicious = ratio > 2.2f && w > imgW * 0.30f &&
                    stats.edgeRatio > max(0.07f, edgeThr - 0.03f) && stats.whiteRatio < whiteSafe
            val bigArtSuspicious = areaRatio > 0.045f && stats.whiteRatio < 0.55f && stats.edgeRatio > 0.075f

            if (sfxOrArt || flatSuspicious || bigArtSuspicious) continue
            out.add(b)
        }
        return out
    }

    private data class GrayStats(
        val blackRatio: Float, val whiteRatio: Float, val edgeRatio: Float,
        val flatRatio: Float
    )

    /** Grayscale threshold + Sobel-magnitude edge ratio (stands in for cv2.Canny). */
    private fun grayStats(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): GrayStats {
        // Subsample large crops for speed; ratios stay statistically equivalent.
        val step = max(1, max(w, h) / 220)
        val cw = (w + step - 1) / step
        val ch = (h + step - 1) / step
        if (cw < 3 || ch < 3) return GrayStats(0f, 1f, 0f, 1f)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x, y, w, h)

        val gray = ByteArray(cw * ch)
        var black = 0; var white = 0
        for (j in 0 until ch) {
            val sy = min(h - 1, j * step)
            for (i in 0 until cw) {
                val sx = min(w - 1, i * step)
                val p = pixels[sy * w + sx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                val v = ((r * 299 + g * 587 + bl * 114) / 1000).coerceIn(0, 255)
                gray[j * cw + i] = v.toByte()
                if (v <= 79) black++
                if (v > 220) white++
            }
        }

        val total = (cw * ch).toFloat()

        // Kerataan interior: fraksi piksel yang simpangan bakunya rendah pada
        // jendela 9x9. Balon -- putih, hitam, biru gelap, apa pun warnanya --
        // punya isi yang rata; SFX dan artwork tidak pernah rata.
        var flat = 0
        run {
            // Integral image supaya biayanya O(piksel), bukan O(piksel * 81).
            val iw = cw + 1
            val sat = LongArray(iw * (ch + 1))
            val sat2 = LongArray(iw * (ch + 1))
            for (j in 0 until ch) {
                var rowSum = 0L; var rowSum2 = 0L
                for (i in 0 until cw) {
                    val v = (gray[j * cw + i].toInt() and 0xFF).toLong()
                    rowSum += v; rowSum2 += v * v
                    sat[(j + 1) * iw + i + 1] = sat[j * iw + i + 1] + rowSum
                    sat2[(j + 1) * iw + i + 1] = sat2[j * iw + i + 1] + rowSum2
                }
            }
            val rad = 4
            for (j in 0 until ch) {
                val j0 = max(0, j - rad); val j1 = min(ch - 1, j + rad)
                for (i in 0 until cw) {
                    val i0 = max(0, i - rad); val i1 = min(cw - 1, i + rad)
                    val n = ((j1 - j0 + 1) * (i1 - i0 + 1)).toDouble()
                    val a1 = (j1 + 1) * iw + i1 + 1; val b1 = j0 * iw + i1 + 1
                    val c1 = (j1 + 1) * iw + i0; val d1 = j0 * iw + i0
                    val sum = sat[a1] - sat[b1] - sat[c1] + sat[d1]
                    val sumSq = sat2[a1] - sat2[b1] - sat2[c1] + sat2[d1]
                    val mean = sum / n
                    if (sumSq / n - mean * mean < 144.0) flat++   // simpangan baku < 12
                }
            }
        }

        var edges = 0
        for (j in 1 until ch - 1) {
            for (i in 1 until cw - 1) {
                val idx = j * cw + i
                fun g(o: Int) = gray[o].toInt() and 0xFF
                val gx = -g(idx - cw - 1) - 2 * g(idx - 1) - g(idx + cw - 1) +
                        g(idx - cw + 1) + 2 * g(idx + 1) + g(idx + cw + 1)
                val gy = -g(idx - cw - 1) - 2 * g(idx - cw) - g(idx - cw + 1) +
                        g(idx + cw - 1) + 2 * g(idx + cw) + g(idx + cw + 1)
                val mag = kotlin.math.abs(gx) + kotlin.math.abs(gy)
                if (mag >= 160) edges++
            }
        }

        return GrayStats(black / total, white / total, edges / total, flat / total)
    }

    /** buat_crop_lega_tapi_tidak_nyamber */
    fun roomyCrop(
        box: IntArray, all: List<IntArray>, imgW: Int, imgH: Int,
        padX: Int, padY: Int, overlapLimit: Float
    ): IntArray {
        val (x1, y1, x2, y2) = listOf(box[0], box[1], box[2], box[3])
        var cx1 = max(0, x1 - padX)
        var cy1 = max(0, y1 - padY)
        var cx2 = min(imgW, x2 + padX)
        var cy2 = min(imgH, y2 + padY)

        val boxW = max(1, x2 - x1); val boxH = max(1, y2 - y1)

        for (other in all) {
            if (other.contentEquals(box)) continue
            val ox1 = other[0]; val oy1 = other[1]; val ox2 = other[2]; val oy2 = other[3]
            val otherW = max(1, ox2 - ox1); val otherH = max(1, oy2 - oy1)

            val overlapX = overlap1d(x1, x2, ox1, ox2).toFloat() / min(boxW, otherW).toFloat()
            val overlapY = overlap1d(y1, y2, oy1, oy2).toFloat() / min(boxH, otherH).toFloat()

            if (overlapX >= overlapLimit) {
                if (oy1 >= y2) {
                    val bound = (y2 + oy1) / 2
                    cy2 = min(cy2, max(y2, bound))
                } else if (oy2 <= y1) {
                    val bound = (oy2 + y1) / 2
                    cy1 = max(cy1, min(y1, bound))
                }
            }
            if (overlapY >= overlapLimit) {
                if (ox1 >= x2) {
                    val bound = (x2 + ox1) / 2
                    cx2 = min(cx2, max(x2, bound))
                } else if (ox2 <= x1) {
                    val bound = (ox2 + x1) / 2
                    cx1 = max(cx1, min(x1, bound))
                }
            }
        }
        return intArrayOf(cx1, cy1, cx2, cy2)
    }

    /** bersihkan_json_dari_gemini */
    fun cleanJson(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```json")) t = t.substring(7).trim()
        if (t.startsWith("```")) t = t.substring(3).trim()
        if (t.endsWith("```")) t = t.substring(0, t.length - 3).trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) t = t.substring(start, end + 1)
        return t.trim()
    }
}
