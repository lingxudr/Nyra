package com.nyra.comic

import kotlin.math.max
import kotlin.math.min

/**
 * Penambal salin-cermin: mengisi lubang dengan memantulkan piksel nyata dari
 * kedua sisinya.
 *
 * Kenapa ini ada, padahal [TambalLokal] sudah punya Telea? Karena diukur, dan
 * Telea kalah. Pengukuran memakai dua halaman manga asli. Pada tiap halaman
 * lubang dijatuhkan dalam tiga bentuk (pita lebar seperti watermark, kotak,
 * dan bilah tinggi) di tiga posisi berbeda, isinya benar-benar dihapus, lalu
 * hasil tambalan dibandingkan dengan piksel asli yang sudah diketahui.
 * Angkanya, persentase piksel yang pulih dengan selisih kanal <= 32:
 *
 * ```
 *   bentuk lubang   cermin tegak   cermin datar   sumbu pendek   difusi
 *   pita lebar          73,2 %         64,2 %         73,2 %      ~53 %
 *   kotak               59,6 %         66,6 %         66,6 %
 *   bilah tinggi        52,1 %         74,1 %         74,1 %
 *   ------------------------------------------------------------------
 *   rata-rata           61,6 %         68,3 %         71,3 %      53,4 %
 * ```
 *
 * Difusi kalah karena mengisi dengan rata-rata berbobot tetangga, jadi
 * hasilnya selalu mulus. Manga justru hampir seluruhnya frekuensi tinggi -
 * garis tinta tegas, hatching, screentone - sehingga "mulus" berarti bercak
 * kabur yang lebih mencolok daripada watermark aslinya. Menyalin tekstur nyata
 * dari sebelah lubang mempertahankan garis dan raster itu.
 *
 * Pemilihan sumbu memakai aturan paling sederhana yang menang: **pantulkan
 * menyeberangi sisi pendek lubang**. Alasannya geometris, bukan tebakan -
 * jarak tempuh pantulan jadi sekecil mungkin, sehingga piksel sumber diambil
 * dari dekat lubang dan tekstur belum sempat berubah. Aturan ini meraih 71,3 %
 * sementara oracle (selalu memilih sumbu terbaik secara curang) hanya 72,0 %,
 * jadi nyaris tidak ada lagi yang bisa didapat dari pemilihan sumbu.
 *
 * Cermin tidak selalu dipakai. Ia menyerah kepada difusi pada dua keadaan
 * yang sudah terbukti merugikan lewat pengujian: daerah mulus, karena
 * memantulkan gradasi akan melipatnya sehingga ramp berbalik arah (lihat
 * [AMBANG_TEKSTUR]), dan lubang yang tidak punya margin bersih di kedua sisi.
 *
 * Percobaan yang sudah diukur dan kalah, dicatat supaya tidak diulang:
 * pemilihan sumbu lewat cincin uji 6 piksel (64,3 % - lebih buruk daripada
 * selalu-tegak, cincin ternyata bukan penanda arah tekstur), interpolasi tepi,
 * campuran cermin+difusi, dan patch-match berbasis konteks.
 *
 * Batasnya jujur: cermin mengarang tekstur, bukan memahami gambar. Bila
 * watermark menimpa wajah atau objek unik, hasilnya akan salah secara isi
 * walau mulus secara tekstur. Untuk kasus itu LaMa tetap pilihan yang lebih
 * baik, dan [HapusWatermark] memang mendahulukannya bila sudah terpasang.
 */
object TambalCermin {

    /** Piksel bersih minimum yang harus tersedia di sisi lubang. */
    const val MARGIN_MIN = 2

    /** Tebal pita bersih yang dibaca untuk mengukur tekstur, dalam piksel. */
    const val PITA = 8

    /**
     * Ambang energi Laplace: di bawah ini daerah dianggap mulus dan cermin
     * menyerah kepada difusi.
     *
     * Cermin unggul pada tekstur, tetapi kalah telak pada gradasi halus -
     * memantulkan sebuah ramp akan melipatnya, sehingga sisi yang seharusnya
     * terus menanjak malah berbalik turun. Difusi justru sempurna di sana
     * karena memang menginterpolasi. Diukur: gradasi linear dan latar polos
     * menghasilkan energi 0,00, sedangkan pita di sekitar watermark pada
     * halaman manga nyata menghasilkan 12,2 sampai 101,0. Ambang 4,0 duduk di
     * jurang lebar antara keduanya.
     */
    const val AMBANG_TEKSTUR = 4.0f

    private const val TEGAK = 0
    private const val DATAR = 1

