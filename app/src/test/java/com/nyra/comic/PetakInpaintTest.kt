package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengemasan petak inpaint. Satu inferensi LaMa memakan beberapa detik, jadi
 * jumlah petak adalah biaya sesungguhnya. Versi lama mengunci posisi petak
 * pada kotak pertama sehingga tetangga yang muat pun terlewat.
 */
class PetakInpaintTest {

    private val W = 1127
    private val H = 1600

    private fun semuaKotakTercakup(
        boxes: List<IntArray>,
        hasil: List<Pair<InpaintMath.Tile, List<Int>>>
    ) {
        val terlihat = hasil.flatMap { it.second }.toSet()
        assertEquals("setiap kotak harus masuk tepat satu petak", boxes.size, terlihat.size)
        for ((tile, anggota) in hasil) {
            for (i in anggota) {
                val b = boxes[i]
                assertTrue(
                    "kotak $i keluar dari petaknya",
                    b[0] >= tile.x1 && b[1] >= tile.y1 && b[2] <= tile.x2 && b[3] <= tile.y2
                )
            }
        }
    }

    @Test
    fun kotakBerdekatanDikemasJadiSatuPetak() {
        // Tiga SFX kecil dalam rentang < 512 px: cukup satu inferensi.
        val boxes = listOf(
            intArrayOf(600, 700, 660, 820),
            intArrayOf(700, 690, 754, 800),
            intArrayOf(800, 710, 860, 830)
        )
        val hasil = InpaintMath.groupIntoTiles(boxes, W, H)
        assertEquals("tiga kotak berdekatan cukup satu petak", 1, hasil.size)
        semuaKotakTercakup(boxes, hasil)
    }

    @Test
    fun urutanMasukanTidakMenambahPetak() {
        // Kotak pertama di tengah dulu, lalu tetangga di kiri dan kanannya.
        // Penempatan petak yang terkunci pada kotak pertama membuat salah satu
        // tetangga jatuh ke petak sendiri.
        val boxes = listOf(
            intArrayOf(500, 800, 560, 900),
            intArrayOf(340, 800, 400, 900),
            intArrayOf(720, 800, 780, 900)
        )
        val maju = InpaintMath.groupIntoTiles(boxes, W, H)
        val mundur = InpaintMath.groupIntoTiles(boxes.reversed(), W, H)
        assertEquals(1, maju.size)
        assertEquals("hasil tidak boleh bergantung urutan", maju.size, mundur.size)
        semuaKotakTercakup(boxes, maju)
    }

    @Test
    fun kotakBerjauhanTetapPetakTerpisah() {
        val boxes = listOf(
            intArrayOf(60, 80, 120, 200),
            intArrayOf(1000, 1400, 1060, 1520)
        )
        val hasil = InpaintMath.groupIntoTiles(boxes, W, H)
        assertEquals(2, hasil.size)
        semuaKotakTercakup(boxes, hasil)
    }

    @Test
    fun petakTidakPernahKeluarHalaman() {
        val boxes = listOf(
            intArrayOf(0, 0, 40, 60),
            intArrayOf(W - 40, H - 60, W, H)
        )
        val hasil = InpaintMath.groupIntoTiles(boxes, W, H)
        for ((t, _) in hasil) {
            assertTrue(t.x1 >= 0 && t.y1 >= 0)
            assertTrue(t.x2 <= W && t.y2 <= H)
        }
        semuaKotakTercakup(boxes, hasil)
    }

    @Test
    fun sfxLebihBesarDari512TetapUtuh() {
        // Petak wajib memuat seluruh kotak walau melebihi ukuran model.
        val besar = intArrayOf(200, 300, 900, 700)
        val hasil = InpaintMath.groupIntoTiles(listOf(besar), W, H)
        assertEquals(1, hasil.size)
        val t = hasil[0].first
        assertTrue("petak harus memuat SFX besar seutuhnya",
            t.x1 <= besar[0] && t.y1 <= besar[1] && t.x2 >= besar[2] && t.y2 >= besar[3])
    }
}
