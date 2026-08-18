package com.cypy.manga

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Arsip CBZ harus benar-benar bisa dibuka pembaca komik.
 *
 * Tes ini membaca ulang arsip dengan ZipInputStream biasa — kalau ada satu
 * saja CRC atau ukuran entri yang salah pada metode STORED, pembacaan akan
 * melempar galat di sini, bukan di telepon pengguna.
 */
class CbzWriterTest {

    private fun isi(n: Int, byte: Int): ByteArray = ByteArray(n) { byte.toByte() }

    private fun bacaUlang(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                out.add(e.name to zin.readBytes())
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        return out
    }

    @Test
    fun arsipBisaDibacaUlangDenganIsiUtuh() {
        val hal = listOf(
            CbzWriter.Halaman("0001.png", isi(500, 0xA1)),
            CbzWriter.Halaman("0002.png", isi(1200, 0x5C)),
            CbzWriter.Halaman("0003.png", isi(3, 0x00))
        )
        val bos = ByteArrayOutputStream()
        CbzWriter.tulis(bos, hal)

        val baca = bacaUlang(bos.toByteArray())
        assertEquals(3, baca.size)
        assertEquals(listOf("0001.png", "0002.png", "0003.png"), baca.map { it.first })
        assertArrayEquals(hal[0].isi, baca[0].second)
        assertArrayEquals(hal[1].isi, baca[1].second)
        assertArrayEquals(hal[2].isi, baca[2].second)
    }

    @Test
    fun entriMemakaiMetodeStored() {
        // DEFLATE atas PNG membakar CPU telepon untuk penghematan nol; kalau
        // ada yang menghapus setMethod, tes ini yang menangkapnya.
        val bos = ByteArrayOutputStream()
        CbzWriter.tulis(bos, listOf(CbzWriter.Halaman("0001.png", isi(400, 0x7B))))
        ZipInputStream(ByteArrayInputStream(bos.toByteArray())).use { zin ->
            val e = zin.nextEntry!!
            zin.readBytes()
            assertEquals(ZipEntry.STORED, e.method)
        }
    }

    @Test
    fun aliranPemanggilTidakDitutup() {
        // SAF menutup alirannya sendiri; penutupan ganda pada sebagian penyedia
        // dokumen melempar galat, jadi tulis() hanya boleh finish().
        var tertutup = false
        val bos = object : ByteArrayOutputStream() {
            override fun close() { tertutup = true; super.close() }
        }
        CbzWriter.tulis(bos, listOf(CbzWriter.Halaman("0001.png", isi(10, 1))))
        assertFalse("tulis() tidak boleh menutup aliran pemanggil", tertutup)
        assertTrue("arsip tetap lengkap", bos.toByteArray().size > 10)
    }

    @Test
    fun arsipKosongTetapZipSah() {
        val bos = ByteArrayOutputStream()
        CbzWriter.tulis(bos, emptyList())
        assertEquals(0, bacaUlang(bos.toByteArray()).size)
        // Direktori pusat kosong = 22 byte; kalau finish() hilang, ukurannya 0.
        assertTrue(bos.toByteArray().size >= 22)
    }

    // ---------- penamaan ----------

    @Test
    fun namaEntriBerlapisNolSehinggaUrutanTeksBenar() {
        val nama = (0 until 12).map { CbzWriter.namaEntri(it, "page.png") }
        assertEquals("0001.png", nama.first())
        assertEquals("0012.png", nama.last())
        // Inti masalahnya: urutan leksikografis harus sama dengan urutan halaman.
        assertEquals(nama, nama.sorted())
    }

    @Test
    fun namaEntriMempertahankanEkstensiAsli() {
        assertEquals("0001.jpg", CbzWriter.namaEntri(0, "halaman 3.jpg"))
        assertEquals("0002.webp", CbzWriter.namaEntri(1, "x.WEBP"))
        assertEquals("0003.png", CbzWriter.namaEntri(2, "tanpa_ekstensi"))
    }

    @Test
    fun namaArsipMenyingkirkanKarakterTerlarang() {
        val n = CbzWriter.namaArsip("Bab 12: Awal/Akhir?", "id")
        assertFalse(n.contains(':'))
        assertFalse(n.contains('/'))
        assertFalse(n.contains('?'))
        assertTrue(n.endsWith("_ID.cbz"))
    }

    @Test
    fun namaProyekKosongTidakMenghasilkanBerkasTanpaNama() {
        val n = CbzWriter.namaArsip("   ", "en")
        assertEquals("cypy_EN.cbz", n)
    }

    @Test
    fun namaProyekSangatPanjangDipotong() {
        val n = CbzWriter.namaArsip("x".repeat(400), "jp")
        // Banyak sistem berkas berhenti di 255 byte untuk satu komponen nama.
        assertTrue("nama harus dipotong, tapi ${n.length}", n.length <= 130)
        assertTrue(n.endsWith(".cbz"))
    }
}
