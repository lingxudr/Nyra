package com.nyra.comic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Logika penyuntingan kotak: geser, ubah ukuran, tambah, hapus.
 *
 * Dipisah dari Activity dan View supaya bisa diuji sebagai matematika biasa.
 * Semua operasi bekerja pada koordinat BITMAP, bukan koordinat layar; View
 * yang bertugas menerjemahkan sentuhan ke ruang bitmap sebelum memanggil ini.
 *
 * Kenapa fitur ini ada: sampai ronde 21 editor hanya bisa mengubah TEKS di
 * dalam kotak yang sudah terdeteksi. Kalau detektor melewatkan satu balon,
 * pengguna tidak punya jalan keluar sama sekali — balon itu selamanya tidak
 * bisa diterjemahkan. Kalau detektor memberi kotak palsu di atas artwork,
 * kotak itu selamanya menutupi gambar. Keduanya keluhan wajar, dan keduanya
 * hanya butuh mengubah empat angka.
 */
object BoxEdit {

    /** Ukuran sisi minimum sebuah kotak dalam piksel bitmap. */
    const val MIN_SISI = 12

    /**
     * Jarak sentuh (piksel bitmap) untuk menangkap gagang pengubah ukuran.
     *
     * Nilai ini SELALU dibagi skala zoom oleh pemanggil: pada zoom 8x, 24 px
     * bitmap hanya terasa 3 px di layar dan gagangnya jadi mustahil disentuh.
     */
    const val GAGANG = 24

    /** Bagian kotak yang sedang ditarik. */
    enum class Gagang { TIDAK_ADA, KIRI_ATAS, KANAN_ATAS, KIRI_BAWAH, KANAN_BAWAH, ISI }

    /**
     * Rapikan kotak: urutkan tepi, jepit ke dalam gambar, jaga sisi minimum.
     *
     * Semua jalur sunting berakhir di sini, sebab kotak dengan x2 < x1 atau
     * yang keluar dari kanvas akan membuat Bitmap.createBitmap melempar galat
     * saat halaman digambar ulang — kegagalan yang muncul jauh dari sebabnya.
     */
    fun rapikan(box: IntArray, imgW: Int, imgH: Int): IntArray {
        var x1 = min(box[0], box[2])
        var y1 = min(box[1], box[3])
        var x2 = max(box[0], box[2])
        var y2 = max(box[1], box[3])

        // Jepit dulu ke kanvas, baru paksa sisi minimum: urutan sebaliknya
        // bisa mendorong kotak keluar gambar lagi.
        x1 = x1.coerceIn(0, max(0, imgW - 1))
        y1 = y1.coerceIn(0, max(0, imgH - 1))
        x2 = x2.coerceIn(0, imgW)
        y2 = y2.coerceIn(0, imgH)

        if (x2 - x1 < MIN_SISI) {
            if (x1 + MIN_SISI <= imgW) x2 = x1 + MIN_SISI else { x2 = imgW; x1 = max(0, imgW - MIN_SISI) }
        }
        if (y2 - y1 < MIN_SISI) {
            if (y1 + MIN_SISI <= imgH) y2 = y1 + MIN_SISI else { y2 = imgH; y1 = max(0, imgH - MIN_SISI) }
        }
        return intArrayOf(x1, y1, x2, y2)
    }

    /**
     * Gagang mana yang berada di bawah titik (bx,by).
     *
     * Sudut diperiksa lebih dulu daripada isi: pada kotak kecil keempat sudut
     * saling tumpang tindih dengan bagian dalam, dan pengguna yang menyentuh
     * pojok hampir selalu bermaksud mengubah ukuran, bukan menggeser.
     */
    fun gagangDi(box: IntArray, bx: Float, by: Float, toleransi: Int = GAGANG): Gagang {
        val t = toleransi.toFloat()
        val dekat = { px: Int, py: Int -> abs(bx - px) <= t && abs(by - py) <= t }
        if (dekat(box[0], box[1])) return Gagang.KIRI_ATAS
        if (dekat(box[2], box[1])) return Gagang.KANAN_ATAS
        if (dekat(box[0], box[3])) return Gagang.KIRI_BAWAH
        if (dekat(box[2], box[3])) return Gagang.KANAN_BAWAH
        if (bx >= box[0] && bx <= box[2] && by >= box[1] && by <= box[3]) return Gagang.ISI
        return Gagang.TIDAK_ADA
    }

