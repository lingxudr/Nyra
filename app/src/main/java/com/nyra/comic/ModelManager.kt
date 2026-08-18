package com.nyra.comic

import java.io.File

/**
 * Aturan pemeriksaan berkas model yang sudah terpasang.
 *
 * Sampai ronde 24, "model ada" berarti: berkasnya ada dan ukurannya tepat.
 * Itu cukup untuk menangkap unduhan yang terpotong, dan memang itulah yang
 * paling sering terjadi. Tapi ada dua hal yang lolos begitu saja:
 *
 *  1. Berkas yang RUSAK tanpa berubah ukurannya. Penyimpanan flash yang aus,
 *     berkas yang tertimpa separuh, atau salinan cadangan yang dipulihkan
 *     setengah jalan menghasilkan 92.591.623 byte yang isinya bukan model
 *     lagi. Gerbang SHA-256 di ModelDownloader hanya berlaku bagi berkas
 *     yang BARU diunduh — begitu berkas mendarat, hash-nya tidak pernah
 *     dilihat lagi seumur hidup pemasangan.
 *  2. Berkas yang DIGANTI setelah terpasang. Direktori filesDir memang milik
 *     aplikasi, tetapi pada perangkat yang sudah di-root ia bisa ditukar.
 *     Isinya lalu dijalankan sebagai graf ONNX.
 *
 * Kelas ini tidak memaksa verifikasi hash pada setiap pemakaian: menghitung
 * SHA-256 atas 93 MB memakan waktu nyata dan tidak boleh terjadi diam-diam
 * sebelum setiap halaman digambar. Verifikasi adalah tindakan yang diminta
 * pengguna dari layar manajemen model, dan hasilnya dilaporkan apa adanya.
 *
 * Logikanya murni fungsi berkas supaya bisa diuji tanpa Android.
 */
object ModelManager {

    /** Status satu berkas model. */
    enum class Status {
        /** Berkas tidak ada sama sekali. */
        BELUM_ADA,

        /** Ada sisa unduhan .part, tapi berkas utuhnya belum ada. */
        SEPARUH,

        /** Ada, ukurannya tepat — hash belum diperiksa. */
        TERPASANG,

        /** Ada, ukurannya tepat, hash cocok. */
        TERVERIFIKASI,

        /** Ada, tapi ukuran atau hash-nya salah. Tidak boleh dipakai. */
        RUSAK
    }

    /**
     * Hasil pemeriksaan satu model.
     *
     * [byteTerpakai] menghitung berkas utuh DAN sisa .part, sebab keduanya
     * sama-sama memakan penyimpanan telepon dan pengguna berhak tahu total
     * sebenarnya — bukan angka yang menyembunyikan 40 MB sisa unduhan gagal.
     */
    data class Laporan(
        val id: String,
        val judul: String,
        val status: Status,
        val byteTerpakai: Long,
        val byteDiharapkan: Long,
        /** Diisi hanya bila verifikasi hash benar-benar dijalankan. */
        val hashTerhitung: String? = null,
        val keterangan: String = ""
    ) {
        val bisaDipakai: Boolean
            get() = status == Status.TERPASANG || status == Status.TERVERIFIKASI

        val perluUnduh: Boolean
            get() = status == Status.BELUM_ADA || status == Status.SEPARUH ||
                    status == Status.RUSAK
    }

    /** Nama berkas sisa unduhan, sama seperti yang dipakai ModelDownloader. */
    fun berkasPart(tujuan: File): File = File(tujuan.parentFile, "${tujuan.name}.part")

    /**
     * Periksa satu berkas tanpa menghitung hash.
     *
     * Ini pemeriksaan murah yang dipakai untuk menggambar daftar; menghitung
     * SHA-256 tiga berkas setiap kali layar dibuka akan membuatnya terasa
     * macet beberapa detik tanpa alasan yang terlihat pengguna.
     */
    fun periksa(
        id: String,
        judul: String,
        berkas: File,
        ukuranDiharapkan: Long
    ): Laporan {
        val part = berkasPart(berkas)
        val byteP = if (part.isFile) part.length() else 0L

        if (!berkas.isFile) {
            return if (byteP > 0L) Laporan(
                id, judul, Status.SEPARUH, byteP, ukuranDiharapkan,
                keterangan = "unduhan tertunda ${persen(byteP, ukuranDiharapkan)}%"
            ) else Laporan(id, judul, Status.BELUM_ADA, 0L, ukuranDiharapkan)
        }

        val byteB = berkas.length()
        val total = byteB + byteP
        if (byteB != ukuranDiharapkan) {
            return Laporan(
                id, judul, Status.RUSAK, total, ukuranDiharapkan,
                keterangan = "ukuran $byteB B, seharusnya $ukuranDiharapkan B"
            )
        }
        return Laporan(id, judul, Status.TERPASANG, total, ukuranDiharapkan)
    }

    /**
     * Periksa DAN hitung hash. Mahal; hanya atas permintaan pengguna.
     *
     * [hitungHash] disuntikkan supaya pengujian tidak perlu menyalin
     * MessageDigest, dan supaya produksi tetap memakai pembacaan mengalir
     * milik ModelDownloader alih-alih readBytes() yang meledakkan memori.
     */
    fun verifikasi(
        id: String,
        judul: String,
        berkas: File,
        ukuranDiharapkan: Long,
        sha256Diharapkan: String,
        hitungHash: (File) -> String = { ModelDownloader.sha256(it) }
    ): Laporan {
        val dasar = periksa(id, judul, berkas, ukuranDiharapkan)
        // Berkas yang belum ada atau salah ukuran tidak perlu dihash: hasilnya
        // sudah pasti tidak cocok, dan membaca 93 MB untuk itu sia-sia.
        if (dasar.status != Status.TERPASANG) return dasar

        val punya = runCatching { hitungHash(berkas) }.getOrElse {
            return dasar.copy(
                status = Status.RUSAK,
                keterangan = "gagal membaca berkas: ${it.message}"
            )
        }
        return if (punya.equals(sha256Diharapkan, ignoreCase = true)) {
            dasar.copy(status = Status.TERVERIFIKASI, hashTerhitung = punya)
        } else {
            dasar.copy(
                status = Status.RUSAK,
                hashTerhitung = punya,
                keterangan = "hash tidak cocok"
            )
        }
    }

    /** Total byte yang dipakai sekumpulan laporan. */
    fun totalByte(daftar: List<Laporan>): Long = daftar.sumOf { it.byteTerpakai }

    /** Persentase bulat, aman terhadap pembagian nol. */
    fun persen(sudah: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((sudah * 100L) / total).coerceIn(0L, 100L).toInt()
    }

    /**
     * Delapan digit pertama hash, untuk ditampilkan.
     *
     * Hash penuh 64 karakter tidak muat dan tidak dibaca siapa pun di layar
     * telepon; yang berguna bagi pengguna hanyalah "ada nilainya dan cocok".
     */
    fun hashPendek(h: String?): String {
        if (h.isNullOrBlank()) return "—"
        return if (h.length <= 12) h else h.take(12)
    }
}
