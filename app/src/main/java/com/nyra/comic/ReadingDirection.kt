package com.nyra.comic

/**
 * Menebak arah baca satu halaman dari tata letaknya.
 *
 * MASALAH YANG DIPERBAIKI: arah baca dulu hanya sebuah sakelar manual dengan
 * bawaan kanan-ke-kiri (manga Jepang). Untuk manhwa Korea, manhua, dan komik
 * Barat - yang dibaca kiri-ke-kanan - bawaan itu salah, dan salahnya diam-diam:
 * hasilnya tetap terlihat "diterjemahkan", tapi setiap halaman yang punya dua
 * balon sebaris terbaca terbalik. Lebih buruk lagi, urutan itu menentukan
 * penomoran ID merah pada mosaik, jadi model menerima percakapan dalam urutan
 * kacau dan menerjemahkan dengan konteks yang keliru.
 *
 * ISYARAT YANG DIPAKAI (semuanya terukur dari kotak, tanpa perlu OCR teks):
 *
 *  1. STRIP WEBTOON. Halaman dengan tinggi >= [STRIP_RASIO] x lebar adalah
 *     gulungan vertikal. Format itu praktis selalu Korea/Cina modern dan
 *     dibaca kiri-ke-kanan. Isyarat negatif (ke arah LTR) yang kuat.
 *
 *  2. TULISAN TEGAK (tategaki). Balon manga Jepang menata teks dalam kolom
 *     vertikal, sehingga blok teks di dalam balon jauh lebih tinggi daripada
 *     lebar. Teks mendatar - Korea, Cina modern, Inggris - menghasilkan blok
 *     yang lebih lebar daripada tinggi. Ini isyarat paling kuat yang tersedia:
 *     ia mengukur sistem tulisannya, bukan menebak dari format berkas.
 *
 *  3. TEKS MENDATAR YANG DOMINAN. Bila hampir tidak ada blok tegak sama sekali
 *     pada sampel yang cukup besar, halaman itu hampir pasti bukan manga
 *     Jepang bergaya klasik. Isyarat ini lebih lemah daripada (2), karena
 *     manga modern juga memakai balon mendatar untuk narasi pendek.
 *
 * Skor positif berarti kanan-ke-kiri, negatif berarti kiri-ke-kanan. Bila skor
 * berada di dalam pita mati [AMBANG], bukti dianggap tidak cukup dan pemanggil
 * memakai nilai cadangannya sendiri - bukan menebak.
 *
 * Semua fungsi di sini murni aritmetika kotak, jadi bisa diuji tanpa Android.
 */
object ReadingDirection {

    /** Tinggi >= rasio ini x lebar dianggap strip webtoon. */
    const val STRIP_RASIO = 2.5f

    /** Blok teks dengan tinggi >= rasio ini x lebar dihitung sebagai tegak. */
    const val TEGAK_RASIO = 1.6f

    /** Jumlah blok teks minimum sebelum isyarat tulisan boleh dipercaya. */
    const val MIN_BLOK = 4

    /** Fraksi blok tegak yang sudah dianggap bukti penuh tategaki. */
    const val FRAKSI_TEGAK_PENUH = 0.45f

    /** Di bawah fraksi ini, halaman dianggap bertulisan mendatar. */
    const val FRAKSI_TEGAK_RENDAH = 0.15f

    /** Blok teks mendatar butuh sampel lebih besar agar isyaratnya dipakai. */
    const val MIN_BLOK_MENDATAR = 6

    const val BOBOT_STRIP = -2.0f
    const val BOBOT_TEGAK = 2.5f
    const val BOBOT_MENDATAR = -1.2f

    /** Skor dengan |nilai| di bawah ini dianggap tidak menentukan. */
    const val AMBANG = 0.5f

    /**
     * @param kananKeKiri keputusan akhir (sudah memasukkan [cadangan]).
     * @param skor bukti mentah; positif = RTL, negatif = LTR.
     * @param yakin false bila keputusan sebenarnya berasal dari [cadangan].
     * @param alasan teks singkat untuk konsol, supaya pengguna bisa menilai.
     */
    data class Bukti(
        val kananKeKiri: Boolean,
        val skor: Float,
        val yakin: Boolean,
        val alasan: String
    )