    /** Geser seluruh kotak sejauh (dx,dy), tetap di dalam gambar. */
    fun geser(box: IntArray, dx: Int, dy: Int, imgW: Int, imgH: Int): IntArray {
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        // Geseran dijepit di tingkat POSISI, bukan per tepi, supaya kotak yang
        // didorong ke pinggir ikut berhenti utuh alih-alih menjadi gepeng.
        val x1 = (box[0] + dx).coerceIn(0, max(0, imgW - w))
        val y1 = (box[1] + dy).coerceIn(0, max(0, imgH - h))
        return rapikan(intArrayOf(x1, y1, x1 + w, y1 + h), imgW, imgH)
    }

    /** Tarik satu sudut ke (bx,by). */
    fun ubahUkuran(box: IntArray, gagang: Gagang, bx: Int, by: Int, imgW: Int, imgH: Int): IntArray {
        val b = box.copyOf()
        when (gagang) {
            Gagang.KIRI_ATAS -> { b[0] = bx; b[1] = by }
            Gagang.KANAN_ATAS -> { b[2] = bx; b[1] = by }
            Gagang.KIRI_BAWAH -> { b[0] = bx; b[3] = by }
            Gagang.KANAN_BAWAH -> { b[2] = bx; b[3] = by }
            else -> return box
        }
        return rapikan(b, imgW, imgH)
    }

    /**
     * Kotak baru dari dua titik seretan; null bila terlalu kecil.
     *
     * Mengembalikan null alih-alih kotak minimum supaya ketukan tak sengaja
     * di kanvas kosong tidak diam-diam melahirkan kotak sebesar 12x12 yang
     * harus dicari dan dihapus pengguna.
     */
    fun kotakBaru(x1: Int, y1: Int, x2: Int, y2: Int, imgW: Int, imgH: Int): IntArray? {
        val lebar = abs(x2 - x1)
        val tinggi = abs(y2 - y1)
        if (lebar < MIN_SISI || tinggi < MIN_SISI) return null
        return rapikan(intArrayOf(x1, y1, x2, y2), imgW, imgH)
    }

    /**
     * Sisipkan kotak baru ke halaman sambil MENJAGA urutan baca.
     *
     * Ini bagian yang paling mudah salah. Nomor kotak bukan sekadar label:
     * ia kunci untuk translations, colors, dan freeText, sekaligus penentu
     * urutan percakapan. Menambahkan kotak baru di akhir daftar akan membuat
     * balon di tengah halaman bernomor terbesar, sehingga urutan bacanya
     * kacau. Jadi kotak disisipkan pada posisi urutan baca yang benar dan
     * SELURUH peta bernomor digeser mengikutinya.
     *
     * Mengembalikan indeks berbasis 0 tempat kotak disisipkan.
     */
    fun sisipkan(page: Project.Page, box: IntArray, kananKeKiri: Boolean): Int {
        val semua = ArrayList(page.boxes)
        semua.add(box)
        val urut = BoxUtils.urutBaca(semua, kananKeKiri)

        // Cari posisi kotak baru di hasil pengurutan berdasarkan identitas
        // isi, bukan referensi objek: urutBaca boleh menyalin array.
        var posBaru = urut.indexOfFirst { it.contentEquals(box) }
        if (posBaru < 0) posBaru = urut.size - 1

        // Peta lama (kunci = nomor berbasis 1) harus digeser: setiap kotak
        // yang kini berada di atau sesudah posisi baru naik satu nomor.
        geserPeta(page, posBaru + 1, +1)

        page.boxes.clear()
        page.boxes.addAll(urut)
        return posBaru
    }

