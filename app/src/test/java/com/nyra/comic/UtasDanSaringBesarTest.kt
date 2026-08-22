package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dua penyetelan kecepatan: jumlah utas inferensi, dan mode "inpaint hanya
 * teks besar".
 */
class UtasDanSaringBesarTest {

    // --- Utas -------------------------------------------------------------

    @Test
    fun tidakPernahMelebihiJumlahInti() {
        assertEquals(1, Utas.hitung(1))
        assertEquals(2, Utas.hitung(2))
        assertEquals(3, Utas.hitung(3))
    }

    @Test
    fun dibatasiEmpatDiPerangkatBanyakInti() {
        // big.LITTLE: memakai semua inti membuat inti cepat menunggu inti hemat
        // daya, jadi batasnya sengaja tidak mengikuti jumlah inti.
        assertEquals(4, Utas.hitung(8))
        assertEquals(4, Utas.hitung(16))
    }

    @Test
    fun minimalSatuUtas() {
        assertEquals(1, Utas.hitung(0))
        assertEquals(1, Utas.hitung(-4))
        assertEquals("batas ngawur tidak boleh menghasilkan 0 utas", 1, Utas.hitung(8, maks = 0))
    }

    @Test
    fun batasBisaDitimpa() {
        assertEquals(2, Utas.hitung(8, maks = 2))
        assertEquals(8, Utas.hitung(8, maks = 32))
    }

    @Test
    fun untukPerangkatMasukAkal() {
        val n = Utas.untukPerangkat()
        assertTrue("hasil harus 1..MAKS_BAWAAN", n in 1..Utas.MAKS_BAWAAN)
    }

    // --- saringBesar ------------------------------------------------------

    private val W = 1127
    private val H = 1600

    @Test
    fun sfxKecilDilewatiSfxBesarDikerjakan() {
        val luas = W * H
        // 1 % halaman = 18.032 px^2. Kotak 200x150 = 30.000 -> besar.
        val besar = intArrayOf(100, 100, 300, 250)
        // 60x40 = 2.400 -> kecil.
        val kecil = intArrayOf(600, 700, 660, 740)

        val hasil = InpaintMath.saringBesar(listOf(besar, kecil), W, H, 0.01f)

        assertEquals(1, hasil.size)
        assertEquals(100, hasil[0][0])
        assertTrue("sanity: ambang memang 1 % dari $luas", luas > 0)
    }

    @Test
    fun ambangNolMempertahankanSemua() {
        val boxes = listOf(
            intArrayOf(0, 0, 10, 10),
            intArrayOf(50, 50, 60, 60)
        )
        assertEquals(2, InpaintMath.saringBesar(boxes, W, H, 0f).size)
    }

    @Test
    fun halamanTanpaLuasTidakMembuangApaPun() {
        val boxes = listOf(intArrayOf(0, 0, 10, 10))
        assertEquals(1, InpaintMath.saringBesar(boxes, 0, 0, 0.01f).size)
    }

    @Test
    fun kotakRusakTidakLolos() {
        // Lebar/tinggi nol atau negatif bukan kotak yang bisa dibersihkan.
        val boxes = listOf(
            intArrayOf(10, 10, 10, 400),
            intArrayOf(10, 10, 400, 5)
        )
        assertTrue(InpaintMath.saringBesar(boxes, W, H, 0.001f).isEmpty())
    }
}
