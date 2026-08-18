package com.cypy.manga

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Penulis arsip CBZ (dan ZIP biasa) untuk hasil terjemahan.
 *
 * Kenapa perlu: sampai ronde 21 keluaran hanya PNG lepas per halaman. Semua
 * pembaca komik di Android — Mihon/Tachiyomi, Perfect Viewer, Kavita — bekerja
 * dengan satu berkas per bab, dan CBZ adalah format bakunya: ZIP berisi gambar,
 * dibaca menurut urutan nama berkas. Tanpa ini pengguna harus memindahkan 40
 * berkas lepas per bab secara manual.
 *
 * Dua keputusan yang menentukan bisa-tidaknya berkas dibaca:
 *
 *  1. Nama entri diberi nomor berlapis nol (`0001.png`) supaya urutan
 *     leksikografis sama dengan urutan halaman. Pembaca komik mengurutkan
 *     nama sebagai teks; `10.png` mendahului `2.png` dan bab jadi berantakan.
 *
 *  2. PNG disimpan dengan metode STORED, bukan DEFLATE. PNG sudah terkompresi
 *     di dalam; memampatkannya lagi menghabiskan CPU telepon untuk penghematan
 *     nyaris nol. STORED mengharuskan kita mengisi sendiri ukuran dan CRC-32
 *     tiap entri — itulah sebabnya isi tiap halaman disiapkan sebagai ByteArray
 *     lebih dulu.
 */
object CbzWriter {

    /** Ekstensi keluaran arsip komik. */
    const val EXT = "cbz"
    const val MIME = "application/vnd.comicbook+zip"

    /**
     * Satu halaman yang akan ditulis.
     *
     * [isi] sengaja ByteArray dan bukan aliran: CRC dan ukuran harus diketahui
     * sebelum entri STORED ditulis, dan halaman terbesar yang pernah kita
     * tangani (strip 1080x11700 PNG) masih beberapa MB — jauh di bawah batas
     * yang membuat telepon kehabisan memori bila diproses satu per satu.
     */
    data class Halaman(val nama: String, val isi: ByteArray) {
        // equals/hashCode dibuat manual karena ByteArray membandingkan
        // referensi; tanpa ini perbandingan di tes selalu gagal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Halaman) return false
            return nama == other.nama && isi.contentEquals(other.isi)
        }

        override fun hashCode(): Int = 31 * nama.hashCode() + isi.contentHashCode()
    }

    /**
     * Nama entri baku: nomor berlapis nol + ekstensi asli.
     *
     * [urutan] berbasis 0; hasilnya berbasis 1 agar cocok dengan kebiasaan
     * penomoran halaman komik.
     */
    fun namaEntri(urutan: Int, nama: String, digit: Int = 4): String {
        val ext = if (nama.contains('.')) nama.substringAfterLast('.').lowercase() else "png"
        return "%0${digit}d.%s".format(urutan + 1, ext)
    }

    /**
     * Tulis arsip ke [keluar].
     *
     * Aliran TIDAK ditutup di sini: pemanggil memilikinya (SAF membutuhkan
     * penutupan pada tempatnya sendiri), dan menutup dua kali pada beberapa
     * penyedia dokumen melempar galat.
     */
    fun tulis(keluar: OutputStream, halaman: List<Halaman>, komentar: String? = null) {
        val zip = ZipOutputStream(keluar)
        zip.setMethod(ZipOutputStream.STORED)
        komentar?.let { zip.setComment(it) }
        val crc = CRC32()
        for (h in halaman) {
            crc.reset()
            crc.update(h.isi)
            val e = ZipEntry(h.nama).apply {
                method = ZipEntry.STORED
                size = h.isi.size.toLong()
                compressedSize = h.isi.size.toLong()
                this.crc = crc.value
                // Waktu tetap supaya arsip yang sama menghasilkan byte yang
                // sama: berguna untuk memverifikasi ulang keluaran.
                time = 0L
            }
            zip.putNextEntry(e)
            zip.write(h.isi)
            zip.closeEntry()
        }
        // finish() menulis direktori pusat tanpa menutup aliran pembungkus.
        zip.finish()
    }

    /**
     * Nama berkas arsip dari nama proyek.
     *
     * Karakter yang dilarang sistem berkas diganti garis bawah. Ini bukan
     * kosmetik: nama bab hasil unduhan kerap memuat ':' atau '/' dan
     * DocumentFile.createFile akan gagal diam-diam karenanya.
     */
    fun namaArsip(namaProyek: String, bahasa: String): String {
        val bersih = namaProyek
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "cypy" }
        return "${bersih}_${bahasa.uppercase()}.$EXT"
    }
}