    /**
     * Menambal setiap kotak di [boxes] langsung pada [pix].
     *
     * Semua kotak membaca dari satu salinan bidikan gambar asli, sehingga
     * hasil tambalan satu kotak tidak pernah menjadi sumber bagi kotak
     * berikutnya - kalau tidak, kesalahan akan berlipat antar kotak.
     *
     * @param paksa bila true, lubang tetap ditambal walau margin bersih di
     *   kedua sisi kurang; piksel sumber dijepit ke tepi gambar. Dipakai saat
     *   pemanggil tidak punya cadangan lain.
     * @return jumlah piksel yang benar-benar diisi. Nilai 0 berarti tidak ada
     *   kotak yang bisa ditangani, dan pemanggil sebaiknya memakai difusi.
     */
    fun tambal(
        pix: IntArray,
        w: Int,
        h: Int,
        boxes: List<IntArray>,
        paksa: Boolean = false,
    ): Int {
        if (w <= 0 || h <= 0 || pix.size < w * h || boxes.isEmpty()) return 0
        val sumber = pix.copyOf()

        // Peta lubang mencakup SEMUA kotak sekaligus. Tanpa ini, dua kotak
        // yang bersebelahan akan saling menyalin isi rusak masing-masing:
        // pantulan kotak pertama mendarat tepat di dalam kotak kedua yang
        // belum ditambal, sehingga watermark tersalin, bukan terhapus.
        val lubang = BooleanArray(w * h)
        val kotakTerpakai = ArrayList<IntArray>(boxes.size)
        for (box in boxes) {
            val pad = InpaintMath.maskPad(box)
            val d = InpaintMath.dilate(box, pad, w, h)
            val x1 = max(0, d[0]); val y1 = max(0, d[1])
            val x2 = min(w, d[2]); val y2 = min(h, d[3])
            if (x2 - x1 <= 0 || y2 - y1 <= 0) continue
            kotakTerpakai.add(intArrayOf(x1, y1, x2, y2))
            for (y in y1 until y2) {
                val baris = y * w
                for (x in x1 until x2) lubang[baris + x] = true
            }
        }
        if (kotakTerpakai.isEmpty()) return 0

        var terisi = 0
        for (k in kotakTerpakai) {
            val x1 = k[0]; val y1 = k[1]; val x2 = k[2]; val y2 = k[3]
            if (!cukupTekstur(sumber, lubang, w, h, x1, y1, x2, y2)) continue
            val sumbu = pilihSumbu(x1, y1, x2, y2, w, h, paksa) ?: continue
            val n = if (sumbu == TEGAK) {
                pantulTegak(pix, sumber, lubang, w, h, x1, y1, x2, y2)
            } else {
                pantulDatar(pix, sumber, lubang, w, h, x1, y1, x2, y2)
            }
            if (n < 0) {
                // Ada piksel yang tidak menemukan sumber bersih sama sekali.
                // Menambal sebagian lebih berbahaya daripada tidak menambal:
                // pemanggil akan mengira lubang sudah tertutup dan tidak
                // memanggil difusi. Kembalikan kotak ini ke keadaan semula.
                for (y in y1 until y2) {
                    val baris = y * w
                    for (x in x1 until x2) pix[baris + x] = sumber[baris + x]
                }
                continue
            }
            terisi += n
        }
        return terisi
    }

    /**
     * Mengukur energi Laplace pada pita bersih di sekeliling lubang.
     *
     * Piksel yang termasuk lubang lain dilewati, supaya watermark tetangga
     * tidak terhitung sebagai "tekstur".
     */
    private fun cukupTekstur(
        sumber: IntArray, lubang: BooleanArray, w: Int, h: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
    ): Boolean {
        var jumlah = 0.0
        var n = 0
        fun pita(ax1: Int, ay1: Int, ax2: Int, ay2: Int) {
            for (y in max(1, ay1) until min(h - 1, ay2)) {
                for (x in max(1, ax1) until min(w - 1, ax2)) {
                    val i = y * w + x
                    if (lubang[i] || lubang[i - w] || lubang[i + w] ||
                        lubang[i - 1] || lubang[i + 1]
                    ) continue
                    val l = 4 * abu(sumber[i]) - abu(sumber[i - w]) -
                        abu(sumber[i + w]) - abu(sumber[i - 1]) - abu(sumber[i + 1])
                    jumlah += if (l < 0) -l.toDouble() else l.toDouble()
                    n++
                }
            }
        }
        pita(x1, y1 - PITA, x2, y1)
        pita(x1, y2, x2, y2 + PITA)
        pita(x1 - PITA, y1, x1, y2)
        pita(x2, y1, x2 + PITA, y2)
        if (n == 0) return false
        return jumlah / n >= AMBANG_TEKSTUR
    }

    /** Keabuan cepat tanpa `android.graphics.Color`, agar bisa diuji di JVM. */
    private fun abu(c: Int): Int =
        ((c ushr 16 and 0xFF) * 77 + (c ushr 8 and 0xFF) * 151 + (c and 0xFF) * 28) shr 8

