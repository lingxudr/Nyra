package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private fun proyek(
        id: String = "prj_1",
        nama: String = "Bab 1",
        bahasa: String = "indonesian",
        diperbarui: Long = 1000L
    ): Project = Project(id, nama, bahasa, 0L, diperbarui)

    private fun halaman(kotak: Int, terjemahan: Map<String, String>): Project.Page {
        val p = Project.Page("a.png", "pages/p0000.png", 800, 1200)
        repeat(kotak) { i -> p.boxes.add(intArrayOf(i, i, i + 10, i + 10)) }
        p.translations.putAll(terjemahan)
        return p
    }

    // ------------------------------------------------------------------
    // Ringkasan
    // ------------------------------------------------------------------

    @Test
    fun `ringkasan menghitung halaman balon dan terjemahan`() {
        val p = proyek()
        p.pages.add(halaman(3, mapOf("1" to "halo", "2" to "dunia")))
        p.pages.add(halaman(2, mapOf("1" to "ya")))
        val e = Library.ringkas(p, 1024L)
        assertEquals(2, e.jumlahHalaman)
        assertEquals(5, e.jumlahBalon)
        assertEquals(3, e.jumlahTerjemahan)
    }

    @Test
    fun `terjemahan kosong tidak dihitung selesai`() {
        val p = proyek()
        // Lubang sungguhan: kotak terdeteksi tapi tidak ada teksnya.
        p.pages.add(halaman(3, mapOf("1" to "halo", "2" to "   ", "3" to "")))
        val e = Library.ringkas(p, 0L)
        assertEquals(3, e.jumlahBalon)
        assertEquals(1, e.jumlahTerjemahan)
        assertFalse(e.lengkap)
    }

    @Test
    fun `SKIP dihitung sebagai selesai karena itu keputusan sadar`() {
        val p = proyek()
        p.pages.add(halaman(2, mapOf("1" to "halo", "2" to "SKIP")))
        val e = Library.ringkas(p, 0L)
        assertEquals(2, e.jumlahTerjemahan)
        assertTrue(e.lengkap)
    }

    @Test
    fun `proyek tanpa balon dianggap lengkap bukan dibagi nol`() {
        val p = proyek()
        p.pages.add(halaman(0, emptyMap()))
        val e = Library.ringkas(p, 0L)
        assertEquals(1f, e.kelengkapan, 0f)
        assertTrue(e.lengkap)
    }

    @Test
    fun `kelengkapan tidak pernah melebihi satu walau terjemahan berlebih`() {
        val p = proyek()
        // Bisa terjadi kalau kotak dihapus di editor tapi terjemahannya tertinggal.
        p.pages.add(halaman(1, mapOf("1" to "a", "2" to "b", "3" to "c")))
        val e = Library.ringkas(p, 0L)
        assertEquals(1f, e.kelengkapan, 0f)
    }

    // ------------------------------------------------------------------
    // Urut & saring
    // ------------------------------------------------------------------

    @Test
    fun `urutan menempatkan yang terbaru di depan`() {
        val a = Library.ringkas(proyek("a", "A", diperbarui = 100L), 0)
        val b = Library.ringkas(proyek("b", "B", diperbarui = 300L), 0)
        val c = Library.ringkas(proyek("c", "C", diperbarui = 200L), 0)
        assertEquals(listOf("b", "c", "a"), Library.urutkan(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `saringan mengabaikan besar kecil huruf dan mencari bahasa juga`() {
        val a = Library.ringkas(proyek("a", "One Piece", "japanese"), 0)
        val b = Library.ringkas(proyek("b", "Naruto", "indonesian"), 0)
        assertEquals(listOf("a"), Library.saring(listOf(a, b), "piece").map { it.id })
        assertEquals(listOf("a"), Library.saring(listOf(a, b), "JAPAN").map { it.id })
        assertEquals(listOf("b"), Library.saring(listOf(a, b), "indo").map { it.id })
    }

    @Test
    fun `kueri kosong mengembalikan semua bukan nol`() {
        val a = Library.ringkas(proyek("a", "One Piece"), 0)
        val b = Library.ringkas(proyek("b", "Naruto"), 0)
        assertEquals(2, Library.saring(listOf(a, b), "").size)
        assertEquals(2, Library.saring(listOf(a, b), "   ").size)
    }

    // ------------------------------------------------------------------
    // Nama
    // ------------------------------------------------------------------

    @Test
    fun `nama dibersihkan dari pemisah jalur karena ikut jadi nama berkas`() {
        assertEquals("a b", Library.rapikanNama("a/b"))
        assertEquals("a b", Library.rapikanNama("a\\b"))
        assertFalse(Library.rapikanNama("../../etc/passwd").contains("/"))
    }

    @Test
    fun `nama dibersihkan dari karakter kendali`() {
        val hasil = Library.rapikanNama("Bab\n1\tDua\u0000")
        assertFalse(hasil.contains("\n"))
        assertFalse(hasil.contains("\t"))
        assertFalse(hasil.contains("\u0000"))
        assertEquals("Bab 1 Dua", hasil)
    }

    @Test
    fun `nama kosong jatuh ke cadangan`() {
        assertEquals("Proyek", Library.rapikanNama(""))
        assertEquals("Proyek", Library.rapikanNama("    "))
        assertEquals("Proyek", Library.rapikanNama("\n\n"))
        assertEquals("Bab", Library.rapikanNama("///", "Bab"))
    }

    @Test
    fun `nama panjang dipotong dan tidak menyisakan spasi di ujung`() {
        val hasil = Library.rapikanNama("x".repeat(200))
        assertEquals(Library.MAKS_NAMA, hasil.length)
        assertEquals(hasil.trim(), hasil)
    }

    @Test
    fun `nama wajar dibiarkan apa adanya`() {
        assertEquals("One Piece 1095", Library.rapikanNama("One Piece 1095"))
    }

    // ------------------------------------------------------------------
    // Format
    // ------------------------------------------------------------------

    @Test
    fun `ukuran ditampilkan dengan satuan yang masuk akal`() {
        assertEquals("512 B", Library.ukuranRingkas(512))
        assertEquals("2 KB", Library.ukuranRingkas(2048))
        assertEquals("1.0 MB", Library.ukuranRingkas(1024L * 1024))
        assertEquals("1.50 GB", Library.ukuranRingkas((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `umur diterjemahkan jadi kata kata`() {
        val kini = 1_000_000_000L
        assertEquals("baru saja", Library.umurRingkas(kini, kini))
        assertEquals("5 menit lalu", Library.umurRingkas(kini - 5 * 60_000L, kini))
        assertEquals("3 jam lalu", Library.umurRingkas(kini - 3 * 3_600_000L, kini))
        assertEquals("2 hari lalu", Library.umurRingkas(kini - 2 * 86_400_000L, kini))
    }

    @Test
    fun `waktu nol atau di masa depan tidak menghasilkan angka negatif`() {
        val kini = 1_000_000_000L
        // Proyek lama sebelum updatedAt dicatat, dan jam telepon yang mundur.
        assertEquals("baru saja", Library.umurRingkas(0L, kini))
        assertEquals("baru saja", Library.umurRingkas(kini + 99_999L, kini))
    }
}
