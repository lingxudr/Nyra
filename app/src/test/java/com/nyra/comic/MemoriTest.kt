package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ronde 39. Aplikasi mati di tengah bab 18 halaman (137 balon, 7 request,
 * requestParalel=4): log berhenti persis saat gelombang KEDUA mulai, tanpa
 * satu pun baris error. Ketiadaan pesan itulah petunjuknya — kalau ada
 * exception, penangkap di TranslationService pasti mencetaknya. Yang mati
 * adalah prosesnya sendiri, dibunuh sistem karena memori.
 *
 * Tes ini mengunci rem yang mencegahnya: berapa request boleh jalan bersamaan
 * dihitung dari memori yang tersedia, bukan dari setelan pengguna semata.
 */
class MemoriTest {

    /** Heap lega: setelan pengguna dihormati apa adanya. */
    @Test
    fun heapLegaTidakMenurunkanSetelan() {
        val biaya = 30L * 1024 * 1024
        assertEquals(4, Memori.izinkan(4, 1024L * 1024 * 1024, biaya))
    }

    /**
     * Kondisi lapangan yang bikin FC: heap sisa 192 MB, satu request menahan
     * ~32 MB. Dengan porsi aman 45%, anggarannya ~86 MB, jadi hanya 2 request
     * yang muat — bukan 4 seperti yang diminta setelan.
     */
    @Test
    fun heapSempitMenurunkanParalel() {
        val sisa = 192L * 1024 * 1024
        val biaya = 32L * 1024 * 1024
        val n = Memori.izinkan(4, sisa, biaya)
        assertTrue("harus turun dari 4, dapat $n", n < 4)
        assertTrue("harus tetap jalan, dapat $n", n >= 1)
    }

    /** Sekalipun satu request saja tidak muat, pekerjaan tidak boleh mustahil. */
    @Test
    fun selaluMinimalSatu() {
        assertEquals(1, Memori.izinkan(4, 1L, 999L * 1024 * 1024))
        assertEquals(1, Memori.izinkan(8, 0L, 50L * 1024 * 1024))
    }

    /** Batas atas tetap milik pengguna: memori longgar tidak menaikkannya. */
    @Test
    fun tidakPernahMelebihiPermintaan() {
        val longgar = 8L * 1024 * 1024 * 1024
        assertEquals(1, Memori.izinkan(1, longgar, 1L))
        assertEquals(2, Memori.izinkan(2, longgar, 1L))
        assertEquals(8, Memori.izinkan(99, longgar, 1L))
    }

    /**
     * Heap sangat besar dibagi biaya sangat kecil pernah melebihi Int.MAX_VALUE
     * dan membungkus jadi negatif, sehingga hasilnya justru 1 — kebalikan dari
     * maksudnya. Dijepit sebagai Long lebih dulu.
     */
    @Test
    fun heapRaksasaTidakMembungkusJadiNegatif() {
        assertEquals(8, Memori.izinkan(8, Long.MAX_VALUE / 4, 1L))
        assertEquals(4, Memori.izinkan(4, 64L * 1024 * 1024 * 1024, 8L))
    }

    /** Nilai di luar rentang dijepit seperti coerceIn(1,8) yang digantikannya. */
    @Test
    fun rentangDijepit() {
        val longgar = 8L * 1024 * 1024 * 1024
        assertEquals(1, Memori.izinkan(0, longgar, 1L))
        assertEquals(1, Memori.izinkan(-5, longgar, 1L))
    }

    /** Mosaik lebih tinggi = biaya lebih besar; monoton, tidak pernah nol. */
    @Test
    fun biayaIkutTinggiMosaik() {
        val kecil = Memori.biayaPerRequest(2000)
        val besar = Memori.biayaPerRequest(6000)
        assertTrue("biaya harus positif", kecil > 0L)
        assertTrue("mosaik lebih tinggi harus lebih mahal", besar > kecil)
    }

    /**
     * Angka nyata: mosaik 6000 px tidak mungkin ditaksir di bawah 20 MB.
     * Taksiran yang terlalu optimistis persis yang membuat proses dibunuh.
     */
    @Test
    fun biayaTidakDiremehkan() {
        val b = Memori.biayaPerRequest(6000)
        assertTrue("terlalu optimistis: $b", b >= 20L * 1024 * 1024)
    }
}
