package com.nyra.comic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Penyaring kandidat watermark dari daftar kotak teks hasil detektor OCR.
 *
 * Watermark scanlation punya pola yang bisa diukur, dan itulah yang dipakai di
 * sini alih-alih menebak: teksnya muncul di tempat yang sama pada banyak
 * halaman, biasanya menempel di tepi, dan ukurannya kecil relatif halaman.
 *
 * Kelas ini sengaja hanya memberi **saran**. Menghapus otomatis tanpa
 * konfirmasi berbahaya: kotak yang salah tebak akan menghapus artwork asli,
 * dan pengguna tidak punya cara mengembalikannya. Keputusan akhir selalu di
 * tangan pengguna lewat daftar centang di editor.
 *
 * Aritmetika murni, tanpa `android.graphics` - alasannya sama dengan
 * [TambalLokal]: supaya benar-benar teruji di unit test JVM.
 */
object WatermarkMath {

    /**
     * Seberapa dekat ke tepi halaman sebuah kotak dianggap "di tepi",
     * sebagai pecahan dari sisi halaman.
     */
    const val PITA_TEPI = 0.14f

    /** Watermark hampir selalu kecil; di atas ini kemungkinan besar dialog. */
    const val LUAS_MAKS = 0.045f

    /** Di bawah ini kemungkinan besar bintik pemindaian, bukan teks. */
    const val LUAS_MIN = 0.00004f

    /** Toleransi posisi ketika mencocokkan kotak antar halaman, pecahan sisi. */
    const val TOLERANSI_POSISI = 0.045f

    /**
     * Berapa banyak halaman harus memuat kotak di posisi yang sama sebelum
     * disebut berulang. Dua halaman sudah cukup kuat: watermark memang
     * ditempel di tiap halaman, sedangkan dialog tidak pernah jatuh di
     * koordinat yang persis sama berulang kali.
     */
    const val MIN_ULANG = 2

    /** Skor minimal supaya sebuah kotak layak disarankan. */
    const val AMBANG_SKOR = 0.55f

    /**
     * Satu kandidat watermark.
     *
     * [skor] 0..1, [alasan] adalah kunci string yang diterjemahkan di UI
     * sehingga lapisan ini tetap bebas dari sumber daya Android.
     */
    data class Kandidat(
        val kotak: IntArray,
        val skor: Float,
        val alasan: String,
        val berulang: Int,
    ) {
        // IntArray memakai kesamaan rujukan, jadi equals/hashCode ditulis
        // tangan agar kandidat bisa dibandingkan di dalam pengujian.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Kandidat) return false
            return kotak.contentEquals(other.kotak) &&
                skor == other.skor &&
                alasan == other.alasan &&
                berulang == other.berulang
        }

