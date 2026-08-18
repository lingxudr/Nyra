package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometri inpaint. Model ONNX-nya sendiri tidak bisa dijalankan di unit test
 * (native lib onnxruntime tidak tersedia), jadi yang diuji adalah semua
 * keputusan yang menentukan benar-tidaknya hasil: petak, masker, pemaduan.
 */
class InpaintMathTest {

    // ---------- dilatasi masker ----------

    @Test
    fun dilatasiMemperbesarKeSegalaArah() {
        val b = InpaintMath.dilate(intArrayOf(100, 100, 200, 150), 10, 1000, 1000)
        assertEquals(90, b[0]); assertEquals(90, b[1])
        assertEquals(210, b[2]); assertEquals(160, b[3])
    }

    /** Kotak di pinggir halaman tidak boleh menghasilkan koordinat negatif. */
    @Test
    fun dilatasiDijepitKeTepiGambar() {
        val b = InpaintMath.dilate(intArrayOf(2, 3, 300, 400), 20, 320, 410)
        assertEquals(0, b[0]); assertEquals(0, b[1])
        assertEquals(320, b[2]); assertEquals(410, b[3])
    }

    @Test
    fun paddingIkutUkuranKotakDanAdaBatasnya() {
        val kecil = InpaintMath.maskPad(intArrayOf(0, 0, 20, 20))
        val sedang = InpaintMath.maskPad(intArrayOf(0, 0, 300, 300))
        val raksasa = InpaintMath.maskPad(intArrayOf(0, 0, 4000, 4000))
        assertTrue("kotak kecil tetap dapat pita minimum", kecil >= 3)
        assertTrue("kotak besar dapat pita lebih lebar", sedang > kecil)
        assertTrue("pita tidak boleh meledak", raksasa <= 24)
    }

    // ---------- pemilihan petak ----------

    @Test
    fun petakBerukuranModelDanMemuatKotak() {
        val box = intArrayOf(900, 900, 1000, 980)
        val t = InpaintMath.tileFor(box, 2000, 3000)
        assertEquals(InpaintMath.TILE, t.w)
        assertEquals(InpaintMath.TILE, t.h)
        assertTrue("kotak harus berada di dalam petak",
            box[0] >= t.x1 && box[1] >= t.y1 && box[2] <= t.x2 && box[3] <= t.y2)
    }

    @Test
    fun petakTidakKeluarDariGambar() {
        for (box in listOf(
            intArrayOf(0, 0, 40, 40),
            intArrayOf(1960, 2960, 2000, 3000),
            intArrayOf(0, 2900, 60, 3000)
        )) {
            val t = InpaintMath.tileFor(box, 2000, 3000)
            assertTrue("x1 >= 0", t.x1 >= 0)
            assertTrue("y1 >= 0", t.y1 >= 0)
            assertTrue("x2 <= lebar", t.x2 <= 2000)
            assertTrue("y2 <= tinggi", t.y2 <= 3000)
        }
    }

    /** Gambar kecil dipakai utuh, bukan dipaksa jadi petak 512. */
    @Test
    fun gambarLebihKecilDariPetakDipakaiUtuh() {
        val t = InpaintMath.tileFor(intArrayOf(10, 10, 50, 50), 300, 400)
        assertEquals(0, t.x1); assertEquals(0, t.y1)
        assertEquals(300, t.w); assertEquals(400, t.h)
    }

    /** Halaman webtoon sangat tinggi: petak menyempit ikut lebar gambar. */
    @Test
    fun stripSempitTidakMemaksaLebar512() {
        val t = InpaintMath.tileFor(intArrayOf(100, 5000, 300, 5100), 400, 11700)
        assertEquals(400, t.w)
        assertEquals(InpaintMath.TILE, t.h)
        assertTrue(t.y1 >= 0 && t.y2 <= 11700)
    }

    // ---------- pengelompokan ----------

    @Test
    fun kotakBerdekatanDigabungJadiSatuPetak() {
        val boxes = listOf(
            intArrayOf(500, 500, 560, 540),
            intArrayOf(580, 520, 640, 560),
            intArrayOf(600, 600, 660, 640)
        )
        val g = InpaintMath.groupIntoTiles(boxes, 2000, 2000)
        assertEquals("ketiganya muat dalam satu petak", 1, g.size)
        assertEquals(3, g[0].second.size)
    }

