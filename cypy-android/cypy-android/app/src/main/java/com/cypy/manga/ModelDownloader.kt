package com.cypy.manga

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pengunduh model inpaint LaMa (93 MB).
 *
 * Kenapa tidak dibundel di APK: berkas pasang jadi 149 MB - tiga kali lipat -
 * padahal fitur inpaint bawaannya mati dan hanya berguna untuk teks efek suara
 * di atas artwork. Pengguna yang memang butuh mengunduhnya sekali; sesudah itu
 * berkasnya menetap di penyimpanan internal aplikasi.
 *
 * Sumbernya repositori resmi OpenCV Zoo di Hugging Face (Apache-2.0), model
 * yang sama persis dengan yang diukur pada ronde 14.
 */
object ModelDownloader {

    /** opencv/inpainting_lama, revisi utama. */
    const val URL_LAMA =
        "https://huggingface.co/opencv/inpainting_lama/resolve/main/inpainting_lama_2025jan.onnx"

    private const val BUF = 1 shl 16

    /** Hasil satu upaya unduh. */
    sealed class Hasil {
        object Sukses : Hasil()
        object Dibatalkan : Hasil()
        data class Gagal(val pesan: String) : Hasil()
    }

    /**
     * Unduh model bila belum ada.
     *
     * [progress] dipanggil dengan (byte terunduh, total byte) - total bisa -1
     * kalau server tidak mengirim Content-Length. [batal] diperiksa tiap blok
     * supaya pengguna bisa membatalkan unduhan besar tanpa menunggu selesai.
     *
     * Unduhan ditulis ke berkas .part lalu baru dipindahkan, sehingga aplikasi
     * yang mati di tengah jalan tidak meninggalkan model rusak yang lolos
     * pemeriksaan keberadaan berkas.
     */
    fun unduhLama(
        ctx: Context,
        progress: (Long, Long) -> Unit = { _, _ -> },
        batal: () -> Boolean = { false },
    ): Hasil = unduh(
        URL_LAMA, Inpainter.berkas(ctx), Inpainter.UKURAN, progress, batal,
        sha256 = Inpainter.SHA256
    )

