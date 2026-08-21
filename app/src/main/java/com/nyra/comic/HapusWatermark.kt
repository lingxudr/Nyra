package com.nyra.comic

import android.content.Context
import android.graphics.Bitmap

/**
 * Penghapus watermark hibrida.
 *
 * Ada dua mesin penambal di aplikasi ini dan keduanya punya alasan hidup:
 *
 * - [Inpainter] (LaMa, 93 MB) memberi hasil terbaik karena mengarang ulang
 *   isi lubang berdasar konteks gambar, tapi harus diunduh dulu.
 * - [TambalLokal] tidak butuh unduhan sama sekali dan bekerja seketika, tapi
 *   hanya meneruskan warna dan gradasi dari sekitar lubang.
 *
 * Memaksa pengguna mengunduh 93 MB sebelum boleh menghapus satu banner
 * watermark itu tidak masuk akal, jadi kelas ini memilih otomatis: pakai LaMa
 * bila memang sudah ada di perangkat, kalau tidak pakai penambal lokal. Kedua
 * jalur menghasilkan gambar yang sudah bersih, jadi pengguna tidak pernah
 * menemui tombol yang mati.
 *
 * Untuk banner watermark - pita teks di atas latar yang relatif rata, yang
 * merupakan bentuk paling umum - selisih kualitas kedua mesin ini kecil.
 * Bedanya baru terasa bila watermark menimpa artwork padat.
 */
object HapusWatermark {

    /** Mesin mana yang dipakai untuk satu penghapusan. */
    enum class Mesin { LAMA, LOKAL }

    /** Hasil satu penghapusan, dipakai untuk melapor ke pengguna. */
    data class Hasil(val mesin: Mesin, val kotak: Int, val piksel: Int)

    /**
     * Hapus [boxes] dari [page], menggambar langsung di bitmap tersebut.
     *
     * Mengembalikan null bila tidak ada yang dikerjakan, sehingga pemanggil
     * bisa membedakan "tidak ada kotak" dari "sudah dibersihkan".
     */
    fun hapus(
        ctx: Context,
        page: Bitmap,
        boxes: List<IntArray>,
        log: (String) -> Unit = {},
    ): Hasil? {
        val sah = boxes.filter { it.size >= 4 && it[2] > it[0] && it[3] > it[1] }
        if (sah.isEmpty()) return null

        // LaMa hanya dicoba bila berkasnya benar-benar ada dan ukurannya utuh;
        // tersedia() sudah memeriksa keduanya.
        if (Inpainter.tersedia(ctx)) {
            val hasil = runCatching {
                Inpainter(ctx).use { it.erase(page, sah, log) }
            }
            hasil.onSuccess { return Hasil(Mesin.LAMA, sah.size, 0) }
            // Model rusak, memori habis, atau ONNX menolak: jangan menyerah,
            // masih ada penambal lokal yang selalu bisa jalan.
            hasil.onFailure { log("  [!] LaMa gagal (${it.message}), memakai tambal lokal") }
        }

        return Hasil(Mesin.LOKAL, sah.size, lokal(page, sah))
    }

    /** Jalankan penambal lokal di atas bitmap Android. */
    fun lokal(page: Bitmap, boxes: List<IntArray>): Int {
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return 0

        val pix = IntArray(w * h)
        page.getPixels(pix, 0, w, 0, 0, w, h)
        val n = TambalLokal.tambal(pix, w, h, boxes)
        if (n > 0) page.setPixels(pix, 0, w, 0, 0, w, h)
        return n
    }

    /** Mesin yang akan dipakai, untuk ditampilkan di UI sebelum menjalankan. */
    fun mesinAktif(ctx: Context): Mesin =
        if (Inpainter.tersedia(ctx)) Mesin.LAMA else Mesin.LOKAL
}
