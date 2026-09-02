package com.nyra.comic

import android.graphics.Bitmap
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Hasil penguraian satu balasan terjemahan model, dipilah per golongan nomor.
 *
 * Ini dipakai [kerjakanChunk] untuk memutuskan siapa yang harus diminta ulang
 * dan MENGAPA seorang nomor belum terjawab. Dua golongan itu sifatnya beda:
 *
 *  - [jawaban]   — nomor yang model balas dengan teks sah (termasuk `SKIP`).
 *  - [kosong]    — nomor yang model CANTUMKAN tetapi nilainya hampa
 *    (`null`/`undefined`/string kosong). Artinya model memang "melihat" nomor
 *    itu namun mengaku tidak bisa membacanya.
 *
 * Nomor yang benar-benar tidak ada di balasan (tidak di [jawaban] maupun
 * [kosong]) dihitung seniri oleh pemanggil: `idMapping - jawaban.keys -
 * kosong`. Ia golongan ketiga yang diam-diam: model tidak mencantumkannya.
 */
class HasilTerjemahan(
    val jawaban: Map<String, String>,
    val kosong: Set<String>
)

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
    /**
     * Nilai terjemahan yang sah untuk dipakai, atau null bila model sebenarnya
     * TIDAK menjawab nomor itu.
     *
     * Model vision kadang membalas sebuah ID dengan string kosong, spasi saja,
     * atau literal `null`/`undefined` — semuanya menandakan ia tidak menemukan
     * teks atau melewatkan balon itu. Menaruh nilai begitu ke peta jawaban akan
     * membuat balon dibiarkan berbahasa asli TANPA ada yang mencoba ulang:
     * drawText menolak teks kosong, tapi kerjakanChunk sudah menganggap nomor
     * itu "dijawab" sehingga tidak masuk daftar `hilang`. Di sini nilai hampa
     * dinormalkan jadi null, sehingga nomornya diperlakukan sebagai tidak
     * terjawab dan diminta ulang (atau dicatat sebagai balon yang dilewati).
     *
     * `SKIP` memang nilai sah — jangan dibuang. Juga tidak boleh diubah oleh
     * [siapkanNilai]: drawText memakai perbandingan huruf besar `"SKIP"`.
     */
    fun siapkanNilai(t: String): String? {
        val v = t.trim()
        if (v.isEmpty()) return null
        val u = v.uppercase()
        if (u == "NULL" || u == "UNDEFINED") return null
        return v
    }

    /**
     * Bersihkan nilai terjemahan sebelum digambar.
     *
     * Model vision, di satu sisi, sering membungkus ulang hasilnya atau
     * melempar entitas HTML (sisa penerjemahan yang di-render lewat browser),
     * dan di sisi lain kadang membalas `null`/`<p>...</p>`/kutip luar yang
     * tidak pernah diminta. Nilai seperti itu kalau digambar apa adanya akan
     * tampak sebagai `null`, tag `&lt;p&gt;`, atau kutip ganda yang mengambang
     * — jelas rusak. Fungsi ini menormalkan artefak lazim itu sehingga piksel
     * balon berisi teks terjemahan yang sebenarnya, bukan sampah pembungkus.
     *
     * Aman untuk nilai normal: tanpa `&`, tanpa kurung kutip kembar, tanpa
     * null literal, hasilnya identik dengan masukannya.
     */
    fun normalisasiTerjemahan(t: String): String {
        var s = t.trim()
        if (s.isEmpty()) return s
        val u = s.uppercase()
        if (u == "NULL" || u == "UNDEFINED" || u == "NONE" || u == "N/A") return ""
        if ('&' in s) s = htmlDecode(s)
        // Pasangan kutip luar yang tersisa karena model membungkus ulang.
        // Hanya dilepas ketika kutip pembuka/penutup itu merupakan satu-satunya
        // pasangan di seluruh nilai — kalau ada kutip di dalam, biarkan agar
        // isi dialog bergaya petik tidak rusak.
        if (s.length >= 2) {
            val a = s.first()
            val b = s.last()
            val kutip = (a == '"' && b == '"') || (a == '\u201C' && b == '\u201D') ||
                (a == '\u2018' && b == '\u2019')
            if (kutip) {
                val jumlah = s.count { it == a || it == b }
                val dalam = s.substring(1, s.length - 1).trim()
                if (jumlah <= 2 && dalam.isNotEmpty()) s = dalam
            }
        }
        // Ratakan deretan spasi/tab/baris baru/spasi khusus (termasuk &nbsp;
        // hasil dekode dan ideografik \u3000 dari bahasa sasaran). Layout
        // membungkus ulang teks, jadi spasi ganda hanya membuat balon tampak
        // renggang; satu spasi cukup.
        s = s.replace(Regex("[ \t\n\r\u00A0\u3000]+"), " ").trim()
        s = normalisasiPlaceholder(s)
        return s.trim()
    }

    /**
     * Samakan beragam bentuk penanda "bagian yang tak terbaca" menjadi `[?]`.
     *
     * Model vision kadang menulis `[ ? ]` alih-alih `[?]` yang diminta prompt.
     * Bentuk kurung kotak yang isinya hanya tanda tanya (boleh berspasi)
     * dinormalkan. Bentuk `(?)` sengaja TIDAK dipetakan — itu bisa teks
     * sungguhan, dan hanya penanda kurung kotak yang jelas dianggap marker.
     */
    private fun normalisasiPlaceholder(s: String): String {
        return s.replace(Regex("\\[\\s*\\?\\s*\\]"), "[?]")
    }

    /** Dekode entitas HTML yang lazim muncul dalam balasan LLM. */
    private fun htmlDecode(s: String): String {
        var r = s
        r = r.replace("&amp;", "&")
        r = r.replace("&lt;", "<")
        r = r.replace("&gt;", ">")
        r = r.replace("&quot;", "\"")
        r = r.replace("&#39;", "'")
        r = r.replace("&apos;", "'")
        r = r.replace("&nbsp;", " ")
        r = Regex("&#([0-9]{1,7});").replace(r) { m ->
            val cp = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        r = Regex("&#[xX]([0-9A-Fa-f]{1,6});").replace(r) { m ->
            val cp = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        return r
    }

    /**
     * Pungut pasangan `"nomor": "teks"` dari balasan yang JSON-nya RUSAK.
     *
     * Kenapa ada. Satu tanda kutip yang tidak di-escape di tengah balasan
     * membuat JSONObject melempar, dan sebelum ronde 40 seluruh request ikut
     * hangus: 30 balon yang deteksi, potongan, dan tokennya SUDAH DIBAYAR
     * dikembalikan sebagai `emptyMap()`. Pada batch 19 halaman itu berarti
     * empat halaman penuh (013-016) keluar tanpa satu pun terjemahan,
     * padahal 29 dari 30 balonnya baik-baik saja.
     *
     * Pemulihan ini sengaja BUKAN parser JSON. Ia memindai teks mentah dan
     * mengambil setiap `"<angka>" : "<isi>"`, berhenti pada tanda kutip
     * penutup yang tidak didahului backslash. Baris yang rusak dilewati;
     * baris yang utuh tetap terpakai. Hasil terburuknya sama dengan
     * perilaku lama (peta kosong), hasil terbaiknya menyelamatkan hampir
     * seluruh request.
     *
     * Hanya dipanggil SETELAH JSONObject gagal, jadi jalur normal tidak
     * berubah sedikit pun.
     */
    fun salvageJson(raw: String): Map<String, String> {
        val hasil = LinkedHashMap<String, String>()
        // Nilai ter-quote: kunci boleh sampai 8 digit (chunk retry terbesar).
        val re = Regex("\"(\\d{1,8})\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        for (m in re.findAll(raw)) {
            val key = m.groupValues[1]
            if (hasil.containsKey(key)) continue
            val v = siapkanNilai(normalisasiTerjemahan(unescapeJson(m.groupValues[2])))
            if (v != null) hasil[key] = v
        }
        // Nilai token telanjang `SKIP` / `null` yang muncul ketika JSON-nya
        // rusak (kutip penutup hilang). Sengaja TIDAK mengizinkan tanda kutip
        // pembuka: kalau ada kutip, itu nilai ter-quote (mungkin belum tertutup)
        // dan harus dipungut oleh re — jangan tersangkut sebagai token telanjang
        // yang hanya mengambil kata pertama dari kalimat itu.
        val bare = Regex("\"(\\d{1,8})\"\\s*:\\s*([A-Za-z]+)")
        for (m in bare.findAll(raw)) {
            val key = m.groupValues[1]
            if (hasil.containsKey(key)) continue
            val v = siapkanNilai(normalisasiTerjemahan(m.groupValues[2]))
            if (v != null) hasil[key] = v
        }
        return hasil
    }

    /**
     * Parsé satu balasan terjemahan dan pilah per-golongan nomor.
     *
     * Bukan sekadar membuang nilai hampa: nilai `null`/`undefined`/kosong yang
     * model TULIS untuk sebuah nomor dicatat di [HasilTerjemahan.kosong] agar
     * pemanggil tahu model itu sebenarnya melihat nomor itu tetapi mengaku tak
     * bisa membacanya. Nomor yang sama sekali tidak dikirim oleh model bukan
     * bagian dari [HasilTerjemahan] mana pun — pemanggil menurunkannya sebagai
     * `id - jawaban.keys - kosong`.
     *
     * Karena JSON biasanya terbungkus kalimat/blok kode/teks pengantar, nilai
     * mentah dilewatkan ke [cleanJson] dulu; kalau itu gagal (pelemparan), ia
     * diserahkan ke [salvageJson] seperti biasa.
     */
    fun parseHasilTerjemahan(raw: String): HasilTerjemahan {
        val obj = JSONObject(cleanJson(raw))
        val jawaban = LinkedHashMap<String, String>()
        val kosong = LinkedHashSet<String>()
        for (key in obj.keys()) {
            val nilai = siapkanNilai(normalisasiTerjemahan(obj.optString(key, "")))
            if (nilai != null) jawaban[key] = nilai else kosong.add(key)
        }
        return HasilTerjemahan(jawaban, kosong)
    }

    /** Kembalikan escape JSON standar ke karakter aslinya. */
    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                '"', '\\', '/' -> { sb.append(n); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, min(i + 6, s.length))
                    val cp = hex.toIntOrNull(16)
                    if (hex.length == 4 && cp != null) { sb.append(cp.toChar()); i += 6 }
                    else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * Persiapkan teks mentah dari model agar bisa di-parse sebagai SATU objek
     * JSON. Sering model membungkus hasilnya dengan blok kode berlabel apa pun
     * (`json`, `JSON`, `jsonc`, `javascript`, atau ` ``` ` polos), menambah
     * kalimat pengantar/penutup yang memuat kurung kurawal, atau menulis koma
     * gantung — tanpa pembersihan, `JSONObject` melempar dan seluruh request
     * terlempar ke `salvageJson` padahal JSON-nya sehat.
     *
     * Yang dirapikan di sini, urut:
     *  1. BOM dan spasi tepi.
     *  2. Objek JSON dipilih dari calon-calon `{...}` seimbang, diuji sebagai
     *     JSON, dan yang pertama lolos itulah yang dipakai. Karena calon
     *     dinilai dengan `JSONObject`, blok kode/pengantar/penutup apa pun
     *     labelnya (bukan hanya `json`) otomatis terbuang, kurung kurawal yang
     *     kebetulan ada DI DALAM nilai terjemahan (mis. `{...}` di teks) tidak
     *     memotong objek, dan kotak prosa seperti `{lihat}` di depan objek
     *     ditolak karena bukan JSON.
     *  3. Koma gantung sebelum `}`/`]` di luar string dibuang — `JSONObject`
     *     menolak koma gantung, dan model sangat suka menambahkannya.
     *
     * Aman untuk nilai normal: tanpa blok kode, tanpa kurung di dalam string,
     * tanpa koma gantung, hasilnya identik dengan masukannya.
     */
    fun cleanJson(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        if (t[0] == '\uFEFF') t = t.substring(1).trim()
        return pilihObjekJson(t) ?: t
    }

    /**
     * Pilih objek JSON yang benar-benar valid di antara calon `{...}`.
     *
     * Model gemar menulis kalimat pengantar yang memuat kurung kurawal
     * (`{lihat}`, `{catatan}`, dsb.) SEBELUM objek JSON yang sesungguhnya.
     * Pemindai seimbang pada `{` pertama akan memilih kotak prosa itu dan
     * menolaknya. Di sini tiap `{` dicoba secara berurutan: setiap calon
     * diambil sebagai objek seimbang, koma gantungnya dibuang, lalu diuji
     * sebagai JSON. Yang pertama lolos uji itulah yang dipakai — sehingga
     * prosa yang berisi kurung di depan tidak lagi merampas objek betulan.
     * Bila tak ada yang lolos, `null` (teks diserahkan ke `salvageJson`).
     */
    private fun pilihObjekJson(t: String): String? {
        var pos = t.indexOf('{')
        while (pos >= 0) {
            val span = cariObjekSeimbang(t, pos)
            if (span != null) {
                val bersih = buangKomaGantung(span)
                if (validJson(bersih)) return bersih
            }
            pos = t.indexOf('{', pos + 1)
        }
        return null
    }

    /**
     * Ambil substring objek seimbang `{...}` yang mulai dari `start`,
     * mengabaikan kurung yang berada di dalam string (beserta escape-nya).
     * Mengembalikan `null` bila `{` itu tak pernah tertutup.
     */
    private fun cariObjekSeimbang(t: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < t.length) {
            val c = t[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return t.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** True bila `s` bisa di-parse sebagai satu objek JSON. */
    private fun validJson(s: String): Boolean =
        try { JSONObject(s); true } catch (e: Exception) { false }

    /**
     * Buang koma gantung: `,` yang langsung diikuti (setelah spasi) oleh `}`
     * atau `]` DI LUAR string. `JSONObject` menolak koma gantung, dan model
     * vision sering menulis `"1": "x", "2": "y", }`.
     */
    private fun buangKomaGantung(s: String): String {
        val sb = StringBuilder(s.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> { inString = true; sb.append(c) }
                ',' -> {
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                        // koma gantung — buang
                    } else {
                        sb.append(c)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Urutan baca satu halaman komik.
     *
     * Mengurutkan murni dengan `compareBy(y, x)` punya dua cacat: ia membaca
     * kiri ke kanan (salah untuk manga Jepang), dan selisih y beberapa piksel
     * saja sudah dianggap baris berbeda sehingga dua balon yang jelas sebaris
     * bisa tertukar.
     *
     * Di sini kotak dikelompokkan dulu menjadi baris lewat tumpang tindih
     * vertikal, baru tiap baris diurutkan mendatar. Ambang [OVERLAP_BARIS]
     * memakai sisi yang lebih pendek supaya balon tinggi tidak menelan seluruh
     * halaman menjadi satu baris.
     */
    fun urutBaca(input: List<IntArray>, kananKeKiri: Boolean): List<IntArray> {
        if (input.size < 2) return input.map { it }

        val sisa = input.sortedWith(compareBy({ it[1] }, { it[0] })).toMutableList()
        val baris = ArrayList<MutableList<IntArray>>()

        while (sisa.isNotEmpty()) {
            val kel = mutableListOf(sisa.removeAt(0))
            var tumbuh = true
            while (tumbuh) {
                tumbuh = false
                val it2 = sisa.iterator()
                while (it2.hasNext()) {
                    val b = it2.next()
                    if (kel.any { sebaris(it, b) }) {
                        kel.add(b); it2.remove(); tumbuh = true
                    }
                }
            }
            baris.add(kel)
        }

        // Baris diurutkan berdasarkan tepi atasnya; isi baris mendatar.
        baris.sortBy { kel -> kel.minOf { it[1] } }
        val keluar = ArrayList<IntArray>(input.size)
        for (kel in baris) {
            val urut = if (kananKeKiri) {
                kel.sortedWith(compareByDescending<IntArray> { it[0] }.thenBy { it[1] })
            } else {
                kel.sortedWith(compareBy<IntArray> { it[0] }.thenBy { it[1] })
            }
            keluar.addAll(urut)
        }
        return keluar
    }

    /** Dua kotak dianggap sebaris bila tumpang tindih vertikalnya dominan. */
    private fun sebaris(a: IntArray, b: IntArray): Boolean {
        val ov = min(a[3], b[3]) - max(a[1], b[1])
        if (ov <= 0) return false
        val pendek = min(a[3] - a[1], b[3] - b[1])
        if (pendek <= 0) return false
        return ov >= OVERLAP_BARIS * pendek
    }

    const val OVERLAP_BARIS = 0.5f
}