    /**
     * SHA-256 berkas, dibaca mengalir per blok.
     *
     * Sengaja TIDAK memuat seluruh berkas ke memori: model ini 93 MB,
     * sementara telepon target hanya punya RAM beberapa ratus MB untuk satu
     * aplikasi. `readBytes()` di sini adalah cara paling mudah untuk membuat
     * fitur ini mati dengan OutOfMemoryError di perangkat kelas bawah.
     */
    internal fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { masuk ->
            val buf = ByteArray(BUF)
            var n = masuk.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = masuk.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Inti unduhan, terpisah dari nama model supaya bisa diuji dengan berkas
     * kecil lewat server sungguhan alih-alih menarik 93 MB tiap kali tes.
     */
    internal fun unduh(
        alamat: String,
        tujuan: File,
        ukuran: Long,
        progress: (Long, Long) -> Unit = { _, _ -> },
        batal: () -> Boolean = { false },
        /** Bila diisi, berkas hanya dipromosikan kalau hash-nya cocok. */
        sha256: String? = null,
    ): Hasil {
        if (tujuan.isFile && tujuan.length() == ukuran) return Hasil.Sukses
        tujuan.parentFile?.mkdirs()

        val part = File(tujuan.parentFile, "${tujuan.name}.part")
        // Sisa unduhan sebelumnya dilanjutkan lewat Range: jaringan seluler
        // kerap putus di tengah berkas 93 MB.
        var sudah = if (part.isFile) part.length() else 0L
        if (sudah > ukuran) { part.delete(); sudah = 0L }

        // Total byte yang dijanjikan server lewat Content-Length; -1 bila tak
        // dikirim. Inilah pembeda antara "sambungan putus" dan "berkas di
        // server memang berukuran lain".
        var dijanjikan = -1L

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(alamat).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (sudah > 0) setRequestProperty("Range", "bytes=$sudah-")
            }
            val kode = conn.responseCode
            // 206 = lanjutan diterima. 200 saat meminta Range berarti server
            // mengabaikannya dan mengirim dari awal, jadi hitungan direset.
            if (sudah > 0 && kode == HttpURLConnection.HTTP_OK) sudah = 0L
            if (kode != HttpURLConnection.HTTP_OK && kode != HttpURLConnection.HTTP_PARTIAL) {
                return Hasil.Gagal("server menjawab HTTP $kode")
            }

            val sisa = conn.contentLengthLong
            val total = if (sisa > 0) sudah + sisa else ukuran
            dijanjikan = if (sisa > 0) total else -1L

            conn.inputStream.use { masuk ->
                java.io.FileOutputStream(part, sudah > 0).use { keluar ->
                    val buf = ByteArray(BUF)
                    var n = masuk.read(buf)
                    var terakhir = 0L
                    while (n >= 0) {
                        if (batal()) return Hasil.Dibatalkan
                        keluar.write(buf, 0, n)
                        sudah += n
                        // Kabari tiap 512 KB, bukan tiap blok 64 KB: 1.400
                        // pembaruan UI untuk satu unduhan hanya bikin macet.
                        if (sudah - terakhir >= 512 * 1024) {
                            terakhir = sudah
                            progress(sudah, total)
                        }
                        n = masuk.read(buf)
                    }
                    keluar.flush()
                }
            }
        } catch (e: Exception) {
            // Berkas .part sengaja TIDAK dihapus supaya percobaan berikutnya
            // melanjutkan, bukan mengulang dari nol.
            return Hasil.Gagal(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }

        val dapat = part.length()
        val lengkapMenurutServer = dijanjikan >= 0 && dapat >= dijanjikan
        if (dapat < ukuran && !lengkapMenurutServer) {
            // Aliran berakhir lebih awal tanpa melempar galat - inilah wujud
            // paling umum sambungan seluler yang putus. Berkas .part SENGAJA
            // dipertahankan; menghapusnya di sini membuat lanjut-unduh tidak
            // pernah berguna karena tiap kegagalan mengulang dari nol.
            return Hasil.Gagal("terputus di ${mb(dapat)} dari ${mb(ukuran)}, " +
                "tekan Unduh lagi untuk melanjutkan")
        }
        if (dapat != ukuran) {
            // Kelebihan byte berarti berkas di server bukan yang kita harapkan;
            // melanjutkannya tidak akan pernah menghasilkan model yang sah.
            part.delete()
            return Hasil.Gagal("ukuran tidak cocok: $dapat B, seharusnya $ukuran B")
        }

        // Gerbang terakhir sebelum berkas diangkat jadi model resmi.
        //
        // Ukurannya sudah cocok di sini, dan justru itu masalahnya: ukuran
        // sama sekali bukan bukti isi. Berkas ini diunduh lewat HTTP dari CDN
        // pihak ketiga lalu langsung dijalankan sebagai graf ONNX, jadi isi
        // yang tertukar berarti menjalankan model yang bukan kita pilih.
        //
        // Yang gagal di sini DIHAPUS, tidak dipertahankan seperti unduhan
        // terputus: berkasnya sudah lengkap dan tetap salah, jadi melanjutkan
        // hanya akan menghasilkan kegagalan yang sama selamanya.
        if (sha256 != null) {
            val punya = runCatching { sha256(part) }.getOrElse {
                part.delete()
                return Hasil.Gagal("gagal memeriksa berkas: ${it.message}")
            }
            if (!punya.equals(sha256, ignoreCase = true)) {
                part.delete()
                return Hasil.Gagal(
                    "berkas tidak sesuai (sidik SHA-256 berbeda), unduhan dibuang demi keamanan"
                )
            }
        }

        // Berkas lama harus disingkirkan dulu: renameTo gagal diam-diam di
        // sebagian sistem berkas kalau tujuannya sudah ada.
        if (tujuan.exists()) tujuan.delete()
        if (!part.renameTo(tujuan)) return Hasil.Gagal("gagal memindahkan berkas unduhan")
        progress(ukuran, ukuran)
        return Hasil.Sukses
    }

    /** Hapus model beserta sisa unduhan. Mengembalikan byte yang dibebaskan. */
    fun hapus(ctx: Context): Long {
        var bebas = 0L
        val t = Inpainter.berkas(ctx)
        if (t.isFile) { bebas += t.length(); t.delete() }
        val p = File(t.parentFile, "${Inpainter.NAMA}.part")
        if (p.isFile) { bebas += p.length(); p.delete() }
        return bebas
    }

    /** Teks ukuran ramah-manusia untuk label UI. */
    fun mb(byte: Long): String = String.format("%.1f MB", byte / 1_048_576.0)
}