    @Test
    fun kotakBerjauhanTetapTerpisah() {
        val boxes = listOf(
            intArrayOf(50, 50, 100, 90),
            intArrayOf(1800, 1800, 1900, 1900)
        )
        val g = InpaintMath.groupIntoTiles(boxes, 2000, 2000)
        assertEquals(2, g.size)
    }

    /** Tiap kotak harus tercakup tepat sekali: bila tidak, ada teks tersisa. */
    @Test
    fun setiapKotakDiprosesTepatSekali() {
        val boxes = (0 until 25).map { i ->
            val x = (i % 5) * 380 + 20
            val y = (i / 5) * 380 + 20
            intArrayOf(x, y, x + 120, y + 80)
        }
        val g = InpaintMath.groupIntoTiles(boxes, 2000, 2000)
        val terlihat = g.flatMap { it.second }
        assertEquals("tidak boleh ada yang terlewat", boxes.size, terlihat.size)
        assertEquals("tidak boleh ada yang dobel", boxes.size, terlihat.toSet().size)
    }

    /** Anggota grup harus benar-benar muat, bukan sekadar dekat. */
    @Test
    fun anggotaGrupSelaluMuatDiPetaknya() {
        val boxes = (0 until 12).map { i ->
            intArrayOf(100 + i * 90, 200 + i * 40, 100 + i * 90 + 200, 200 + i * 40 + 150)
        }
        for ((t, anggota) in InpaintMath.groupIntoTiles(boxes, 3000, 3000)) {
            for (i in anggota) {
                val b = boxes[i]
                assertTrue("kotak $i harus muat penuh di petaknya",
                    b[0] >= t.x1 && b[1] >= t.y1 && b[2] <= t.x2 && b[3] <= t.y2)
            }
        }
    }

    /**
     * SFX bisa lebih lebar dari 512 px. Kalau petak memotongnya, sisa huruf
     * tertinggal di halaman - kegagalan nyata yang lolos dari uji kotak kecil.
     */
    @Test
    fun kotakLebihBesarDariPetakTetapTercakupPenuh() {
        for (b in listOf(
            intArrayOf(180, 300, 720, 430),
            intArrayOf(100, 100, 300, 900),
            intArrayOf(0, 0, 1390, 700)
        )) {
            val t = InpaintMath.tileFor(b, 1400, 2000)
            assertTrue("kotak ${b.joinToString()} harus muat penuh di petak",
                b[0] >= t.x1 && b[1] >= t.y1 && b[2] <= t.x2 && b[3] <= t.y2)
            assertTrue("petak tetap di dalam gambar",
                t.x1 >= 0 && t.y1 >= 0 && t.x2 <= 1400 && t.y2 <= 2000)
        }
    }

    /** Kotak selebar halaman: petak menyamai lebar gambar, bukan meluber. */
    @Test
    fun kotakSelebarHalamanTidakMeluber() {
        val t = InpaintMath.tileFor(intArrayOf(0, 500, 1400, 700), 1400, 2000)
        assertEquals(0, t.x1)
        assertEquals(1400, t.x2)
    }

    @Test
    fun daftarKosongTidakMenghasilkanPetak() {
        assertEquals(0, InpaintMath.groupIntoTiles(emptyList(), 800, 800).size)
    }

    // ---------- pemaduan tepi ----------

    @Test
    fun bobotPenuhDiTengahDanMengecilDiTepi() {
        val b = intArrayOf(0, 0, 200, 200)
        assertEquals(1f, InpaintMath.featherWeight(100, 100, b), 1e-6f)
        val tepi = InpaintMath.featherWeight(0, 100, b)
        assertTrue("tepi harus lebih ringan dari tengah", tepi < 1f)
        assertTrue("tetap positif", tepi > 0f)
    }

    @Test
    fun bobotNaikMonotonMenujuTengah() {
        val b = intArrayOf(0, 0, 200, 200)
        var prev = -1f
        for (x in 0..InpaintMath.FEATHER) {
            val w = InpaintMath.featherWeight(x, 100, b)
            assertTrue("bobot tidak boleh turun saat menjauh dari tepi", w >= prev)
            prev = w
        }
    }

    @Test
    fun pikselDiLuarKotakTidakBerbobot() {
        val b = intArrayOf(10, 10, 100, 100)
        assertEquals(0f, InpaintMath.featherWeight(5, 50, b), 1e-6f)
    }
}
