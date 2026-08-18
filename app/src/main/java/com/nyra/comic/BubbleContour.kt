package com.nyra.comic

import kotlin.math.max
import kotlin.math.min

/**
 * Mencari bentuk balon yang sebenarnya di dalam sebuah kotak deteksi.
 *
 * Latar masalahnya (ronde 24): RT-DETR mengembalikan kotak persegi, dan
 * TextRenderer menutup kotak itu dengan persegi-panjang-membulat berwarna
 * latar. Untuk balon oval, balon miring, balon berekor, atau balon ledakan,
 * sudut kotak itu BUKAN bagian balon — jadi kita mengecat artwork di
 * sekelilingnya. Pembanding jedzqer menyelesaikan ini dengan model segmentasi
 * YOLO-seg yang mengembalikan kontur, tetapi model semacam itu praktis selalu
 * berlisensi AGPL dan akan menular ke APK yang kita bagikan.
 *
 * Jalan yang dipakai di sini tidak butuh model baru sama sekali. Kuncinya satu
 * pengamatan: balon manga hampir selalu berupa daerah rata yang DIKELILINGI
 * garis tepi gelap. Artinya isi banjir (flood fill) yang berangkat dari tengah
 * kotak akan berhenti sendiri di garis tepi itu dan tidak pernah bocor ke
 * halaman — walaupun warna halaman sama putihnya dengan isi balon.
 *
 * Urutannya:
 *   1. ambang Otsu atas potongan kotak,
 *   2. tentukan polaritas dari inti tengah (balon putih vs balon hitam),
 *   3. isi banjir 4-arah dari inti,
 *   4. tutup lubang — huruf di dalam balon ikut jadi bagian balon,
 *      kalau tidak, teks asli akan menyembul lewat lubangnya,
 *   5. kikis beberapa piksel supaya garis tepi balon tetap utuh,
 *   6. haluskan tepi jadi alfa 0..1 supaya tidak bergerigi.
 *
 * Kalau hasilnya tidak masuk akal — terlalu kecil, memenuhi hampir seluruh
 * kotak, atau menyentuh terlalu banyak tepi (tanda garis tepinya terbuka dan
 * isi banjir bocor ke halaman) — [Hasil.sah] bernilai false dan pemanggil
 * wajib kembali memakai persegi membulat seperti sebelumnya. Teks bebas tanpa
 * balon (`text_free`) memang selalu tertolak lewat jalur ini, dan itu benar.
 */
object BubbleContour {

    /** Sisi terpendek minimum sebuah kotak supaya layak dianalisis. */
    const val MIN_SISI = 16

    /** Mask harus menutupi minimal sekian bagian kotak. */
    const val MIN_FRAKSI = 0.18f

    /** ...dan tidak boleh menutupi hampir seluruhnya (tanda tak ada tepi). */
    const val MAKS_FRAKSI = 0.985f

    /** Bila sekian bagian piksel tepi ikut termask, isi banjir dianggap bocor. */
    const val TEPI_BOCOR = 0.55f

    /**
     * Sisi wilayah tengah yang dipakai untuk memilih polaritas isi balon.
     *
     * Sengaja lebar (50%), bukan kotak kecil di titik pusat. Sebabnya nyata:
     * teks balon justru terletak di tengah, jadi kotak inti yang sempit
     * gampang tertutup satu huruf dan polaritasnya terbaca terbalik — balon
     * putih disangka balon hitam, lalu isi banjir menapaki garis tepi dan
     * bocor ke seluruh halaman. Dengan wilayah selebar ini, isi balon selalu
     * menang suara atas guratan hurufnya.
     */
    const val INTI = 0.5f

    /** Sisi kotak benih isi banjir, relatif terhadap kotak. */
    const val BENIH = 0.18f

    /** Kikisan bawaan (piksel) supaya garis tepi balon tidak ikut tertimpa. */
    const val KIKIS = 2

    /** Radius penghalusan tepi bawaan (piksel). */
    const val BULU = 2