    /**
     * Memilih sumbu pantul: menyeberangi sisi pendek lubang, karena itu
     * membuat jarak tempuh pantulan sekecil mungkin.
     *
     * Mengembalikan null bila sisi yang dipilih tidak punya piksel bersih yang
     * cukup dan [paksa] mati - pemanggil lalu jatuh ke difusi.
     */
    private fun pilihSumbu(
        x1: Int, y1: Int, x2: Int, y2: Int,
        w: Int, h: Int, paksa: Boolean,
    ): Int? {
        val tinggi = y2 - y1
        val lebar = x2 - x1
        val utama = if (tinggi <= lebar) TEGAK else DATAR
        if (paksa) return utama
        val cadangan = if (utama == TEGAK) DATAR else TEGAK
        if (cukupRuang(utama, x1, y1, x2, y2, w, h)) return utama
        if (cukupRuang(cadangan, x1, y1, x2, y2, w, h)) return cadangan
        return null
    }

    /** Apakah kedua sisi pada [sumbu] menyisakan piksel bersih yang cukup. */
    private fun cukupRuang(
        sumbu: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        w: Int, h: Int,
    ): Boolean = if (sumbu == TEGAK) {
        y1 >= MARGIN_MIN && h - y2 >= MARGIN_MIN
    } else {
        x1 >= MARGIN_MIN && w - x2 >= MARGIN_MIN
    }

    /**
     * Memantulkan menyeberangi sisi atas dan bawah.
     *
     * Separuh baris teratas mengambil cerminan dari pita di ATAS lubang, dan
     * separuh sisanya dari pita di BAWAH lubang. Indeks sumber selalu jatuh di
     * luar lubang - inilah yang dulu salah dan membuat lubang menyalin dirinya
     * sendiri.
     */
    private fun pantulTegak(
        pix: IntArray, sumber: IntArray, lubang: BooleanArray, w: Int, h: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
    ): Int {
        val tinggi = y2 - y1
        val separuh = tinggi / 2
        var n = 0
        for (i in 0 until tinggi) {
            val ke = if (i < separuh) -1 else 1
            val mulai = if (i < separuh) y1 - 1 - i else y2 + (tinggi - 1 - i)
            val barisTujuan = (y1 + i) * w
            for (x in x1 until x2) {
                val sy = cariBersihY(lubang, w, h, x, mulai, ke)
                if (sy < 0) return -1
                pix[barisTujuan + x] = sumber[sy * w + x]
                n++
            }
        }
        return n
    }

    /** Memantulkan menyeberangi sisi kiri dan kanan; cerminan dari [pantulTegak]. */
    private fun pantulDatar(
        pix: IntArray, sumber: IntArray, lubang: BooleanArray, w: Int, h: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
    ): Int {
        val lebar = x2 - x1
        val separuh = lebar / 2
        var n = 0
        for (j in 0 until lebar) {
            val ke = if (j < separuh) -1 else 1
            val mulai = if (j < separuh) x1 - 1 - j else x2 + (lebar - 1 - j)
            val tx = x1 + j
            for (y in y1 until y2) {
                val sx = cariBersihX(lubang, w, y, mulai, ke)
                if (sx < 0) return -1
                pix[y * w + tx] = sumber[y * w + sx]
                n++
            }
        }
        return n
    }

    /**
     * Mencari piksel bersih pada kolom [x] mulai dari baris [mulai], melangkah
     * ke arah [ke], lalu berbalik bila mentok tepi.
     *
     * Diperlukan karena kotak lain bisa menghalangi titik pantul. Menyalin
     * piksel yang masih bertuliskan watermark akan memindahkan watermark, dan
     * itu lebih buruk daripada tidak menambal. Mengembalikan -1 bila seluruh
     * kolom adalah lubang.
     */
    private fun cariBersihY(
        lubang: BooleanArray, w: Int, h: Int, x: Int, mulai: Int, ke: Int,
    ): Int {
        var y = mulai
        while (y in 0 until h) {
            if (!lubang[y * w + x]) return y
            y += ke
        }
        y = mulai - ke
        while (y in 0 until h) {
            if (!lubang[y * w + x]) return y
            y -= ke
        }
        return -1
    }

    /** Sepadan dengan [cariBersihY], tetapi menyusuri baris [y]. */
    private fun cariBersihX(
        lubang: BooleanArray, w: Int, y: Int, mulai: Int, ke: Int,
    ): Int {
        val baris = y * w
        var x = mulai
        while (x in 0 until w) {
            if (!lubang[baris + x]) return x
            x += ke
        }
        x = mulai - ke
        while (x in 0 until w) {
            if (!lubang[baris + x]) return x
            x -= ke
        }
        return -1
    }
}
