package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matematika penggabungan keluaran tiga-kelas RT-DETR. Murni fungsi, tidak
 * menyentuh ONNX Runtime (pustaka native-nya tidak ada di unit test JVM).
 */
class RtMathTest {

    private fun b(x1: Int, y1: Int, x2: Int, y2: Int) = intArrayOf(x1, y1, x2, y2)

    @Test
    fun teksDalamBalonDipasangkanKeBalonnya() {
        val balon = listOf(b(100, 100, 400, 300), b(500, 600, 800, 800))
        val teks = listOf(b(150, 150, 350, 250), b(550, 650, 750, 750))
        val pasangan = RtMath.pairTextToBubbles(balon, teks)
        assertEquals(2, pasangan.size)
        assertNotNull(pasangan[0])
        assertNotNull(pasangan[1])
        assertTrue(pasangan[0]!!.contentEquals(b(150, 150, 350, 250)))
        assertTrue(pasangan[1]!!.contentEquals(b(550, 650, 750, 750)))
    }

    @Test
    fun beberapaKotakTeksDalamSatuBalonDigabung() {
        val balon = listOf(b(100, 100, 400, 400))
        // dua baris teks terpisah di balon yang sama
        val teks = listOf(b(150, 150, 350, 200), b(160, 250, 340, 300))
        val pasangan = RtMath.pairTextToBubbles(balon, teks)
        // kotak pembungkus harus menutupi keduanya
        assertTrue(pasangan[0]!!.contentEquals(b(150, 150, 350, 300)))
    }

    @Test
    fun balonKosongTidakDapatKotakTeks() {
        val balon = listOf(b(100, 100, 400, 300))
        val pasangan = RtMath.pairTextToBubbles(balon, emptyList())
        assertNull(pasangan[0])
    }

    @Test
    fun teksMilikBalonLainTidakSalahPasang() {
        val balon = listOf(b(0, 0, 200, 200), b(600, 600, 900, 900))
        val teks = listOf(b(650, 650, 850, 850))
        val pasangan = RtMath.pairTextToBubbles(balon, teks)
        assertNull(pasangan[0])
        assertNotNull(pasangan[1])
    }

    @Test
    fun teksLepasYangSudahDiDalamBalonDibuang() {
        val balon = listOf(b(100, 100, 500, 400))
        // Kotak ini 100% di dalam balon: tidak boleh diterjemahkan dua kali.
        val lepas = listOf(b(150, 150, 450, 350))
        val hasil = RtMath.refineFreeText(lepas, balon, 1080, 2400)
        assertTrue(hasil.isEmpty())
    }

    @Test
    fun teksLepasSejatiDipertahankan() {
        val balon = listOf(b(100, 100, 500, 400))
        // "EFFRONTÉ" melayang, jauh dari balon mana pun.
        val lepas = listOf(b(60, 1200, 500, 1400))
        val hasil = RtMath.refineFreeText(lepas, balon, 1080, 2400)
        assertEquals(1, hasil.size)
        assertTrue(hasil[0].contentEquals(b(60, 1200, 500, 1400)))
    }

    @Test
    fun teksLepasTerlaluLebarDitolak() {
        // Selebar 95% halaman: hampir pasti bilah UI, bukan teks komik.
        val lepas = listOf(b(10, 300, 1040, 380))
        val hasil = RtMath.refineFreeText(lepas, emptyList(), 1080, 2400)
        assertTrue(hasil.isEmpty())
    }

    @Test
    fun teksLepasTerlaluKecilDitolak() {
        val lepas = listOf(b(10, 10, 18, 18))
        val hasil = RtMath.refineFreeText(lepas, emptyList(), 1080, 2400)
        assertTrue(hasil.isEmpty())
    }

    @Test
    fun teksLepasKembarDibuang() {
        val a = b(100, 1000, 400, 1100)
        val hampirSama = b(105, 1005, 398, 1098)
        val hasil = RtMath.refineFreeText(listOf(a, hampirSama), emptyList(), 1080, 2400)
        assertEquals(1, hasil.size)
    }

    @Test
    fun unionMenambahHanyaKotakBaru() {
        val utama = listOf(b(100, 100, 400, 300))
        val tambahan = listOf(
            b(102, 98, 402, 302),      // kembaran -> tidak ditambah
            b(600, 600, 800, 800)      // baru -> ditambah
        )
        val hasil = RtMath.union(utama, tambahan)
        assertEquals(2, hasil.size)
        assertTrue(hasil[1].contentEquals(b(600, 600, 800, 800)))
    }

    @Test
    fun iouDanIrisanKonsisten() {
        val a = b(0, 0, 100, 100)
        val c = b(50, 0, 150, 100)
        assertEquals(5000, RtMath.intersection(a, c))
        assertEquals(1f / 3f, RtMath.iou(a, c), 0.001f)
        assertEquals(0, RtMath.intersection(a, b(200, 200, 300, 300)))
    }

    @Test
    fun isInsideMenghormatiAmbang() {
        val luar = b(0, 0, 100, 100)
        assertTrue(RtMath.isInside(b(10, 10, 50, 50), luar))
        // hanya seperempatnya yang tumpang tindih
        assertFalse(RtMath.isInside(b(50, 50, 150, 150), luar))
    }
}