    data class Hasil(
        val lebar: Int,
        val tinggi: Int,
        /** Alfa 0..1 per piksel, ukuran lebar*tinggi. Kosong bila tidak sah. */
        val alfa: FloatArray,
        /** Bagian kotak yang tertutup mask, sebelum dikikis. */
        val fraksi: Float,
        val sah: Boolean,
        val alasan: String
    ) {
        // FloatArray bikin equals/hashCode bawaan data class menyesatkan.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private fun tolak(alasan: String) =
        Hasil(0, 0, FloatArray(0), 0f, false, alasan)

    /**
     * Luminansi 0..255, rumus sama dengan [Palette.luminance].
     *
     * Kanal dibongkar dengan geser-bit, bukan lewat android.graphics.Color.
     * Alasannya konkret: di unit test JVM biasa seluruh metode Color adalah
     * stub yang mengembalikan 0, sehingga setiap piksel menjadi abu-abu
     * seragam dan seluruh pengujian kontur berubah jadi omong kosong yang
     * lulus atau gagal tanpa makna. Aritmetika murni membuat kelas ini bisa
     * diuji sungguhan tanpa Robolectric.
     */
    internal fun abuAbu(piksel: IntArray): IntArray {
        val out = IntArray(piksel.size)
        for (i in piksel.indices) {
            val c = piksel[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Ambang Otsu klasik: memaksimalkan varians antar-kelas.
     * Mengembalikan 0..255.
     */
    internal fun otsu(abu: IntArray): Int {
        val hist = IntArray(256)
        for (v in abu) hist[v]++
        val total = abu.size
        if (total == 0) return 128

        var jumlahSemua = 0.0
        for (t in 0..255) jumlahSemua += t.toDouble() * hist[t]

        var jumlahBelakang = 0.0
        var beratBelakang = 0
        var terbaik = 0.0
        var ambang = 128

        for (t in 0..255) {
            beratBelakang += hist[t]
            if (beratBelakang == 0) continue
            val beratDepan = total - beratBelakang
            if (beratDepan == 0) break

            jumlahBelakang += t.toDouble() * hist[t]
            val rataBelakang = jumlahBelakang / beratBelakang
            val rataDepan = (jumlahSemua - jumlahBelakang) / beratDepan
            val selisih = rataBelakang - rataDepan
            val varians = beratBelakang.toDouble() * beratDepan * selisih * selisih
            if (varians > terbaik) {
                terbaik = varians
                ambang = t
            }
        }
        return ambang
    }

    /**
     * Menentukan apakah isi balon lebih terang daripada ambang, dilihat dari
     * kotak inti di tengah. Balon hitam berteks putih menghasilkan false.
     */
    internal fun intiTerang(abu: IntArray, w: Int, h: Int, ambang: Int): Boolean {
        val iw = max(1, (w * INTI).toInt())
        val ih = max(1, (h * INTI).toInt())
        val x0 = (w - iw) / 2
        val y0 = (h - ih) / 2
        var terang = 0
        var gelap = 0
        for (y in y0 until min(h, y0 + ih)) {
            for (x in x0 until min(w, x0 + iw)) {
                if (abu[y * w + x] > ambang) terang++ else gelap++
            }
        }
        return terang >= gelap
    }

    /**
     * Isi banjir 4-arah dari kotak inti, hanya menapaki piksel yang polaritas
     * terang/gelapnya sama dengan inti. Garis tepi balon yang berlawanan
     * polaritas otomatis menjadi dinding.
     */
    internal fun isiBanjir(
        abu: IntArray, w: Int, h: Int, ambang: Int, terang: Boolean
    ): BooleanArray {
        val mask = BooleanArray(w * h)
        val cocok = { i: Int -> if (terang) abu[i] > ambang else abu[i] <= ambang }

        val antre = IntArray(w * h)
        var kepala = 0
        var ekor = 0

        // Benih diambil dari seluruh piksel berpolaritas isi di wilayah tengah,
        // bukan dari satu kotak kecil. Kotak kecil bisa jatuh tepat di atas
        // sebuah huruf dan tidak menghasilkan benih sama sekali.
        val iw = max(1, (w * BENIH).toInt())
        val ih = max(1, (h * BENIH).toInt())
        var x0 = (w - iw) / 2
        var y0 = (h - ih) / 2
        var adaBenih = false
        for (y in y0 until min(h, y0 + ih)) {
            for (x in x0 until min(w, x0 + iw)) {
                val i = y * w + x
                if (!mask[i] && cocok(i)) {
                    mask[i] = true
                    antre[ekor++] = i
                    adaBenih = true
                }
            }
        }
        if (!adaBenih) {
            // Kotak benih tertutup huruf seluruhnya: perlebar ke wilayah tengah.
            val lw = max(1, (w * INTI).toInt())
            val lh = max(1, (h * INTI).toInt())
            x0 = (w - lw) / 2
            y0 = (h - lh) / 2
            for (y in y0 until min(h, y0 + lh)) {
                for (x in x0 until min(w, x0 + lw)) {
                    val i = y * w + x
                    if (!mask[i] && cocok(i)) {
                        mask[i] = true
                        antre[ekor++] = i
                    }
                }
            }
        }

        while (kepala < ekor) {
            val i = antre[kepala++]
            val x = i % w
            val y = i / w
            if (x > 0) { val j = i - 1; if (!mask[j] && cocok(j)) { mask[j] = true; antre[ekor++] = j } }
            if (x < w - 1) { val j = i + 1; if (!mask[j] && cocok(j)) { mask[j] = true; antre[ekor++] = j } }
            if (y > 0) { val j = i - w; if (!mask[j] && cocok(j)) { mask[j] = true; antre[ekor++] = j } }
            if (y < h - 1) { val j = i + w; if (!mask[j] && cocok(j)) { mask[j] = true; antre[ekor++] = j } }
        }
        return mask
    }

    /**
     * Menutup lubang di dalam mask.
     *
     * Huruf di dalam balon berlawanan polaritas dengan isinya, jadi isi banjir
     * melompatinya dan meninggalkan lubang berbentuk huruf. Kalau dibiarkan,
     * teks ASLI akan tetap terlihat menyembul lewat lubang itu setelah balon
     * ditimpa warna latar — persis bug yang ingin kita hindari.
     *
     * Caranya: banjiri komplemen mask dari tepi potongan. Komplemen yang tidak
     * terjangkau berarti terkurung di dalam balon, jadi ia lubang.
     */
    internal fun tutupLubang(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val luar = BooleanArray(w * h)
        val antre = IntArray(w * h)
        var kepala = 0
        var ekor = 0

        fun dorong(i: Int) {
            if (!luar[i] && !mask[i]) { luar[i] = true; antre[ekor++] = i }
        }
        for (x in 0 until w) { dorong(x); dorong((h - 1) * w + x) }
        for (y in 0 until h) { dorong(y * w); dorong(y * w + w - 1) }

        while (kepala < ekor) {
            val i = antre[kepala++]
            val x = i % w
            val y = i / w
            if (x > 0) dorong(i - 1)
            if (x < w - 1) dorong(i + 1)
            if (y > 0) dorong(i - w)
            if (y < h - 1) dorong(i + w)
        }

        val out = BooleanArray(w * h)
        for (i in out.indices) out[i] = mask[i] || !luar[i]
        return out
    }

    /** Erosi Chebyshev: piksel bertahan hanya bila seluruh tetangga radius r ikut mask. */
    internal fun kikis(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return mask
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                var utuh = true
                var dy = -r
                while (dy <= r && utuh) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) { utuh = false; break }
                    var dx = -r
                    while (dx <= r) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w || !mask[yy * w + xx]) { utuh = false; break }
                        dx++
                    }
                    dy++
                }
                out[i] = utuh
            }
        }
        return out
    }

    /** Fraksi piksel tepi potongan yang ikut termask — indikator kebocoran. */
    internal fun fraksiTepi(mask: BooleanArray, w: Int, h: Int): Float {
        if (w < 2 || h < 2) return 1f
        var kena = 0
        var total = 0
        for (x in 0 until w) {
            if (mask[x]) kena++
            if (mask[(h - 1) * w + x]) kena++
            total += 2
        }
        for (y in 1 until h - 1) {
            if (mask[y * w]) kena++
            if (mask[y * w + w - 1]) kena++
            total += 2
        }
        return if (total == 0) 1f else kena.toFloat() / total
    }

    /**
     * Box blur terpisah (horizontal lalu vertikal) atas mask biner, menghasilkan
     * alfa 0..1. Tanpa ini tepi balon tampak bergerigi seperti tangga.
     */
    internal fun bulu(mask: BooleanArray, w: Int, h: Int, r: Int): FloatArray {
        val a = FloatArray(w * h)
        for (i in a.indices) a[i] = if (mask[i]) 1f else 0f
        if (r <= 0) return a

        val lebarJendela = (2 * r + 1).toFloat()
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var jum = 0f
                for (dx in -r..r) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    jum += a[y * w + xx]
                }
                tmp[y * w + x] = jum / lebarJendela
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var jum = 0f
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    jum += tmp[yy * w + x]
                }
                out[y * w + x] = jum / lebarJendela
            }
        }
        return out
    }

    /**
     * Menghitung kontur balon dari potongan piksel ARGB berukuran [w]x[h].
     *
     * [piksel] harus berisi tepat kotak balon yang sudah dipotong dari halaman.
     */
    fun hitung(
        piksel: IntArray,
        w: Int,
        h: Int,
        kikisR: Int = KIKIS,
        buluR: Int = BULU
    ): Hasil {
        if (w <= 0 || h <= 0 || piksel.size < w * h) return tolak("ukuran tidak sah")
        if (min(w, h) < MIN_SISI) return tolak("kotak terlalu kecil")

        val abu = abuAbu(piksel)
        val ambang = otsu(abu)
        val terang = intiTerang(abu, w, h, ambang)

        val mentah = isiBanjir(abu, w, h, ambang, terang)
        val penuh = tutupLubang(mentah, w, h)

        var kena = 0
        for (b in penuh) if (b) kena++
        val fraksi = kena.toFloat() / (w * h)

        if (fraksi < MIN_FRAKSI) return tolak("mask terlalu kecil: $fraksi")
        if (fraksi > MAKS_FRAKSI) return tolak("mask hampir penuh: $fraksi")

        val tepi = fraksiTepi(penuh, w, h)
        if (tepi >= TEPI_BOCOR) return tolak("isi banjir bocor ke tepi: $tepi")

        val terkikis = kikis(penuh, w, h, kikisR)
        var sisa = 0
        for (b in terkikis) if (b) sisa++
        if (sisa == 0) return tolak("habis setelah dikikis")

        return Hasil(w, h, bulu(terkikis, w, h, buluR), fraksi, true, "ok")
    }
}