    /**
     * Fraksi blok teks yang tertulis tegak.
     *
     * Blok berukuran nol dilewati: kotak cacat semacam itu akan menghasilkan
     * pembagian dengan nol dan bukan bukti apa pun.
     */
    fun fraksiTegak(blokTeks: List<IntArray>): Float {
        var sah = 0
        var tegak = 0
        for (b in blokTeks) {
            val w = b[2] - b[0]
            val h = b[3] - b[1]
            if (w <= 0 || h <= 0) continue
            sah++
            if (h >= TEGAK_RASIO * w) tegak++
        }
        if (sah == 0) return 0f
        return tegak.toFloat() / sah.toFloat()
    }

    /** Jumlah blok teks berukuran sah dalam [blokTeks]. */
    fun blokSah(blokTeks: List<IntArray>): Int =
        blokTeks.count { it[2] - it[0] > 0 && it[3] - it[1] > 0 }

    /** True bila halaman berbentuk strip gulung vertikal. */
    fun strip(lebar: Int, tinggi: Int): Boolean =
        lebar > 0 && tinggi >= STRIP_RASIO * lebar

    /**
     * True bila urutan mendatar benar-benar berpengaruh pada halaman ini.
     *
     * Kalau tidak ada dua balon pun yang sebaris, urutan baca sepenuhnya
     * ditentukan oleh koordinat y dan arah mendatar tidak mengubah apa pun.
     * Memakai ini menghindari mencatat "arah terdeteksi" pada halaman yang
     * sebetulnya tidak memberi informasi.
     */
    fun arahBerpengaruh(balon: List<IntArray>): Boolean {
        for (i in balon.indices) {
            for (j in i + 1 until balon.size) {
                val a = balon[i]
                val b = balon[j]
                val ov = minOf(a[3], b[3]) - maxOf(a[1], b[1])
                if (ov <= 0) continue
                val pendek = minOf(a[3] - a[1], b[3] - b[1])
                if (pendek <= 0) continue
                if (ov >= BoxUtils.OVERLAP_BARIS * pendek) return true
            }
        }
        return false
    }

    /**
     * Kumpulkan bukti untuk satu halaman.
     *
     * @param blokTeks kotak blok teks di dalam balon (kelas text_bubble dari
     *   RT-DETR, atau kotak baris dari detektor teks). Boleh kosong.
     * @param cadangan nilai yang dipakai bila bukti tidak cukup - biasanya
     *   keputusan halaman sebelumnya, atau sakelar manual pada halaman pertama.
     */
    fun putuskan(
        lebarHalaman: Int,
        tinggiHalaman: Int,
        blokTeks: List<IntArray>,
        cadangan: Boolean
    ): Bukti {
        var skor = 0f
        val alasan = ArrayList<String>(3)

        if (strip(lebarHalaman, tinggiHalaman)) {
            skor += BOBOT_STRIP
            alasan.add("strip webtoon")
        }

        val n = blokSah(blokTeks)
        if (n >= MIN_BLOK) {
            val f = fraksiTegak(blokTeks)
            if (f >= FRAKSI_TEGAK_RENDAH) {
                // Bukti tategaki diskalakan: setengah blok tegak sudah cukup
                // meyakinkan, tapi tiga dari sepuluh pun tetap berarti.
                val skala = (f / FRAKSI_TEGAK_PENUH).coerceAtMost(1f)
                val tambah = BOBOT_TEGAK * skala
                if (tambah > 0f) {
                    skor += tambah
                    alasan.add("${(f * 100).toInt()}% blok teks tegak")
                }
            } else if (n >= MIN_BLOK_MENDATAR) {
                skor += BOBOT_MENDATAR
                alasan.add("teks mendatar ($n blok)")
            }
        }

        val yakin = kotlin.math.abs(skor) >= AMBANG
        val arah = if (yakin) skor > 0f else cadangan
        val teks = when {
            alasan.isEmpty() -> "bukti tata letak tidak ada"
            yakin -> alasan.joinToString(", ")
            else -> alasan.joinToString(", ") + " (belum meyakinkan)"
        }
        return Bukti(arah, skor, yakin, teks)
    }

    /** Label pendek untuk konsol. */
    fun label(kananKeKiri: Boolean): String =
        if (kananKeKiri) "kanan-ke-kiri" else "kiri-ke-kanan"
}
