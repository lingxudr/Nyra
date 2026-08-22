package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pencatat durasi per tahap. */
class DurasiTest {

    @Test
    fun kosongSaatBelumAdaCatatan() {
        val d = Durasi()
        assertTrue(d.kosong())
        assertTrue("tanpa catatan tidak ada yang dilaporkan", d.ringkasan(1000).isEmpty())
        assertEquals(0L, d.totalMs(Durasi.Tahap.API))
    }

    @Test
    fun catatanDijumlahkanPerTahap() {
        val d = Durasi()
        d.catat(Durasi.Tahap.API, 100)
        d.catat(Durasi.Tahap.API, 250)
        d.catat(Durasi.Tahap.INPAINT, 900)

        assertEquals(350L, d.totalMs(Durasi.Tahap.API))
        assertEquals(2, d.jumlahPanggilan(Durasi.Tahap.API))
        assertEquals(900L, d.totalMs(Durasi.Tahap.INPAINT))
        assertEquals(1, d.jumlahPanggilan(Durasi.Tahap.INPAINT))
        assertEquals(1250L, d.totalSemuaMs())
        assertFalse(d.kosong())
    }

    @Test
    fun durasiNegatifDiabaikan() {
        // Jam sistem bisa mundur; jangan sampai totalnya jadi ngawur.
        val d = Durasi()
        d.catat(Durasi.Tahap.RENDER, -50)
        assertEquals(0L, d.totalMs(Durasi.Tahap.RENDER))
        assertEquals(0, d.jumlahPanggilan(Durasi.Tahap.RENDER))
    }

    @Test
    fun ukurMengembalikanNilaiBlok() {
        val d = Durasi()
        val hasil = d.ukur(Durasi.Tahap.DETEKSI) { 6 * 7 }
        assertEquals(42, hasil)
        assertEquals(1, d.jumlahPanggilan(Durasi.Tahap.DETEKSI))
    }

    @Test
    fun ukurTetapMencatatSaatBlokMelempar() {
        val d = Durasi()
        runCatching { d.ukur(Durasi.Tahap.OCR) { throw IllegalStateException("gagal") } }
        assertEquals(
            "tahap yang gagal tetap memakan waktu, jadi tetap dicatat",
            1, d.jumlahPanggilan(Durasi.Tahap.OCR)
        )
    }

    @Test
    fun ringkasanTerurutDariYangPalingMahal() {
        val d = Durasi()
        d.catat(Durasi.Tahap.RENDER, 100)
        d.catat(Durasi.Tahap.INPAINT, 5000)
        d.catat(Durasi.Tahap.API, 1200)

        val baris = d.ringkasan(6300)

        assertEquals("[Rincian waktu]", baris[0])
        assertTrue("inpaint harus di puncak", baris[1].contains(Durasi.Tahap.INPAINT.label))
        assertTrue(baris[2].contains(Durasi.Tahap.API.label))
        assertTrue(baris[3].contains(Durasi.Tahap.RENDER.label))
    }

    @Test
    fun tahapTanpaCatatanTidakMunculDiRingkasan() {
        val d = Durasi()
        d.catat(Durasi.Tahap.API, 500)
        val teks = d.ringkasan(500).joinToString("\n")
        assertTrue(teks.contains(Durasi.Tahap.API.label))
        assertFalse("tahap 0 detik hanya menambah kebisingan",
            teks.contains(Durasi.Tahap.INPAINT.label))
    }

    @Test
    fun persentaseDihitungTerhadapJamDinding() {
        val d = Durasi()
        d.catat(Durasi.Tahap.INPAINT, 5000)
        val baris = d.ringkasan(10_000)
        assertTrue("5 dari 10 detik = 50 %", baris[1].contains("50.0 %"))
    }

    @Test
    fun paralelismeDitandaiBukanDisembunyikan() {
        // Tahap paralel bisa membuat jumlah durasi melebihi jam dinding.
        // Itu fakta yang harus dijelaskan, bukan dinormalisasi diam-diam.
        val d = Durasi()
        d.catat(Durasi.Tahap.API, 8000)
        d.catat(Durasi.Tahap.INPAINT, 8000)
        val teks = d.ringkasan(10_000).joinToString("\n")
        assertTrue(teks.contains("paralel"))
    }

    @Test
    fun bersihkanMengosongkanCatatanLama() {
        // Satu Pipeline bisa dipakai beberapa kali; angka proses sebelumnya
        // tidak boleh bocor ke ringkasan proses berikutnya.
        val d = Durasi()
        d.catat(Durasi.Tahap.INPAINT, 9000)
        d.bersihkan()

        assertTrue(d.kosong())
        assertEquals(0L, d.totalMs(Durasi.Tahap.INPAINT))
        assertEquals(0, d.jumlahPanggilan(Durasi.Tahap.INPAINT))

        d.catat(Durasi.Tahap.INPAINT, 100)
        assertEquals("hanya proses baru yang terhitung", 100L, d.totalSemuaMs())
    }

    @Test
    fun jamDindingNolTidakMembagiNol() {
        val d = Durasi()
        d.catat(Durasi.Tahap.API, 100)
        val baris = d.ringkasan(0)
        assertTrue(baris.size >= 2)
        assertTrue(baris[1].contains("0.0 %"))
    }
}
