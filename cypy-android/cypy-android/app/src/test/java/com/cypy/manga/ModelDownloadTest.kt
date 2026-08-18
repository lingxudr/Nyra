package com.cypy.manga

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Unduhan model inpaint.
 *
 * Model 93 MB tidak lagi dibundel di APK - berkas pasang membengkak jadi
 * 149 MB dan melampaui batas unggah, padahal fiturnya bawaan mati. Karena itu
 * jalur unduhnya kini bagian dari produk dan harus diuji seperti kode lain:
 * lewat soket sungguhan, bukan tiruan.
 */
class ModelDownloadTest {

    /** Server berkas biner minimal yang paham header Range. */
    private class FileServer(private val isi: ByteArray) {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort

        /** Putuskan sambungan setelah sekian byte, meniru jaringan buruk. */
        @Volatile var putusSetelah: Int = -1

        /** Abaikan Range dan selalu kirim dari awal (server tanpa dukungan). */
        @Volatile var abaikanRange: Boolean = false

        @Volatile var kode: Int = 200

        /** Berapa kali permintaan masuk; membuktikan lanjutan benar terjadi. */
        @Volatile var permintaan: Int = 0

        /** Nilai header Range permintaan terakhir. */
        @Volatile var rangeTerakhir: String = ""

        fun start() = thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                try { layani(s) } catch (_: Exception) { } finally { runCatching { s.close() } }
            }
        }

        private fun layani(sock: Socket) {
            permintaan++
            val masuk = sock.getInputStream()
            val head = StringBuilder()
            var blank = 0
            while (blank < 2) {
                val b = masuk.read()
                if (b == -1) return
                val c = b.toChar()
                head.append(c)
                if (c == '\n') blank++ else if (c != '\r') blank = 0
            }
            val baris = head.toString().split("\r\n")
            rangeTerakhir = baris.firstOrNull { it.startsWith("Range:", true) }
                ?.substringAfter(':')?.trim().orEmpty()

            val mulai = if (!abaikanRange && rangeTerakhir.startsWith("bytes=")) {
                rangeTerakhir.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
            } else 0

            val out = sock.getOutputStream()
            if (kode != 200) {
                out.write(("HTTP/1.1 $kode ERROR\r\nContent-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
                return
            }
            val potongan = isi.copyOfRange(mulai.coerceAtMost(isi.size), isi.size)
            val status = if (mulai > 0) "206 Partial Content" else "200 OK"
            out.write(("HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${potongan.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(StandardCharsets.UTF_8))
            val batas = if (putusSetelah >= 0) minOf(putusSetelah, potongan.size) else potongan.size
            out.write(potongan, 0, batas)
            out.flush()
            if (batas < potongan.size) sock.close()   // putus di tengah berkas
        }

        fun stop() = runCatching { server.close() }.let { }
    }

    private lateinit var dir: File
    private lateinit var isi: ByteArray
    private lateinit var srv: FileServer
    private lateinit var tujuan: File

    private fun url() = "http://127.0.0.1:${srv.port}/model.onnx"

    @Before
    fun siap() {
        dir = File(System.getProperty("java.io.tmpdir"), "unduh-${System.nanoTime()}")
        dir.mkdirs()
        tujuan = File(dir, "lama.onnx")
        // 300 KB: cukup besar untuk melewati beberapa blok 64 KB sekaligus
        // memicu laporan kemajuan tiap 512 KB minimal sekali di akhir.
        isi = ByteArray(300_000) { (it % 251).toByte() }
        srv = FileServer(isi)
        srv.start()
    }

    @After
    fun bersih() {
        srv.stop()
        dir.deleteRecursively()
    }

    private fun unduh(
        batal: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = ModelDownloader.unduh(url(), tujuan, isi.size.toLong(), progress, batal)

    @Test
    fun unduhanUtuhTersimpanApaAdanya() {
        val h = unduh()
        assertTrue("harus sukses, dapat $h", h is ModelDownloader.Hasil.Sukses)
        assertTrue(tujuan.isFile)
        assertEquals(isi.size.toLong(), tujuan.length())
        assertArrayEquals("isi berkas harus identik byte per byte", isi, tujuan.readBytes())
    }

    @Test
    fun berkasSementaraTidakDitinggalkanSetelahSukses() {
        unduh()
        assertFalse("berkas .part harus sudah dipindahkan",
            File(dir, "lama.onnx.part").exists())
    }

    /** Model yang sudah ada tidak boleh diunduh ulang. */
    @Test
    fun modelYangSudahAdaTidakDiunduhLagi() {
        tujuan.writeBytes(isi)
        val sebelum = srv.permintaan
        val h = unduh()
        assertTrue(h is ModelDownloader.Hasil.Sukses)
        assertEquals("tidak boleh ada permintaan jaringan", sebelum, srv.permintaan)
    }

    /**
     * Inti dari fitur lanjut-unduh: sambungan putus di tengah berkas 93 MB
     * adalah hal biasa di jaringan seluler, dan mengulang dari nol tiap kali
     * berarti unduhan tidak pernah selesai.
     */
    @Test
    fun unduhanTerputusDilanjutkanBukanDiulang() {
        srv.putusSetelah = 100_000
        val gagal = unduh()
        assertTrue("percobaan pertama harus gagal", gagal is ModelDownloader.Hasil.Gagal)
        val part = File(dir, "lama.onnx.part")
        assertTrue("kemajuan harus tersimpan di .part", part.isFile)
        val tersimpan = part.length()
        assertTrue("harus ada isi tersimpan, dapat $tersimpan", tersimpan > 0)

        srv.putusSetelah = -1
        val h = unduh()
        assertTrue("percobaan kedua harus sukses, dapat $h", h is ModelDownloader.Hasil.Sukses)
        assertEquals("bytes=$tersimpan-", srv.rangeTerakhir)
        assertArrayEquals("hasil sambungan harus utuh", isi, tujuan.readBytes())
    }

    /**
     * Sebagian server mengabaikan Range dan mengirim ulang dari awal. Kalau
     * byte-nya ditambahkan ke .part yang sudah terisi, berkas jadi lebih
     * panjang dari aslinya dan modelnya rusak.
     */
    @Test
    fun serverYangAbaikanRangeTidakMenghasilkanBerkasGanda() {
        srv.putusSetelah = 100_000
        unduh()
        srv.putusSetelah = -1
        srv.abaikanRange = true
        val h = unduh()
        assertTrue("harus tetap sukses, dapat $h", h is ModelDownloader.Hasil.Sukses)
        assertEquals(isi.size.toLong(), tujuan.length())
        assertArrayEquals(isi, tujuan.readBytes())
    }

    @Test
    fun ukuranTidakCocokDitolakBukanDisimpan() {
        // Server jujur, tetapi pemanggil mengharapkan ukuran lain: itu tanda
        // berkas di server berubah, dan ONNX salah ukuran pasti gagal dimuat.
        val h = ModelDownloader.unduh(url(), tujuan, isi.size + 999L)
        assertTrue("harus gagal, dapat $h", h is ModelDownloader.Hasil.Gagal)
        assertFalse("berkas rusak tidak boleh tersimpan", tujuan.exists())
        assertFalse(File(dir, "lama.onnx.part").exists())
    }

    @Test
    fun httpErrorDilaporkanBukanDianggapSukses() {
        srv.kode = 404
        val h = unduh()
        assertTrue("harus gagal, dapat $h", h is ModelDownloader.Hasil.Gagal)
        assertTrue((h as ModelDownloader.Hasil.Gagal).pesan.contains("404"))
        assertFalse(tujuan.exists())
    }

    @Test
    fun pembatalanBerhentiDanMenyimpanKemajuan() {
        val h = unduh(batal = { true })
        assertTrue("harus dibatalkan, dapat $h", h is ModelDownloader.Hasil.Dibatalkan)
        assertFalse("model utuh belum boleh ada", tujuan.exists())
    }

    @Test
    fun kemajuanDilaporkanNaikDanBerakhirDiTotal() {
        val jejak = mutableListOf<Pair<Long, Long>>()
        unduh(progress = { a, b -> jejak.add(a to b) })
        assertTrue("harus ada laporan kemajuan", jejak.isNotEmpty())
        val akhir = jejak.last()
        assertEquals("laporan terakhir harus penuh", isi.size.toLong(), akhir.first)
        assertEquals(isi.size.toLong(), akhir.second)
        for (i in 1 until jejak.size) {
            assertTrue("kemajuan tidak boleh mundur", jejak[i].first >= jejak[i - 1].first)
        }
    }

    /** Ukuran yang dipakai memvalidasi harus sama dengan model resmi. */
    @Test
    fun ukuranModelResmiTerkunci() {
        assertEquals(92_591_623L, Inpainter.UKURAN)
        assertTrue(ModelDownloader.URL_LAMA.startsWith("https://"))
        assertTrue(ModelDownloader.URL_LAMA.endsWith(".onnx"))
    }

    @Test
    fun formatUkuranTerbaca() {
        assertEquals("93.0 MB", ModelDownloader.mb(97_517_568L))
        assertEquals("0.5 MB", ModelDownloader.mb(524_288L))
    }

    // ------------------------------------------------------------------
    // Verifikasi SHA-256 saat promosi .part
    // ------------------------------------------------------------------

    private fun sha(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun sha256DihitungSamaDenganPustakaStandar() {
        val f = File(dir, "contoh.bin")
        f.writeBytes(isi)
        // Berkas 300 KB melewati banyak blok, jadi ini sekaligus membuktikan
        // pembacaan mengalir per blok tidak merusak hasil hash.
        assertEquals(sha(isi), ModelDownloader.sha256(f))
    }

    @Test
    fun berkasBenarLolosVerifikasi() {
        val h = ModelDownloader.unduh(
            url(), tujuan, isi.size.toLong(), sha256 = sha(isi)
        )
        assertTrue("berkas yang sah harus diterima, bukan $h", h is ModelDownloader.Hasil.Sukses)
        assertTrue(tujuan.isFile)
        assertArrayEquals(isi, tujuan.readBytes())
    }

    @Test
    fun berkasTertukarDitolakMeskiUkuranSamaPersis() {
        // Inti ancamannya: isi berbeda, ukuran identik. Pemeriksaan ukuran
        // meloloskan ini tanpa keluhan, dan model asing itu langsung
        // dijalankan sebagai graf ONNX.
        val palsu = ByteArray(isi.size) { ((it + 7) % 251).toByte() }
        assertEquals("ukuran harus sama supaya ujinya bermakna", isi.size, palsu.size)
        assertFalse(sha(palsu) == sha(isi))

        val srvPalsu = FileServer(palsu)
        srvPalsu.start()
        try {
            val h = ModelDownloader.unduh(
                "http://127.0.0.1:${srvPalsu.port}/model.onnx",
                tujuan, palsu.size.toLong(), sha256 = sha(isi)
            )
            assertTrue("berkas tertukar harus ditolak, bukan $h", h is ModelDownloader.Hasil.Gagal)
            assertTrue(
                "pesan harus menyebut sidik SHA-256",
                (h as ModelDownloader.Hasil.Gagal).pesan.contains("SHA-256")
            )
            // Yang penting: tidak boleh ada model yang bisa dipakai.
            assertFalse("model palsu tidak boleh dipromosikan", tujuan.exists())
            assertFalse(
                "sisa .part harus dibuang, bukan dilanjutkan selamanya",
                File(dir, "lama.onnx.part").exists()
            )
        } finally {
            srvPalsu.stop()
        }
    }

    @Test
    fun modelLamaTidakDitimpaOlehUnduhanPalsu() {
        // Pengguna sudah punya model sah; unduhan ulang yang ternyata rusak
        // tidak boleh merusak yang sudah bekerja.
        tujuan.writeBytes(isi)
        val palsu = ByteArray(isi.size) { 9 }
        val srvPalsu = FileServer(palsu)
        srvPalsu.start()
        try {
            ModelDownloader.unduh(
                "http://127.0.0.1:${srvPalsu.port}/model.onnx",
                tujuan, palsu.size.toLong(), sha256 = sha(palsu.copyOf().also { it[0] = 1 })
            )
            assertArrayEquals("model lama harus tetap utuh", isi, tujuan.readBytes())
        } finally {
            srvPalsu.stop()
        }
    }

    @Test
    fun tanpaSha256PerilakuLamaTidakBerubah() {
        // Pemanggil lain (dan tes lama) tidak mengirim hash; jalur itu harus
        // tetap bekerja apa adanya.
        val h = ModelDownloader.unduh(url(), tujuan, isi.size.toLong())
        assertTrue(h is ModelDownloader.Hasil.Sukses)
        assertArrayEquals(isi, tujuan.readBytes())
    }

    /**
     * Sidik jari model resmi, dikunci sebagai konstanta.
     *
     * Nilai ini diverifikasi dengan mengunduh berkas 92.591.623 byte itu
     * sungguhan lalu menghitung hash-nya; hasilnya juga sama dengan header
     * `x-linked-etag` (hash LFS) dari Hugging Face. Kalau konstanta ini salah
     * ketik, fitur inpaint mati total bagi semua pengguna - jadi ia dikunci
     * di sini supaya perubahan tak sengaja langsung ketahuan.
     */
    @Test
    fun sidikModelResmiTerkunci() {
        assertEquals(
            "7df918ac3921d3daf0aae1d219776cf0dc4e4935f035af81841b40adcf74fdf2",
            Inpainter.SHA256
        )
        assertEquals(64, Inpainter.SHA256.length)
        assertTrue(Inpainter.SHA256.all { it in "0123456789abcdef" })
    }
}
