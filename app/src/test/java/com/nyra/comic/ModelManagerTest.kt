package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Aturan status berkas model.
 *
 * Yang diuji di sini bukan sekadar "ada atau tidak", melainkan perbedaan yang
 * berbahaya: berkas berukuran TEPAT tetapi isinya bukan model lagi. Kondisi
 * itu lolos dari seluruh pemeriksaan yang dipakai aplikasi sebelum ronde 24.
 */
class ModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val UKURAN = 1000L
    private val HASH_BENAR = "aa".repeat(32)

    private fun berkas(nama: String, ukuran: Long): File {
        val f = tmp.newFile(nama)
        f.writeBytes(ByteArray(ukuran.toInt()))
        return f
    }

    // ------------------------------------------------------------------
    // Pemeriksaan murah
    // ------------------------------------------------------------------

    @Test
    fun `berkas tidak ada dilaporkan belum ada`() {
        val f = File(tmp.root, "hilang.onnx")
        val l = ModelManager.periksa("lama", "LaMa", f, UKURAN)
        assertEquals(ModelManager.Status.BELUM_ADA, l.status)
        assertEquals(0L, l.byteTerpakai)
        assertTrue(l.perluUnduh)
        assertFalse(l.bisaDipakai)
    }

    @Test
    fun `sisa unduhan part dilaporkan separuh bukan belum ada`() {
        // Bedanya penting bagi pengguna: 40 MB sudah terpakai di telepon, dan
        // menekan unduh akan melanjutkan, bukan mulai dari nol.
        val f = File(tmp.root, "m.onnx")
        File(tmp.root, "m.onnx.part").writeBytes(ByteArray(400))
        val l = ModelManager.periksa("lama", "LaMa", f, UKURAN)
        assertEquals(ModelManager.Status.SEPARUH, l.status)
        assertEquals(400L, l.byteTerpakai)
        assertTrue(l.keterangan.contains("40"))
        assertTrue(l.perluUnduh)
    }

    @Test
    fun `ukuran tepat dilaporkan terpasang tanpa menghitung hash`() {
        val f = berkas("m.onnx", UKURAN)
        val l = ModelManager.periksa("lama", "LaMa", f, UKURAN)
        assertEquals(ModelManager.Status.TERPASANG, l.status)
        assertTrue(l.bisaDipakai)
        assertFalse(l.perluUnduh)
        assertNull("periksa() tidak boleh menghitung hash", l.hashTerhitung)
    }

    @Test
    fun `ukuran salah dilaporkan rusak`() {
        val f = berkas("m.onnx", 999L)
        val l = ModelManager.periksa("lama", "LaMa", f, UKURAN)
        assertEquals(ModelManager.Status.RUSAK, l.status)
        assertFalse(l.bisaDipakai)
        assertTrue(l.perluUnduh)
    }

    @Test
    fun `byte terpakai menjumlahkan berkas utuh dan sisa part`() {
        // Pengguna berhak melihat total sebenarnya, bukan angka yang
        // menyembunyikan sisa unduhan gagal.
        val f = berkas("m.onnx", UKURAN)
        File(tmp.root, "m.onnx.part").writeBytes(ByteArray(250))
        val l = ModelManager.periksa("lama", "LaMa", f, UKURAN)
        assertEquals(1250L, l.byteTerpakai)
    }

    // ------------------------------------------------------------------
    // Verifikasi hash
    // ------------------------------------------------------------------

    @Test
    fun `hash cocok menghasilkan terverifikasi`() {
        val f = berkas("m.onnx", UKURAN)
        val l = ModelManager.verifikasi("lama", "LaMa", f, UKURAN, HASH_BENAR) { HASH_BENAR }
        assertEquals(ModelManager.Status.TERVERIFIKASI, l.status)
        assertEquals(HASH_BENAR, l.hashTerhitung)
        assertTrue(l.bisaDipakai)
    }

    @Test
    fun `berkas berukuran tepat tapi isinya salah ketahuan rusak`() {
        // Inilah kasus yang lolos dari seluruh pemeriksaan sebelum ronde 24:
        // flash yang aus atau berkas yang ditukar, ukurannya tetap pas.
        val f = berkas("m.onnx", UKURAN)
        val l = ModelManager.verifikasi("lama", "LaMa", f, UKURAN, HASH_BENAR) { "bb".repeat(32) }
        assertEquals(ModelManager.Status.RUSAK, l.status)
        assertFalse(l.bisaDipakai)
        assertTrue(l.perluUnduh)
        assertTrue(l.keterangan.contains("hash"))
    }

    @Test
    fun `perbandingan hash mengabaikan besar kecil huruf`() {
        val f = berkas("m.onnx", UKURAN)
        val l = ModelManager.verifikasi("lama", "LaMa", f, UKURAN, HASH_BENAR.uppercase()) {
            HASH_BENAR.lowercase()
        }
        assertEquals(ModelManager.Status.TERVERIFIKASI, l.status)
    }

    @Test
    fun `berkas hilang tidak dibaca untuk dihash`() {
        val f = File(tmp.root, "hilang.onnx")
        var dipanggil = false
        val l = ModelManager.verifikasi("lama", "LaMa", f, UKURAN, HASH_BENAR) {
            dipanggil = true; HASH_BENAR
        }
        assertEquals(ModelManager.Status.BELUM_ADA, l.status)
        assertFalse("membaca 93 MB untuk berkas yang tak ada itu sia-sia", dipanggil)
    }

    @Test
    fun `berkas salah ukuran tidak dibaca untuk dihash`() {
        val f = berkas("m.onnx", 500L)
        var dipanggil = false
        val l = ModelManager.verifikasi("lama", "LaMa", f, UKURAN, HASH_BENAR) {
            dipanggil = true; HASH_BENAR
        }
        assertEquals(ModelManager.Status.RUSAK, l.status)
        assertFalse(dipanggil)
    }

    @Test
    fun `kegagalan membaca berkas dilaporkan rusak bukan melempar`() {
        val f = berkas("m.onnx", UKURAN)
        val l = ModelManager.verifikasi("lama", "LaMa", f, UKURAN, HASH_BENAR) {
            throw java.io.IOException("disk mati")
        }
        assertEquals(ModelManager.Status.RUSAK, l.status)
        assertTrue(l.keterangan.contains("disk mati"))
    }

    // ------------------------------------------------------------------
    // Bantuan tampilan
    // ------------------------------------------------------------------

    @Test
    fun `persen aman terhadap pembagian nol`() {
        assertEquals(0, ModelManager.persen(50, 0))
        assertEquals(0, ModelManager.persen(0, 100))
        assertEquals(50, ModelManager.persen(50, 100))
        assertEquals(100, ModelManager.persen(100, 100))
    }

    @Test
    fun `persen tidak melampaui seratus walau berkas lebih besar`() {
        assertEquals(100, ModelManager.persen(300, 100))
    }

    @Test
    fun `hash pendek menangani nilai kosong`() {
        assertEquals("—", ModelManager.hashPendek(null))
        assertEquals("—", ModelManager.hashPendek(""))
        assertEquals("abc", ModelManager.hashPendek("abc"))
        assertEquals("0123456789ab", ModelManager.hashPendek("0123456789abcdef"))
    }

    @Test
    fun `total byte menjumlahkan seluruh laporan`() {
        val a = ModelManager.Laporan("a", "A", ModelManager.Status.TERPASANG, 100, 100)
        val b = ModelManager.Laporan("b", "B", ModelManager.Status.BELUM_ADA, 0, 200)
        val c = ModelManager.Laporan("c", "C", ModelManager.Status.SEPARUH, 50, 300)
        assertEquals(150L, ModelManager.totalByte(listOf(a, b, c)))
    }

    // ------------------------------------------------------------------
    // Hash sungguhan lewat ModelDownloader
    // ------------------------------------------------------------------

    @Test
    fun `hash bawaan memakai perhitungan sungguhan dan cocok dengan nilai yang diketahui`() {
        // SHA-256 dari string kosong; membuktikan jalur bawaan benar-benar
        // menghitung, bukan mengembalikan nilai palsu.
        val f = tmp.newFile("kosong.bin")
        val kosong = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val l = ModelManager.verifikasi("x", "X", f, 0L, kosong)
        assertEquals(ModelManager.Status.TERVERIFIKASI, l.status)
        assertEquals(kosong, l.hashTerhitung)
    }
}
