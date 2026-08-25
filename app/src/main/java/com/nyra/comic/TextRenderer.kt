package com.nyra.comic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.util.regex.Pattern
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Port of the typography half of image_service.py:
 * tulis_teks_di_balon / tulis_teks_jepang_vertikal / bungkus_teks_per_kata.
 */
class TextRenderer(ctx: Context) {

    private val fontManga: Typeface
    private val fontUniversal: Typeface

    /**
     * Font paket tambahan, dimuat sekali di awal.
     *
     * Dipetakan per aksara, bukan per bahasa tujuan, sebab satu halaman bisa
     * memuat balon dalam aksara berbeda (misalnya nama Korea yang dibiarkan
     * asli di tengah terjemahan Indonesia).
     */
    private val fontTambahan: Map<FontPack.Aksara, Typeface>

    init {
        fontManga = loadAssetFont(ctx, "komika.ttf")
        fontUniversal = loadAssetFont(ctx, "kosugi.ttf")
        fontTambahan = fontPack(ctx)
    }

    companion object {
        /**
         * Batas paling bawah penjamin muat, di bawah minFont mana pun.
         *
         * Penjamin muat boleh menembus [Typography.FONT_MIN] karena tugasnya
         * mencegah tabrakan antar balon, tetapi tetap perlu lantai supaya
         * perulangan pasti berhenti dan teks tidak menyusut jadi debu pada
         * balon yang keliru terdeteksi seukuran beberapa piksel.
         */
        private const val FONT_DARURAT = 6

        /**
         * Typeface paket font, dibagi seluruh proses.
         *
         * Tanpa cache, setiap Pipeline baru mengurai ulang OTF Mandarin 8,3 MB
         * dari penyimpanan — biaya yang terbayar berulang kali saat pengguna
         * membuka editor per halaman. Typeface tidak menyimpan rujukan ke
         * Context, jadi aman disimpan statis.
         */
        private var cache: Map<FontPack.Aksara, Typeface>? = null

        @Synchronized
        private fun fontPack(ctx: Context): Map<FontPack.Aksara, Typeface> {
            cache?.let { return it }
            val m = buildMap {
                FontPack.muat(ctx, FontPack.KR)?.let { put(FontPack.Aksara.HANGUL, it) }
                FontPack.muat(ctx, FontPack.SC)?.let { put(FontPack.Aksara.HAN, it) }
                FontPack.muat(ctx, FontPack.TH)?.let { put(FontPack.Aksara.THAI, it) }
            }
            cache = m
            return m
        }

        /**
         * Buang cache setelah paket diunduh atau dihapus.
         *
         * Wajib dipanggil oleh UI unduhan: tanpa ini, font yang baru selesai
         * diunduh tidak dipakai sampai proses aplikasi dimulai ulang, dan
         * gejalanya persis seperti unduhan yang tidak berfungsi.
         */
        @Synchronized
        fun invalidateFontCache() { cache = null }
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

    /**
     * Pilih font untuk sepotong teks.
     *
     * Urutannya: paket tambahan yang cocok (kalau terpasang) -> kosugi untuk
     * aksara non-Latin -> komika untuk Latin. Paket didahulukan karena hanya
     * ia yang punya glif Hangul/Thai sama sekali; tanpanya teks tergambar
     * sebagai kotak kosong.
     */
    private fun fontFor(text: String): Typeface {
        val aks = FontPack.aksara(text)
        fontTambahan[aks]?.let { return it }
        return if (hasNonLatin(text)) fontUniversal else fontManga
    }

    private data class Setting(
        val skalaW: Float, val skalaH: Float, val fontScale: Float,
        val spacingRatio: Float, val maxFont: Int, val minFont: Int
    )

    /**
     * Setting untuk balon ini: hasil pengukuran teks asli bila ada, kalau
     * tidak jatuh ke tiga tuple lama.
     *
     * Yang diambil dari pengukuran hanyalah skala (seberapa penuh balon
     * aslinya) dan jarak antar baris. Batas font tetap dari tuple lama,
     * sebab batas itu yang menjaga teks tidak pernah menjadi raksasa pada
     * balon besar berteks pendek.
     */
    private fun settingUntuk(
        boxW: Int, boxH: Int, text: String, gaya: Typography.Gaya
    ): Setting {
        val dasar = chooseSetting(boxW, boxH, text)
        // Gaya terkunci berasal dari pilihan pengguna dan harus dipakai
        // walau balonnya sendiri gagal diukur otomatis.
        if (!gaya.terukur && !gaya.dikunci) return dasar
        val rencana = Typography.putuskan(
            gaya = gaya,
            ukuranMuat = dasar.maxFont,
            panjangTeks = text.length,
            bahasaTegak = false
        )
        return dasar.copy(
            skalaW = rencana.skalaLebar,
            skalaH = rencana.skalaTinggi,
            spacingRatio = rencana.spasiBaris
        )
    }

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

    private fun makePaint(
        tf: Typeface, size: Float, tebal: Boolean = false, jarakHuruf: Float = 0f
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf
        textSize = size
        isSubpixelText = true
        // Komika tidak punya berkas bold terpisah, jadi penebalan sintetis
        // adalah satu-satunya cara mengikuti huruf tebal asli. Diterapkan
        // sebelum pengukuran supaya lebar yang dihitung memang lebar yang
        // digambar - kalau tidak, teks tebal akan luber keluar balon.
        isFakeBoldText = tebal
        // Sama alasannya: letterSpacing harus dipasang SEBELUM measureText,
        // sebab Paint memasukkannya ke dalam lebar yang dilaporkan. Memasang
        // setelah pengukuran akan membuat setiap baris lebih lebar daripada
        // yang dihitung, dan teks luber keluar balon.
        letterSpacing = jarakHuruf
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

        // Pembungkusan seimbang lebih dulu: hasilnya blok yang panjang
        // barisnya merata, bukan satu baris penuh dengan sisa menggantung.
        // Kalau tidak ada susunan yang muat (misalnya satu kata lebih lebar
        // daripada balon walau sudah dipenggal), jatuh ke cara rakus di bawah
        // supaya tetap ada keluaran - pencarian biner yang akan mengecilkan
        // fontnya sampai muat.
        val seimbang = wrapSeimbang(words, paint, maxW, cjkMode)
        if (seimbang != null) return seimbang

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

    /**
     * Pemenggalan paksa per huruf - upaya TERAKHIR, bukan pembungkus biasa.
     *
     * Satu kata panjang tanpa tanda hubung ("KEBERLANGSUNGANNYA" di balon
     * 60x40) tidak bisa dipecah oleh [wrapText] maupun [splitHyphen], jadi
     * lebarnya tetap melebihi balon berapa pun kecilnya font. Mengecilkan
     * font sampai lantai darurat pun tidak menolong; teks tetap keluar garis.
     *
     * Fungsi ini memotong di tengah kata dan menambahkan tanda hubung, cara
     * yang sama dipakai penata huruf pada kolom sempit. Sengaja TIDAK dipakai
     * di dalam pencarian biner: kalau setiap kata boleh dipenggal, pencarian
     * akan selalu "muat" pada font terbesar dan seluruh halaman jadi penuh
     * kata terpotong. Hanya dipanggil setelah pengecilan biasa menyerah.
     */
    private fun pecahPaksa(text: String, paint: Paint, maxW: Float): List<String> {
        val hasil = ArrayList<String>()
        var baris = StringBuilder()
        val hubung = paint.measureText("-")

        fun tutup() {
            if (baris.isNotEmpty()) { hasil.add(baris.toString()); baris = StringBuilder() }
        }

        for (kata in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val calon = if (baris.isEmpty()) kata else "$baris $kata"
            if (paint.measureText(calon) <= maxW) {
                baris = StringBuilder(calon)
                continue
            }
            tutup()
            if (paint.measureText(kata) <= maxW) { baris = StringBuilder(kata); continue }
            // Kata ini sendiri tidak muat: potong per huruf.
            var sisa = kata
            while (paint.measureText(sisa) > maxW) {
                var n = 1
                while (n < sisa.length &&
                    paint.measureText(sisa.substring(0, n + 1)) + hubung <= maxW
                ) n++
                if (n <= 0) n = 1
                hasil.add(sisa.substring(0, n) + "-")
                sisa = sisa.substring(n)
                // Huruf tunggal pun lebih lebar daripada balon: berhenti,
                // penjamin muat di pemanggil yang akan mengecilkan font.
                if (n == 1 && paint.measureText(sisa.take(1)) > maxW) break
            }
            if (sisa.isNotEmpty()) baris = StringBuilder(sisa)
        }
        tutup()
        return if (hasil.isEmpty()) listOf("") else hasil
    }

    /**
     * Menyusun baris seimbang lewat [Typography.pecahSeimbang].
     *
     * Mengembalikan null bila tidak ada susunan yang muat; pemanggil lalu
     * memakai pembungkusan rakus.
     */
    private fun wrapSeimbang(
        words: List<String>, paint: Paint, maxW: Float, cjkMode: Boolean
    ): List<String>? {
        if (words.isEmpty()) return null
        val lebarKata = FloatArray(words.size) { paint.measureText(words[it]) }
        // Pada CJK tidak ada spasi antar aksara.
        val lebarSpasi = if (cjkMode) 0f else paint.measureText(" ")
        val awal = Typography.pecahSeimbang(lebarKata, lebarSpasi, maxW)
        if (awal.isEmpty()) return null

        val pemisah = if (cjkMode) "" else " "
        val hasil = ArrayList<String>(awal.size)
        for ((i, mulai) in awal.withIndex()) {
            val akhir = if (i + 1 < awal.size) awal[i + 1] else words.size
            hasil.add(words.subList(mulai, akhir).joinToString(pemisah))
        }
        return hasil
    }

    private data class Block(val width: Float, val height: Float, val lineHeight: Float)

    /**
     * Menghitung startY supaya tinta yang terlihat berada tepat di tengah
     * kotak secara tegak.
     *
     * Dengan startY apa adanya, tinta membentang dari (startY + tinta_atas)
     * sampai (startY + tinta_bawah), dengan kedua nilai diukur dari puncak
     * baris pertama. Yang diinginkan adalah titik tengah tinta jatuh di
     * tengah kotak, jadi startY digeser sebesar selisihnya.
     *
     * Hasilnya dijepit ke [y1, y1 + boxH - block.height] supaya penyesuaian
     * ini tidak pernah bisa mendorong teks keluar kotak - kalau blok memang
     * lebih tinggi dari kotak (kasus langka teks sangat panjang), perilaku
     * lama yang dipakai.
     */
    private fun startYTinta(
        lines: List<String>, paint: Paint, spacing: Float,
        block: Block, y1: Int, boxH: Int
    ): Float {
        val bawaan = y1 + (boxH - block.height) / 2f
        if (lines.isEmpty()) return bawaan

        val fm = paint.fontMetrics
        val kotak = Rect()
        var atas = Float.MAX_VALUE
        var bawah = -Float.MAX_VALUE
        // Puncak baris ke-i relatif startY; garis dasarnya berjarak -ascent.
        for ((i, baris) in lines.withIndex()) {
            if (baris.isBlank()) continue
            paint.getTextBounds(baris, 0, baris.length, kotak)
            val garisDasar = i * (block.lineHeight + spacing) - fm.ascent
            // kotak.top negatif di atas garis dasar, kotak.bottom positif di bawah.
            atas = min(atas, garisDasar + kotak.top)
            bawah = max(bawah, garisDasar + kotak.bottom)
        }
        if (atas > bawah) return bawaan

        val tinggiTinta = bawah - atas
        val geser = (boxH - tinggiTinta) / 2f - atas
        val batasBawah = y1 + (boxH - block.height)
        if (batasBawah < y1) return bawaan
        return (y1 + geser).coerceIn(y1.toFloat(), batasBawah)
    }

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
        colors: Palette.Colors = Palette.DEFAULT,
        ikutiKontur: Boolean = false,
        gaya: Typography.Gaya = Typography.Gaya.BAWAAN,
        bayangan: Boolean = false
    ) {
        var text = text0.trim()
        if (text.isEmpty()) return

        val boxW = max(1, x2 - x1)
        val boxH = max(1, y2 - y1)
        val setting = settingUntuk(boxW, boxH, text, gaya)
        val langKey = (targetLanguage ?: "").lowercase()

        if (!backgroundPatch) {
            // Utamakan bentuk balon yang sebenarnya (ronde 24). Persegi
            // membulat menimpa artwork di sudut kotak pada balon oval, miring,
            // atau berekor; kontur hanya menimpa bagian dalam balon.
            val pakaiKontur = ikutiKontur &&
                gambarKontur(canvas, bmp, x1, y1, x2, y2, colors.background)
            if (!pakaiKontur) {
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
        }

        if (langKey == "japanese" || langKey == "jepang") {
            drawVerticalJapanese(canvas, text, x1, y1, x2, y2, setting, backgroundPatch, colors)
            return
        }

        if (!hasNonLatin(text)) text = text.uppercase()

        val maxW = boxW * setting.skalaW
        val maxH = boxH * setting.skalaH
        val tf = fontFor(text)

        // Pencarian biner, bukan perulangan menurun: hasilnya ukuran yang
        // sama persis (kecocokan bersifat monotonik) dengan ~7 pengukuran
        // alih-alih sampai 89 per balon.
        val tebal = (gaya.terukur || gaya.dikunci) && gaya.berat == Typography.Berat.TEBAL
        // Jarak antar huruf ikut diukur di dalam pencarian, bukan ditambahkan
        // sesudahnya, supaya ukuran yang ditemukan benar-benar muat.
        val jarak = if (gaya.terukur || gaya.dikunci) {
            Typography.jarakHuruf(text.length, gaya.berat)
        } else 0f
        val bestFontSize = Typography.cariUkuran(setting.minFont, setting.maxFont) { size ->
            val paint = makePaint(tf, size.toFloat(), tebal, jarak)
            val spacing = max(1f, size * setting.spacingRatio)
            val lines = wrapText(text, paint, maxW)
            val block = measureBlock(lines, paint, spacing)
            block.width <= maxW && block.height <= maxH
        }

        var finalSize = max(setting.minFont, (bestFontSize * setting.fontScale).toInt())
        var paint = makePaint(tf, finalSize.toFloat(), tebal, jarak)
        var spacing = max(1f, finalSize * setting.spacingRatio)
        var lines = wrapText(text, paint, maxW)
        var block = measureBlock(lines, paint, spacing)

        // Penjamin muat (ronde 40).
        //
        // Dua jalur membuat teks bisa lolos lebih besar daripada balonnya:
        //
        //  1. [Typography.cariUkuran] mengembalikan batas bawahnya ketika
        //     TIDAK ADA ukuran yang muat - misalnya satu kata panjang tanpa
        //     tanda hubung yang lebih lebar daripada balon walau pada
        //     minFont. Nilai itu lalu digambar begitu saja.
        //  2. minFont pada `max(setting.minFont, ...)` di atas bisa menaikkan
        //     kembali ukuran yang sudah ditemukan mengecil.
        //
        // Akibatnya balon padat saling bertindih dan tulisan keluar dari
        // garis balon. Di sini ukuran diturunkan sampai blok benar-benar
        // masuk, menembus minFont bila memang perlu: huruf kecil masih bisa
        // dibaca, huruf yang menabrak panel tetangga tidak.
        // Kalau yang meluap adalah LEBAR-nya, biang keladinya kata tunggal
        // yang tak terpecah. Mengecilkan font tidak akan menolong sebelum
        // kata itu dipenggal, jadi penggal dulu baru ukur ulang.
        if (block.width > maxW) {
            val dipenggal = pecahPaksa(text, paint, maxW)
            val blokPenggal = measureBlock(dipenggal, paint, spacing)
            if (blokPenggal.width < block.width) {
                lines = dipenggal
                block = blokPenggal
            }
        }

        while ((block.width > maxW || block.height > maxH) && finalSize > FONT_DARURAT) {
            // Turun sebanding dengan sisi terburuk supaya tidak perlu puluhan
            // langkah satu piksel pada balon yang jauh terlalu kecil.
            val faktor = min(maxW / max(1f, block.width), maxH / max(1f, block.height))
            val berikut = floor(finalSize * faktor.coerceIn(0.5f, 0.97f)).toInt()
            finalSize = max(FONT_DARURAT, min(berikut, finalSize - 1))
            paint = makePaint(tf, finalSize.toFloat(), tebal, jarak)
            spacing = max(1f, finalSize * setting.spacingRatio)
            lines = wrapText(text, paint, maxW)
            block = measureBlock(lines, paint, spacing)
            // Pemenggalan paksa harus diulang tiap putaran: wrapText di atas
            // mengembalikan kata utuh lagi, jadi tanpa ini lebarnya tidak
            // pernah menyusut dan perulangan berjalan sampai lantai darurat.
            if (block.width > maxW) {
                val dipenggal = pecahPaksa(text, paint, maxW)
                val blokPenggal = measureBlock(dipenggal, paint, spacing)
                if (blokPenggal.width < block.width) {
                    lines = dipenggal
                    block = blokPenggal
                }
            }
        }

        val centerX = x1 + boxW / 2f
        // Penengahan tegak memakai batas tinta, bukan metrik huruf.
        //
        // Metrik font menyediakan ruang di atas untuk aksen (A dengan topi)
        // dan di bawah untuk ekor huruf (g, y). Teks komik hampir selalu
        // HURUF BESAR tanpa keduanya, jadi menengahkan memakai ascent/descent
        // menyisakan ruang kosong yang tidak seimbang - pada Komika 60px
        // ruang atas 29px lawan bawah 13px, teks tampak melorot ~8px.
        // Pelettering profesional menengahkan siluet huruf yang benar-benar
        // terlihat, dan itu yang dilakukan di sini.
        val startY = startYTinta(lines, paint, spacing, block, y1, boxH)

        // Garis luar mengikuti berat huruf asli: huruf tebal perlu garis lebih
        // tegas, huruf tipis perlu yang lebih halus supaya tidak tertelan.
        val strokeW = if (gaya.terukur || gaya.dikunci) {
            Typography.lebarGarisLuar(finalSize, gaya.berat)
        } else max(1f, finalSize / 11f)

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

        val strokePaint = makePaint(tf, finalSize.toFloat(), tebal, jarak).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 2f
            strokeJoin = Paint.Join.ROUND
            // Garis luar terukur kalau balon aslinya memang punya; kalau tidak,
            // warna latar seperti sebelumnya.
            color = colors.garisLuar ?: colors.background
            textAlign = Paint.Align.CENTER
            // Bayangan dipasang pada goresan luar, bukan pada isi: goresan
            // adalah bentuk terluar huruf, jadi bayangannya melingkupi seluruh
            // siluet. Kalau dipasang pada isi, bayangan jatuh DI BAWAH garis
            // luar dan nyaris tak terlihat.
            if (bayangan) {
                val radius = max(1.5f, finalSize / 8f)
                val geser = max(1f, finalSize / 14f)
                setShadowLayer(radius, geser, geser, colors.foreground)
            }
        }
        val fillPaint = makePaint(tf, finalSize.toFloat(), tebal, jarak).apply {
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
     * Menimpa isi balon mengikuti bentuk aslinya, bukan persegi membulat.
     *
     * Mengembalikan true bila kontur berhasil dipakai. False berarti pemanggil
     * harus kembali ke persegi membulat — kotak terlalu kecil, tidak ada garis
     * tepi yang mengurung (teks bebas tanpa balon), atau isi banjir bocor.
     *
     * Alfa kontur dipakai sebagai peluruhan tepi, jadi transisinya halus tanpa
     * BlurMaskFilter. Piksel yang alfanya nol tidak disentuh sama sekali —
     * itulah inti perbaikannya: artwork di sudut kotak tetap utuh.
     */
    internal fun gambarKontur(
        canvas: Canvas, bmp: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        warnaLatar: Int
    ): Boolean {
        val cx1 = x1.coerceIn(0, bmp.width)
        val cy1 = y1.coerceIn(0, bmp.height)
        val cx2 = x2.coerceIn(0, bmp.width)
        val cy2 = y2.coerceIn(0, bmp.height)
        val w = cx2 - cx1
        val h = cy2 - cy1
        if (w < BubbleContour.MIN_SISI || h < BubbleContour.MIN_SISI) return false

        val piksel = IntArray(w * h)
        bmp.getPixels(piksel, 0, w, cx1, cy1, w, h)

        val hasil = BubbleContour.hitung(piksel, w, h)
        if (!hasil.sah) return false

        // Bangun potongan berwarna latar dengan alfa kontur, lalu tempelkan.
        val isi = IntArray(w * h)
        val r = Color.red(warnaLatar)
        val g = Color.green(warnaLatar)
        val b = Color.blue(warnaLatar)
        for (i in isi.indices) {
            val a = (hasil.alfa[i] * 255f).toInt().coerceIn(0, 255)
            isi[i] = Color.argb(a, r, g, b)
        }
        val lapis = Bitmap.createBitmap(isi, w, h, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(lapis, cx1.toFloat(), cy1.toFloat(), null)
        lapis.recycle()
        return true
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

        // Sama seperti jalur mendatar: pencarian biner, hasil identik.
        val bestFontSize = Typography.cariUkuran(setting.minFont, setting.maxFont) { size ->
            val charsPerCol = max(1, (maxH / size).toInt())
            text.chunked(charsPerCol).size * size <= maxW
        }
        var bestColumns: List<String> = run {
            val charsPerCol = max(1, (maxH / bestFontSize).toInt())
            text.chunked(charsPerCol)
        }
        if (bestColumns.isEmpty()) {
            val charsPerCol = max(1, (maxH / setting.minFont).toInt())
            bestColumns = text.chunked(charsPerCol)
        }

        val fontSize = max(setting.minFont, (bestFontSize * setting.fontScale).toInt())
        // Jalur vertikal juga lewat fontFor: teks vertikal Korea/Mandarin
        // butuh paket tambahan yang sama, bukan hanya kosugi.
        val tf = fontFor(text)

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
            color = colors.garisLuar ?: colors.background
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
