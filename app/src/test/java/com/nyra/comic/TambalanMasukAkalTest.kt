package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penjaga keluaran LaMa. Diukur dari kasus nyata: halaman sumber murni
 * hitam-putih (R=G=B=222.8) tetapi area tambalan kembali sebagai zaitun gelap
 * (R=51.3 G=63.2 B=48.6). Lebih baik teks aslinya tertinggal daripada artwork
 * tertutup bidang datar berwarna.
 */
class TambalanMasukAkalTest {

    private fun abu(v: Int, n: Int) = IntArray(n) { (0xFF shl 24) or (v shl 16) or (v shl 8) or v }

    private fun warna(r: Int, g: Int, b: Int, n: Int) =
        IntArray(n) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    @Test
    fun statistikMengukurTerangDanSaturasi() {
        val (terang, sat) = InpaintMath.statistik(abu(200, 16))
        assertEquals(200f, terang, 0.01f)
        assertEquals("abu-abu tidak punya saturasi", 0f, sat, 0.01f)

        val (t2, s2) = InpaintMath.statistik(warna(51, 63, 48, 16))
        assertEquals(54f, t2, 0.5f)
        assertEquals(15f, s2, 0.5f)
    }

    @Test
    fun statistikKosongTidakMeledak() {
        val (t, s) = InpaintMath.statistik(IntArray(0))
        assertEquals(0f, t, 0f)
        assertEquals(0f, s, 0f)
    }

    @Test
    fun tambalanZaitunDiHalamanHitamPutihDitolak() {
        // Persis angka lapangan.
        assertFalse(
            InpaintMath.tambalanMasukAkal(
                terangSekitar = 222.8f, satSekitar = 0f,
                terangTambalan = 54.4f, satTambalan = 15f
            )
        )
    }

    @Test
    fun tambalanWajarDiterima() {
        // Tambalan bersih pada balon putih: sedikit lebih terang, tanpa warna.
        assertTrue(
            InpaintMath.tambalanMasukAkal(
                terangSekitar = 222.8f, satSekitar = 0f,
                terangTambalan = 240f, satTambalan = 1f
            )
        )
    }

    @Test
    fun halamanBerwarnaBolehMenerimaTambalanBerwarna() {
        // Manhwa berwarna: sekitarnya memang bersaturasi, jadi penjaga warna
        // tidak boleh ikut campur selama terangnya sepadan.
        assertTrue(
            InpaintMath.tambalanMasukAkal(
                terangSekitar = 150f, satSekitar = 60f,
                terangTambalan = 158f, satTambalan = 55f
            )
        )
    }

    @Test
    fun tambalanTerlaluGelapDitolakWalauTanpaWarna() {
        assertFalse(
            "bidang hitam datar di atas balon putih tetap merusak",
            InpaintMath.tambalanMasukAkal(
                terangSekitar = 225f, satSekitar = 0f,
                terangTambalan = 20f, satTambalan = 0f
            )
        )
    }

    @Test
    fun bedaTerangTepatDiAmbangMasihDiterima() {
        assertTrue(
            InpaintMath.tambalanMasukAkal(
                terangSekitar = 200f, satSekitar = 0f,
                terangTambalan = 200f - InpaintMath.BEDA_TERANG_MAKS + 0.5f,
                satTambalan = 0f
            )
        )
    }
}