    /**
     * Hapus kotak nomor [nomor] (berbasis 1) beserta seluruh data terkait.
     *
     * Warna dan penanda teks-lepas berkunci koordinat, bukan nomor, jadi
     * keduanya dibersihkan lewat kunci kotak yang dihapus. Kalau tidak,
     * entri yatim menumpuk di project.json setiap kali pengguna membetulkan
     * deteksi.
     */
    fun hapus(page: Project.Page, nomor: Int): Boolean {
        val idx = nomor - 1
        if (idx < 0 || idx >= page.boxes.size) return false
        val box = page.boxes[idx]
        val kunci = kunciKotak(box)

        page.boxes.removeAt(idx)
        // Warna dan penanda teks-lepas berkunci KOORDINAT, jadi keduanya hanya
        // boleh dibuang kalau tidak ada kotak tersisa dengan koordinat sama.
        // Dua balon identik posisinya jarang, tapi hasil mergeOverlapping bisa
        // menghasilkannya, dan membuang warnanya akan mengubah tampilan kotak
        // yang justru TIDAK disunting pengguna.
        if (page.boxes.none { kunciKotak(it) == kunci }) {
            page.colors.remove(kunci)
            page.styles.remove(kunci)
            page.freeText.remove(kunci)
        }
        page.translations.remove(nomor.toString())
        page.sourceText.remove(nomor.toString())
        geserPeta(page, nomor + 1, -1)
        return true
    }

    /**
     * Perbarui koordinat kotak nomor [nomor] tanpa mengubah urutannya.
     *
     * Kunci warna ikut dipindahkan supaya warna terukur tidak hilang begitu
     * kotak digeser satu piksel.
     */
    fun perbarui(page: Project.Page, nomor: Int, box: IntArray) {
        val idx = nomor - 1
        if (idx < 0 || idx >= page.boxes.size) return
        val lama = kunciKotak(page.boxes[idx])
        val baru = kunciKotak(box)
        if (lama != baru) {
            page.colors.remove(lama)?.let { page.colors[baru] = it }
            // Gaya ikut pindah bersama warna: kalau tertinggal di kunci lama,
            // kotak yang digeser kehilangan tipografinya dan - lebih buruk -
            // kotak baru yang kebetulan menempati kunci itu mewarisi gaya
            // milik kotak lain.
            page.styles.remove(lama)?.let { page.styles[baru] = it }
            if (page.freeText.remove(lama)) page.freeText.add(baru)
        }
        page.boxes[idx] = box
    }

    /**
     * Geser semua kunci bernomor mulai dari [dari] sebesar [delta].
     *
     * Ditulis sebagai satu fungsi karena translations dan sourceText harus
     * bergeser BERSAMAAN; menggeser salah satu saja membuat teks asli
     * menempel pada balon yang salah.
     */
    private fun geserPeta(page: Project.Page, dari: Int, delta: Int) {
        if (delta == 0) return
        geserSatuPeta(page.translations, dari, delta)
        geserSatuPeta(page.sourceText, dari, delta)
    }

    private fun geserSatuPeta(peta: MutableMap<String, String>, dari: Int, delta: Int) {
        val bernomor = peta.keys.mapNotNull { k -> k.toIntOrNull()?.let { it to k } }
            .filter { it.first >= dari }
        if (bernomor.isEmpty()) return
        // Urutan penyalinan menentukan benar-salah: saat menaikkan nomor,
        // proses dari yang TERBESAR dulu supaya tidak menimpa tetangga yang
        // belum sempat dipindahkan; saat menurunkan, dari yang terkecil.
        val urut = if (delta > 0) bernomor.sortedByDescending { it.first }
        else bernomor.sortedBy { it.first }
        for ((n, k) in urut) {
            val v = peta.remove(k) ?: continue
            peta[(n + delta).toString()] = v
        }
    }

    /** Kunci koordinat, harus sama persis dengan yang dipakai Pipeline. */
    fun kunciKotak(b: IntArray): String = "${b[0]},${b[1]},${b[2]},${b[3]}"
}