        override fun hashCode(): Int {
            var h = kotak.contentHashCode()
            h = 31 * h + skor.hashCode()
            h = 31 * h + alasan.hashCode()
            h = 31 * h + berulang
            return h
        }
    }

    /** Pecahan luas halaman yang ditempati [box]. */
    fun rasioLuas(box: IntArray, imgW: Int, imgH: Int): Float {
        if (imgW <= 0 || imgH <= 0) return 0f
        val w = (box[2] - box[0]).coerceAtLeast(0)
        val h = (box[3] - box[1]).coerceAtLeast(0)
        return (w.toFloat() * h) / (imgW.toFloat() * imgH)
    }

    /**
     * Jarak kotak ke tepi halaman terdekat, sebagai pecahan sisi halaman.
     * 0 berarti menempel tepi, 0,5 berarti tepat di tengah.
     */
    fun jarakTepi(box: IntArray, imgW: Int, imgH: Int): Float {
        if (imgW <= 0 || imgH <= 0) return 0.5f
        val kiri = box[0].toFloat() / imgW
        val kanan = (imgW - box[2]).toFloat() / imgW
        val atas = box[1].toFloat() / imgH
        val bawah = (imgH - box[3]).toFloat() / imgH
        return max(0f, min(min(kiri, kanan), min(atas, bawah)))
    }

    /** Benar bila dua kotak menempati posisi yang sama pada halaman berbeda. */
    fun posisiSama(a: IntArray, b: IntArray, imgW: Int, imgH: Int): Boolean {
        if (imgW <= 0 || imgH <= 0) return false
        val tolX = imgW * TOLERANSI_POSISI
        val tolY = imgH * TOLERANSI_POSISI
        return abs(a[0] - b[0]) <= tolX && abs(a[1] - b[1]) <= tolY &&
            abs(a[2] - b[2]) <= tolX && abs(a[3] - b[3]) <= tolY
    }

    /**
     * Berapa halaman lain yang memuat kotak di posisi yang sama dengan [box].
     * [halamanLain] adalah daftar kotak per halaman, tanpa halaman asal.
     */
    fun hitungUlang(
        box: IntArray,
        halamanLain: List<List<IntArray>>,
        imgW: Int,
        imgH: Int,
    ): Int = halamanLain.count { hal -> hal.any { posisiSama(box, it, imgW, imgH) } }

    /** Bobot kedekatan ke tepi halaman. */
    const val BOBOT_TEPI = 0.45f

    /** Bobot pengulangan posisi antar halaman. */
    const val BOBOT_ULANG = 0.30f

    /** Bobot ukuran kecil. */
    const val BOBOT_KECIL = 0.25f

    /**
     * Nilai satu kotak sebagai kandidat watermark.
     *
     * Pengulangan antar halaman adalah petunjuk terkuat, tapi bobotnya sengaja
     * ditahan di 0,30 dan bukan lebih. Alasannya praktis: pengguna sering
     * membuka satu halaman saja, dan bila pengulangan mendominasi maka skor
     * tertinggi yang mungkin dicapai halaman tunggal jatuh di bawah ambang -
     * deteksi otomatis mati total persis di kasus yang paling sering dipakai.
     *
     * Jadi kedekatan ke tepi (0,45) plus ukuran kecil (0,25) sudah cukup
     * meloloskan watermark yang menempel di tepi tanpa bukti halaman lain,
     * sementara pengulangan bekerja sebagai penguat yang mendorong kandidat
     * sungguhan ke puncak daftar saat memang tersedia banyak halaman.
     */
    fun skor(box: IntArray, imgW: Int, imgH: Int, berulang: Int): Float {
        val luas = rasioLuas(box, imgW, imgH)
        if (luas <= 0f) return 0f

        val nUlang = min(1f, berulang.toFloat() / MIN_ULANG)
        val tepi = jarakTepi(box, imgW, imgH)
        val nTepi = (1f - (tepi / PITA_TEPI)).coerceIn(0f, 1f)
        val nKecil = (1f - (luas / LUAS_MAKS)).coerceIn(0f, 1f)

        return (BOBOT_TEPI * nTepi + BOBOT_ULANG * nUlang + BOBOT_KECIL * nKecil)
            .coerceIn(0f, 1f)
    }

    /** Kunci alasan untuk ditampilkan di UI. */
    fun alasan(berulang: Int, tepi: Float): String = when {
        berulang >= MIN_ULANG -> "ulang"
        tepi <= PITA_TEPI -> "tepi"
        else -> "lain"
    }

    /**
     * Saring [regions] pada satu halaman menjadi kandidat watermark terurut
     * dari skor tertinggi.
     *
     * [halamanLain] dipakai untuk mendeteksi pengulangan; boleh kosong, dan
     * saat itu hanya kotak di tepi yang bisa lolos ambang.
     *
     * [bubbles] adalah balon dialog yang sudah terdeteksi: teks di dalam balon
     * tidak pernah watermark, dan membuang lebih awal mencegah saran yang
     * akan menghapus dialog.
     */
    fun cari(
        regions: List<IntArray>,
        imgW: Int,
        imgH: Int,
        halamanLain: List<List<IntArray>> = emptyList(),
        bubbles: List<IntArray> = emptyList(),
    ): List<Kandidat> {
        if (regions.isEmpty() || imgW <= 0 || imgH <= 0) return emptyList()

        val diluarBalon =
            if (bubbles.isEmpty()) regions
            else TextRegionMath.dropInsideBubbles(regions, bubbles)

        val hasil = ArrayList<Kandidat>()
        for (r in diluarBalon) {
            if (r[2] <= r[0] || r[3] <= r[1]) continue
            val luas = rasioLuas(r, imgW, imgH)
            if (luas > LUAS_MAKS || luas < LUAS_MIN) continue

            val ulang = hitungUlang(r, halamanLain, imgW, imgH)
            val s = skor(r, imgW, imgH, ulang)
            if (s < AMBANG_SKOR) continue
            hasil.add(Kandidat(r, s, alasan(ulang, jarakTepi(r, imgW, imgH)), ulang))
        }
        return hasil.sortedByDescending { it.skor }
    }
}
