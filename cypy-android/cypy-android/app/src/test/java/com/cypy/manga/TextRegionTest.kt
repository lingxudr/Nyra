package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detektor teks lepas-balon (PP-OCRv5 det).
 *
 * Angka acuan diukur dengan model sungguhan pada halaman yang dikirim
 * pengguna, memakai replika Python di /home/user/probe.
 */
class TextRegionTest {

    // ---------- bentuk masukan ----------

    @Test
    fun inputSizeIsAlwaysAMultipleOf32() {
        val kasus = listOf(1080 to 2400, 760 to 1100, 282 to 409, 1500 to 1000, 1080 to 11700)
        for ((w, h) in kasus) {
            val (iw, ih) = TextRegionMath.inputSizeFor(w, h)
            assertEquals("lebar $w -> $iw harus kelipatan 32", 0, iw % 32)
            assertEquals("tinggi $h -> $ih harus kelipatan 32", 0, ih % 32)
            assertTrue("sisi tidak boleh nol", iw >= 32 && ih >= 32)
        }
    }

    @Test
    fun inputSizeKeepsAspectRatioAndRespectsTheLimit() {
        val (w, h) = TextRegionMath.inputSizeFor(1080, 2400)
        assertTrue("sisi terpanjang tidak boleh jauh melebihi batas", h <= TextRegionMath.OCR_LIMIT + 32)
        val rasioAsli = 2400f / 1080f
        val rasioBaru = h.toFloat() / w.toFloat()
        assertTrue("rasio aspek harus terjaga: $rasioAsli vs $rasioBaru",
            kotlin.math.abs(rasioAsli - rasioBaru) < 0.15f)
    }

    @Test
    fun tinyImageIsNotSnappedToZero() {
        val (w, h) = TextRegionMath.inputSizeFor(10, 8)
        assertTrue("gambar mungil harus tetap punya sisi >= 32", w >= 32 && h >= 32)
    }

    // ---------- jangan menerjemahkan dua kali ----------

    @Test
    fun textAlreadyInsideABubbleIsDropped() {
        val balon = listOf(intArrayOf(100, 100, 500, 400))
        val diDalam = intArrayOf(150, 150, 450, 250)
        val diLuar = intArrayOf(600, 800, 900, 880)
        val sisa = TextRegionMath.dropInsideBubbles(listOf(diDalam, diLuar), balon)
        assertEquals("hanya teks di luar balon yang tersisa", 1, sisa.size)
        assertEquals(600, sisa[0][0])
    }

    /** Teks yang cuma menyerempet tepi balon tetap harus diterjemahkan. */
    @Test
    fun textOnlyClippingABubbleEdgeSurvives() {
        val balon = listOf(intArrayOf(100, 100, 500, 400))
        // 20% saja yang tumpang tindih.
        val nyerempet = intArrayOf(420, 350, 820, 450)
        val sisa = TextRegionMath.dropInsideBubbles(listOf(nyerempet), balon)
        assertEquals("teks yang hanya menyerempet harus bertahan", 1, sisa.size)
    }

    @Test
    fun coveredFractionIsMeasuredAgainstTheTextNotTheBubble() {
        val teksKecil = intArrayOf(0, 0, 100, 100)
        val balonBesar = intArrayOf(0, 0, 1000, 1000)
        assertEquals("teks kecil di dalam balon besar = tertutup penuh",
            1.0f, TextRegionMath.coveredFraction(teksKecil, balonBesar), 0.001f)
        assertTrue("kebalikannya harus kecil",
            TextRegionMath.coveredFraction(balonBesar, teksKecil) < 0.02f)
    }

    // ---------- penggabungan ----------

    /**
     * PP-OCR memecah teks Jepang vertikal jadi banyak serpihan. Tanpa
     * penggabungan, satu kalimat jadi belasan permintaan terjemahan terpisah
     * yang kehilangan konteks satu sama lain.
     */
    @Test
    fun verticalJapaneseFragmentsBecomeOneBlock() {
        val serpihan = listOf(
            intArrayOf(100, 100, 140, 150),
            intArrayOf(100, 155, 140, 205),
            intArrayOf(100, 210, 140, 260),
            intArrayOf(100, 265, 140, 315)
        )
        val blok = TextRegionMath.groupNearby(serpihan)
        assertEquals("empat serpihan sejajar harus jadi satu blok", 1, blok.size)
        assertEquals(100, blok[0][1])
        assertEquals(315, blok[0][3])
    }

    @Test
    fun distantTextBlocksStayApart() {
        val jauh = listOf(
            intArrayOf(100, 100, 300, 140),
            intArrayOf(100, 900, 300, 940)
        )
        assertEquals("blok yang berjauhan tidak boleh digabung",
            2, TextRegionMath.groupNearby(jauh).size)
    }

    @Test
    fun groupingIsOrderIndependent() {
        val a = listOf(
            intArrayOf(100, 100, 140, 150),
            intArrayOf(100, 155, 140, 205),
            intArrayOf(500, 900, 560, 950)
        )
        val b = a.reversed()
        assertEquals(TextRegionMath.groupNearby(a).size, TextRegionMath.groupNearby(b).size)
    }

    // ---------- penyaringan derau ----------

    @Test
    fun specksAreDroppedButRealTextIsKept() {
        val w = 1080; val h = 2400
        val bintik = intArrayOf(10, 10, 18, 18)
        val teks = intArrayOf(100, 100, 400, 180)
        val hasil = TextRegionMath.dropNoise(listOf(bintik, teks), w, h)
        assertEquals("bintik dibuang, teks bertahan", 1, hasil.size)
        assertEquals(100, hasil[0][0])
    }

    @Test
    fun panelWideBandsAreNotTreatedAsText() {
        val w = 1080; val h = 2400
        val pita = intArrayOf(5, 500, 1075, 560)
        assertTrue("pita selebar halaman harus dibuang",
            TextRegionMath.dropNoise(listOf(pita), w, h).isEmpty())
    }

    // ---------- rangkaian penuh ----------

    @Test
    fun refineKeepsReadingOrderTopToBottom() {
        val balon = listOf(intArrayOf(0, 0, 10, 10))
        val teks = listOf(
            intArrayOf(100, 1800, 400, 1900),
            intArrayOf(100, 200, 400, 300),
            intArrayOf(100, 900, 400, 1000)
        )
        val hasil = TextRegionMath.refine(teks, balon, 1080, 2400)
        assertEquals(3, hasil.size)
        assertTrue("harus urut atas ke bawah",
            hasil[0][1] < hasil[1][1] && hasil[1][1] < hasil[2][1])
    }

    /**
     * Halaman biasa yang seluruh teksnya sudah berada di dalam balon tidak
     * boleh mendapat satu pun kotak tambahan — kalau tidak, jumlah balon
     * berubah dan paritas dengan cypy Python rusak.
     */
    @Test
    fun anOrdinaryPageGainsNothing() {
        val balon = listOf(
            intArrayOf(100, 100, 500, 400),
            intArrayOf(600, 700, 900, 1000)
        )
        val teksSemuaDiDalam = listOf(
            intArrayOf(150, 150, 450, 250),
            intArrayOf(650, 750, 850, 850)
        )
        assertTrue("tidak boleh ada tambahan pada halaman biasa",
            TextRegionMath.refine(teksSemuaDiDalam, balon, 900, 1119).isEmpty())
    }
}
