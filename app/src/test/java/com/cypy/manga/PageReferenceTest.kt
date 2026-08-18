package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gambar rujukan hanya berguna kalau ia benar-benar halaman asal SEMUA balon
 * dalam request itu. Chunk dipotong per 20 balon, bukan per halaman, jadi satu
 * request sangat mungkin bercampur dua halaman - dan melampirkan salah satunya
 * lebih buruk daripada tidak melampirkan apa pun.
 */
class PageReferenceTest {

    private fun k(u: Int, p: Int) = PageReference.Kunci(u, p)

    @Test
    fun satuHalamanUtuhMenghasilkanRujukan() {
        val hasil = PageReference.pilih(listOf(k(3, 0), k(3, 0), k(3, 0)))
        assertEquals(k(3, 0), hasil)
    }

    @Test
    fun campuranDuaHalamanTidakDapatRujukan() {
        assertNull(PageReference.pilih(listOf(k(3, 0), k(4, 0))))
    }

    @Test
    fun bagianSplitBerbedaDihitungHalamanBerbeda() {
        // Halaman lebar dipecah dua; kiri dan kanan adalah gambar yang berbeda.
        assertNull(PageReference.pilih(listOf(k(3, 0), k(3, 1))))
    }

    @Test
    fun daftarKosongTidakDapatRujukan() {
        assertNull(PageReference.pilih(emptyList()))
    }

    @Test
    fun satuPotonganTunggalTetapDapatRujukan() {
        assertEquals(k(0, 0), PageReference.pilih(listOf(k(0, 0))))
    }

    @Test
    fun campuranYangHalamanBerbedanyaDiAkhirTetapTerdeteksi() {
        val banyak = List(19) { k(2, 0) } + k(5, 0)
        assertNull("beda di elemen terakhir harus tetap ketahuan", PageReference.pilih(banyak))
    }

    @Test
    fun promptMenjelaskanGambarKeduaBukanSumberId() {
        val t = PageReference.promptSection()
        assertTrue(t.contains("TWO images"))
        assertTrue("harus menegaskan mosaik yang punya ID", t.contains("FIRST image"))
        assertTrue("harus melarang menerjemahkan gambar kedua", t.contains("Do NOT translate"))
        assertTrue("harus melarang mengarang ID baru", t.contains("Do NOT invent new ids"))
    }
}
