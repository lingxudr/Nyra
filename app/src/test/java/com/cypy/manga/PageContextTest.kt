package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Akumulasi riwayat halaman dan pemangkasannya terhadap anggaran token. */
class PageContextTest {

    @Test
    fun kosongMenghasilkanPromptKosong() {
        assertEquals("", PageContext().promptSection())
    }

    @Test
    fun halamanTercatatDanMasukPrompt() {
        val c = PageContext()
        c.add("001.png", listOf("Halo, Pellin!", "Kau terlambat lagi."))
        val p = c.promptSection()
        assertTrue(p.contains("PREVIOUS PAGES CONTEXT"))
        assertTrue(p.contains("001.png"))
        assertTrue(p.contains("Halo, Pellin!"))
        assertTrue(p.contains("Kau terlambat lagi."))
    }

    /**
     * Tanpa larangan eksplisit, model gampang ikut menerjemahkan ulang baris
     * konteks dan memasukkannya ke jawaban JSON dengan nomor karangan.
     */
    @Test
    fun promptMelarangModelMenjawabBarisKonteks() {
        val c = PageContext()
        c.add("001.png", listOf("Halo"))
        val p = c.promptSection()
        assertTrue(p.contains("do NOT translate"))
        assertTrue(p.contains("background only"))
    }

    @Test
    fun barisKosongDanSkipDibuang() {
        val c = PageContext()
        c.add("001.png", listOf("", "   ", "SKIP", "skip", "Nyata"))
        val p = c.promptSection()
        assertTrue(p.contains("Nyata"))
        assertFalse(p.contains("SKIP"))
        assertEquals(1, c.size)
    }

    @Test
    fun halamanTanpaIsiTidakDicatatSamaSekali() {
        val c = PageContext()
        c.add("001.png", listOf("", "SKIP"))
        assertEquals(0, c.size)
        assertEquals("", c.promptSection())
    }

    @Test
    fun barisTerlaluPanjangDipotong() {
        val c = PageContext()
        c.add("001.png", listOf("x".repeat(400)))
        val p = c.promptSection()
        assertTrue("harus ada penanda potong", p.contains("…"))
        assertFalse(p.contains("x".repeat(400)))
    }

    // ---------- anggaran ----------

    @Test
    fun halamanTerlamaDibuangSaatAnggaranPenuh() {
        val c = PageContext(budgetTokens = 60)
        repeat(20) { i ->
            c.add("hal$i.png", listOf("Baris panjang nomor $i untuk memakan anggaran token"))
        }
        val p = c.promptSection()
        assertFalse("halaman pertama harus sudah terbuang", p.contains("hal0.png"))
        assertTrue("halaman terakhir harus bertahan", p.contains("hal19.png"))
        assertTrue("harus tersisa sesuatu", c.size in 1..19)
    }

    /**
     * Satu halaman yang sendirian melebihi anggaran tidak boleh membuat
     * riwayat jadi kosong terus-menerus - itu mematikan fitur secara diam-diam
     * pada bab yang padat teks.
     */
    @Test
    fun satuHalamanRaksasaTetapDisimpan() {
        val c = PageContext(budgetTokens = 10)
        c.add("besar.png", List(50) { "Kalimat panjang sekali nomor $it" })
        assertEquals(1, c.size)
        assertTrue(c.promptSection().contains("besar.png"))
    }

    @Test
    fun jumlahHalamanDibatasiMeskiAnggaranLonggar() {
        val c = PageContext(budgetTokens = 1_000_000)
        repeat(40) { c.add("h$it.png", listOf("ya")) }
        assertEquals(PageContext.MAX_PAGES, c.size)
        assertTrue(c.promptSection().contains("h39.png"))
        assertFalse(c.promptSection().contains("h0.png"))
    }

    @Test
    fun clearMengosongkanRiwayat() {
        val c = PageContext()
        c.add("001.png", listOf("Halo"))
        c.clear()
        assertEquals(0, c.size)
        assertEquals("", c.promptSection())
    }

    @Test
    fun urutanHalamanDipertahankanDariTerlamaKeTerbaru() {
        val c = PageContext()
        c.add("a.png", listOf("satu"))
        c.add("b.png", listOf("dua"))
        val p = c.promptSection()
        assertTrue(p.indexOf("a.png") < p.indexOf("b.png"))
    }

    // ---------- perkiraan token ----------

    @Test
    fun perkiraanTokenMenghitungCjkLebihBerat() {
        val latin = PageContext.estimateTokens("abcdefghij")
        val cjk = PageContext.estimateTokens("あいうえおかきくけこ")
        assertTrue("CJK harus dihitung lebih berat per karakter", cjk > latin)
    }

    @Test
    fun perkiraanTokenNaikSeiringPanjang() {
        val pendek = PageContext.estimateTokens("halo")
        val panjang = PageContext.estimateTokens("halo ".repeat(100))
        assertTrue(panjang > pendek)
        assertEquals(0, PageContext.estimateTokens(""))
    }
}
