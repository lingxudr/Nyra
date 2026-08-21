package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian penyaring kandidat watermark.
 *
 * Skenarionya meniru halaman komik sungguhan: watermark kecil di tepi yang
 * muncul di banyak halaman, berdampingan dengan dialog besar di tengah yang
 * tidak boleh ikut tersaring.
 */
class WatermarkMathTest {

    private val W = 800
    private val H = 1200

    /** Watermark khas: pita kecil menempel di tepi bawah. */
    private fun wm() = intArrayOf(300, 1150, 520, 1180)

    /** Dialog khas: kotak besar di tengah halaman. */
    private fun dialog() = intArrayOf(200, 400, 600, 700)

    @Test
    fun watermarkBerulangDisarankan() {
        val lain = listOf(listOf(wm()), listOf(wm()), listOf(wm()))
        val hasil = WatermarkMath.cari(listOf(wm(), dialog()), W, H, lain)

        assertEquals("hanya watermark yang boleh disarankan", 1, hasil.size)
        assertTrue(hasil[0].kotak.contentEquals(wm()))
        assertEquals("ulang", hasil[0].alasan)
        assertTrue("skor harus tinggi: ${hasil[0].skor}", hasil[0].skor > 0.8f)
    }

    @Test
    fun dialogDiTengahTidakPernahDisarankan() {
        // Bahkan bila dialog kebetulan muncul di posisi sama di tiap halaman,
        // ukurannya menggugurkan dia lewat LUAS_MAKS.
        val lain = listOf(listOf(dialog()), listOf(dialog()), listOf(dialog()))
        val hasil = WatermarkMath.cari(listOf(dialog()), W, H, lain)
        assertTrue("dialog besar tidak boleh jadi kandidat", hasil.isEmpty())
    }

    @Test
    fun teksDidalamBalonDibuang() {
        val balon = intArrayOf(280, 1140, 540, 1190)
        val hasil = WatermarkMath.cari(
            listOf(wm()), W, H,
            halamanLain = listOf(listOf(wm()), listOf(wm())),
            bubbles = listOf(balon),
        )
        assertTrue("teks di dalam balon bukan watermark", hasil.isEmpty())
    }

    @Test
    fun tanpaHalamanLainHanyaTepiYangLolos() {
        // Satu halaman saja: tidak ada bukti pengulangan.
        val diTepi = intArrayOf(20, 1160, 200, 1185)
        val diTengah = intArrayOf(380, 600, 430, 620)

        val hasil = WatermarkMath.cari(listOf(diTepi, diTengah), W, H)
        val kotakHasil = hasil.map { it.kotak.toList() }

        assertTrue("kotak di tepi harus lolos", kotakHasil.contains(diTepi.toList()))
        assertFalse("kotak kecil di tengah tidak boleh lolos", kotakHasil.contains(diTengah.toList()))
    }

    @Test
    fun toleransiPosisiMemaafkanGeseranKecil() {
        val a = wm()
        // Watermark yang sama, digeser beberapa piksel seperti pada pemindaian.
        val b = intArrayOf(a[0] + 8, a[1] + 5, a[2] + 8, a[3] + 5)
        assertTrue("geseran kecil harus dianggap posisi sama", WatermarkMath.posisiSama(a, b, W, H))

        val jauh = intArrayOf(a[0] + 300, a[1], a[2] + 300, a[3])
        assertFalse("geseran besar bukan posisi sama", WatermarkMath.posisiSama(a, jauh, W, H))
    }

    @Test
    fun hasilTerurutDariSkorTertinggi() {
        val kuat = wm()
        // Sama-sama kecil dan di tepi, tapi tanpa bukti pengulangan.
        val lemah = intArrayOf(10, 1120, 160, 1150)
        val lain = listOf(listOf(kuat), listOf(kuat))

        val hasil = WatermarkMath.cari(listOf(lemah, kuat), W, H, lain)
        assertTrue("harus ada minimal dua kandidat", hasil.size >= 2)
        for (i in 1 until hasil.size) {
            assertTrue("urutan skor harus menurun", hasil[i - 1].skor >= hasil[i].skor)
        }
        assertTrue("yang berulang harus di puncak", hasil[0].kotak.contentEquals(kuat))
    }

    @Test
    fun ukuranEkstremDitolak() {
        val raksasa = intArrayOf(0, 0, W, H)
        val debu = intArrayOf(400, 600, 401, 601)
        val hasil = WatermarkMath.cari(listOf(raksasa, debu), W, H)
        assertTrue("kotak raksasa dan bintik debu harus ditolak", hasil.isEmpty())
    }

    @Test
    fun masukanKosongAman() {
        assertTrue(WatermarkMath.cari(emptyList(), W, H).isEmpty())
        assertTrue(WatermarkMath.cari(listOf(wm()), 0, 0).isEmpty())
        // Kotak terbalik tidak boleh melempar.
        assertTrue(WatermarkMath.cari(listOf(intArrayOf(50, 50, 10, 10)), W, H).isEmpty())
    }

    @Test
    fun jarakTepiDanLuasTerukurBenar() {
        // Kotak menempel tepi kiri.
        assertEquals(0f, WatermarkMath.jarakTepi(intArrayOf(0, 500, 100, 520), W, H), 1e-4f)
        // Kotak 80x120 pada halaman 800x1200 = 1% luas.
        val luas = WatermarkMath.rasioLuas(intArrayOf(0, 0, 80, 120), W, H)
        assertEquals(0.01f, luas, 1e-4f)
    }
}
