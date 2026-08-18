package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deteksi arah baca otomatis.
 *
 * Yang diuji adalah keputusannya, bukan angka skornya: nilai bobot boleh
 * disetel ulang selama kesimpulan untuk tata letak khas tiap format tetap
 * benar.
 */
class ReadingDirectionTest {

    private fun kotak(x1: Int, y1: Int, x2: Int, y2: Int) = intArrayOf(x1, y1, x2, y2)

    /** Blok teks tegak (tategaki): jauh lebih tinggi daripada lebar. */
    private fun tegak(n: Int): List<IntArray> =
        (0 until n).map { kotak(100 + it * 60, 100, 130 + it * 60, 300) }

    /** Blok teks mendatar: lebih lebar daripada tinggi. */
    private fun mendatar(n: Int): List<IntArray> =
        (0 until n).map { kotak(100, 100 + it * 60, 400, 130 + it * 60) }

    // ------------------------------------------------------------------
    // Isyarat dasar
    // ------------------------------------------------------------------

    @Test
    fun `halaman manga dengan tulisan tegak dibaca kanan ke kiri`() {
        val b = ReadingDirection.putuskan(1200, 1700, tegak(8), cadangan = false)
        assertTrue("tategaki harus meyakinkan", b.yakin)
        assertTrue("harus RTL, skor=${b.skor}", b.kananKeKiri)
    }

    @Test
    fun `strip webtoon panjang dibaca kiri ke kanan`() {
        // 1080x11700: rasio 10.8, jauh di atas STRIP_RASIO.
        val b = ReadingDirection.putuskan(1080, 11700, mendatar(10), cadangan = true)
        assertTrue("strip + teks mendatar harus meyakinkan", b.yakin)
        assertFalse("harus LTR meski cadangan RTL, skor=${b.skor}", b.kananKeKiri)
    }

    @Test
    fun `komik barat halaman biasa dengan teks mendatar dibaca kiri ke kanan`() {
        val b = ReadingDirection.putuskan(1300, 2000, mendatar(9), cadangan = true)
        assertTrue("teks mendatar pada 9 blok harus cukup", b.yakin)
        assertFalse(b.kananKeKiri)
    }

    /**
     * Regresi untuk keluhan aslinya: manhwa.
     *
     * Manhwa adalah strip vertikal dengan teks mendatar. Sebelum ronde ini,
     * bawaan aplikasi adalah RTL dan pengguna mendapat urutan terbalik tanpa
     * peringatan apa pun. Dua isyarat harus sepakat di sini.
     */
    @Test
    fun `manhwa mengabaikan bawaan RTL`() {
        val b = ReadingDirection.putuskan(800, 4000, mendatar(12), cadangan = true)
        assertFalse("manhwa harus LTR", b.kananKeKiri)
        assertTrue(b.skor < 0f)
    }

    // ------------------------------------------------------------------
    // Batas: kapan TIDAK boleh menebak
    // ------------------------------------------------------------------

    @Test
    fun `halaman tanpa blok teks memakai cadangan`() {
        val rtl = ReadingDirection.putuskan(1200, 1700, emptyList(), cadangan = true)
        assertFalse("tanpa bukti tidak boleh mengaku yakin", rtl.yakin)
        assertTrue("harus mengikuti cadangan", rtl.kananKeKiri)

        val ltr = ReadingDirection.putuskan(1200, 1700, emptyList(), cadangan = false)
        assertFalse(ltr.kananKeKiri)
    }

    @Test
    fun `sampel terlalu kecil tidak dianggap bukti`() {
        // 3 blok mendatar: di bawah MIN_BLOK, tidak boleh menggeser keputusan.
        val b = ReadingDirection.putuskan(1200, 1700, mendatar(3), cadangan = true)
        assertFalse("3 blok belum cukup", b.yakin)
        assertTrue(b.kananKeKiri)
    }

    @Test
    fun `sampel mendatar sedang belum cukup untuk membalik`() {
        // 5 blok: lolos MIN_BLOK tapi belum MIN_BLOK_MENDATAR.
        val b = ReadingDirection.putuskan(1200, 1700, mendatar(5), cadangan = true)
        assertFalse("5 blok mendatar belum boleh membalik keputusan", b.yakin)
        assertTrue(b.kananKeKiri)
    }

    // ------------------------------------------------------------------
    // Fungsi pembantu
    // ------------------------------------------------------------------

    @Test
    fun `fraksi tegak dihitung benar dan mengabaikan kotak cacat`() {
        val campur = tegak(3) + mendatar(1) + listOf(kotak(5, 5, 5, 5))
        // 3 tegak dari 4 blok sah; kotak berukuran nol dibuang.
        assertEquals(4, ReadingDirection.blokSah(campur))
        assertEquals(0.75f, ReadingDirection.fraksiTegak(campur), 0.001f)
    }

    @Test
    fun `fraksi tegak nol saat semua kotak cacat`() {
        val rusak = listOf(kotak(10, 10, 10, 20), kotak(0, 0, 0, 0))
        assertEquals(0, ReadingDirection.blokSah(rusak))
        assertEquals(0f, ReadingDirection.fraksiTegak(rusak), 0.001f)
    }

    @Test
    fun `strip dikenali dari rasio tinggi terhadap lebar`() {
        assertTrue(ReadingDirection.strip(1000, 2500))
        assertFalse(ReadingDirection.strip(1000, 2000))
        assertFalse("lebar nol tidak boleh membuat pembagian gagal",
            ReadingDirection.strip(0, 5000))
    }

    // ------------------------------------------------------------------
    // Kapan arah benar-benar berpengaruh
    // ------------------------------------------------------------------

    @Test
    fun `arah tidak berpengaruh bila balon tersusun menurun`() {
        val menurun = listOf(
            kotak(100, 100, 400, 200),
            kotak(100, 400, 400, 500),
            kotak(100, 700, 400, 800)
        )
        assertFalse(ReadingDirection.arahBerpengaruh(menurun))
    }

    @Test
    fun `arah berpengaruh bila ada dua balon sebaris`() {
        val sebaris = listOf(
            kotak(100, 100, 400, 300),
            kotak(600, 120, 900, 320)
        )
        assertTrue(ReadingDirection.arahBerpengaruh(sebaris))
    }

    /**
     * Konsistensi dengan pengurut sebenarnya: bila arahBerpengaruh mengatakan
     * "ya", membalik arah HARUS mengubah urutan keluaran. Kalau tidak, log yang
     * kita tampilkan ke pengguna menyesatkan.
     */
    @Test
    fun `arah berpengaruh sejalan dengan urutBaca`() {
        val sebaris = listOf(
            kotak(100, 100, 400, 300),
            kotak(600, 120, 900, 320)
        )
        assertTrue(ReadingDirection.arahBerpengaruh(sebaris))

        val rtl = BoxUtils.urutBaca(sebaris, true).map { it[0] }
        val ltr = BoxUtils.urutBaca(sebaris, false).map { it[0] }
        assertEquals(listOf(600, 100), rtl)
        assertEquals(listOf(100, 600), ltr)
    }

    @Test
    fun `label arah terbaca manusia`() {
        assertEquals("kanan-ke-kiri", ReadingDirection.label(true))
        assertEquals("kiri-ke-kanan", ReadingDirection.label(false))
    }
}
