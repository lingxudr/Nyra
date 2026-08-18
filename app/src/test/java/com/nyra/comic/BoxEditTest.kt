package com.nyra.comic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penyuntingan kotak: geser, ubah ukuran, tambah, hapus.
 *
 * Yang diuji di sini bukan sekadar aritmetika empat angka, melainkan hal yang
 * membuat fitur ini berbahaya: nomor kotak adalah KUNCI untuk translations,
 * sourceText, colors, dan freeText sekaligus penentu urutan baca. Satu
 * pergeseran nomor yang salah memindahkan terjemahan ke balon lain, dan
 * pengguna baru sadar setelah seluruh bab digambar ulang.
 */
class BoxEditTest {

    private fun page(vararg boxes: IntArray): Project.Page {
        val p = Project.Page("a.png", "pages/p0000.png", 1000, 1400)
        boxes.forEach { p.boxes.add(it) }
        return p
    }

    // ---------- rapikan ----------

    @Test
    fun tepiTerbalikDiluruskan() {
        val r = BoxEdit.rapikan(intArrayOf(300, 400, 100, 200), 1000, 1400)
        assertArrayEquals(intArrayOf(100, 200, 300, 400), r)
    }

    @Test
    fun kotakDijepitKeDalamGambar() {
        val r = BoxEdit.rapikan(intArrayOf(-50, -30, 1200, 1600), 1000, 1400)
        assertArrayEquals(intArrayOf(0, 0, 1000, 1400), r)
    }

    @Test
    fun sisiMinimumDipaksa() {
        val r = BoxEdit.rapikan(intArrayOf(500, 500, 502, 503), 1000, 1400)
        assertTrue("lebar minimal", r[2] - r[0] >= BoxEdit.MIN_SISI)
        assertTrue("tinggi minimal", r[3] - r[1] >= BoxEdit.MIN_SISI)
    }

    @Test
    fun sisiMinimumDiPojokKananBawahTidakKeluarGambar() {
        // Kotak sangat kecil menempel di tepi kanan-bawah: memaksa sisi minimum
        // dengan menambah x2 saja akan mendorongnya keluar kanvas.
        val r = BoxEdit.rapikan(intArrayOf(998, 1398, 1000, 1400), 1000, 1400)
        assertTrue("tetap di dalam", r[2] <= 1000 && r[3] <= 1400)
        assertTrue("tetap di dalam", r[0] >= 0 && r[1] >= 0)
        assertEquals(BoxEdit.MIN_SISI, r[2] - r[0])
        assertEquals(BoxEdit.MIN_SISI, r[3] - r[1])
    }

    // ---------- gagang ----------

    @Test
    fun sudutDikenaliSebagaiGagang() {
        val b = intArrayOf(100, 100, 300, 300)
        assertEquals(BoxEdit.Gagang.KIRI_ATAS, BoxEdit.gagangDi(b, 104f, 103f))
        assertEquals(BoxEdit.Gagang.KANAN_ATAS, BoxEdit.gagangDi(b, 296f, 104f))
        assertEquals(BoxEdit.Gagang.KIRI_BAWAH, BoxEdit.gagangDi(b, 102f, 297f))
        assertEquals(BoxEdit.Gagang.KANAN_BAWAH, BoxEdit.gagangDi(b, 298f, 299f))
    }

    @Test
    fun tengahAdalahIsiDanLuarTidakAda() {
        val b = intArrayOf(100, 100, 300, 300)
        assertEquals(BoxEdit.Gagang.ISI, BoxEdit.gagangDi(b, 200f, 200f))
        assertEquals(BoxEdit.Gagang.TIDAK_ADA, BoxEdit.gagangDi(b, 500f, 500f))
    }

    // ---------- geser ----------

    @Test
    fun geserMempertahankanUkuran() {
        val b = intArrayOf(100, 100, 300, 260)
        val r = BoxEdit.geser(b, 50, -40, 1000, 1400)
        assertArrayEquals(intArrayOf(150, 60, 350, 220), r)
    }

    @Test
    fun geserKeLuarBerhentiUtuhBukanGepeng() {
        // Ini regresi yang mudah lolos: menjepit per TEPI membuat kotak yang
        // didorong ke pinggir menyusut jadi gepeng alih-alih berhenti.
        val b = intArrayOf(900, 100, 1000, 260)
        val r = BoxEdit.geser(b, 500, 0, 1000, 1400)
        assertEquals("lebar harus tetap", 100, r[2] - r[0])
        assertEquals("tinggi harus tetap", 160, r[3] - r[1])
        assertEquals(1000, r[2])
    }

    // ---------- ubah ukuran ----------

    @Test
    fun tarikSudutMengubahTepiYangBenar() {
        val b = intArrayOf(100, 100, 300, 300)
        val r = BoxEdit.ubahUkuran(b, BoxEdit.Gagang.KIRI_ATAS, 150, 160, 1000, 1400)
        assertArrayEquals(intArrayOf(150, 160, 300, 300), r)
    }

    @Test
    fun tarikSudutMelewatiSisiSeberangTidakMembalikKotak() {
        val b = intArrayOf(100, 100, 300, 300)
        val r = BoxEdit.ubahUkuran(b, BoxEdit.Gagang.KIRI_ATAS, 400, 400, 1000, 1400)
        assertTrue("x1 < x2", r[0] < r[2])
        assertTrue("y1 < y2", r[1] < r[3])
    }

    // ---------- kotak baru ----------

    @Test
    fun seretanTerlaluKecilTidakMembuatKotak() {
        assertNull(BoxEdit.kotakBaru(100, 100, 104, 104, 1000, 1400))
    }

    @Test
    fun seretanTerbalikTetapMenghasilkanKotakSah() {
        val r = BoxEdit.kotakBaru(300, 400, 100, 200, 1000, 1400)
        assertNotNull(r)
        assertArrayEquals(intArrayOf(100, 200, 300, 400), r)
    }

    // ---------- sisip: urutan baca + penggeseran kunci ----------

    @Test
    fun sisipDiTengahMenggeserTerjemahanBerikutnya() {
        // Tiga balon bertumpuk vertikal (satu kolom), baca atas->bawah.
        val p = page(
            intArrayOf(100, 100, 300, 200),   // nomor 1
            intArrayOf(100, 500, 300, 600)    // nomor 2
        )
        p.translations["1"] = "atas"
        p.translations["2"] = "bawah"
        p.sourceText["1"] = "ASAL-ATAS"
        p.sourceText["2"] = "ASAL-BAWAH"

        // Kotak baru di antara keduanya harus jadi nomor 2.
        val pos = BoxEdit.sisipkan(p, intArrayOf(100, 300, 300, 400), kananKeKiri = true)
        assertEquals("disisipkan di tengah", 1, pos)
        assertEquals(3, p.boxes.size)

        // Yang lama harus ikut bergeser, bukan tertimpa.
        assertEquals("atas", p.translations["1"])
        assertEquals("bawah", p.translations["3"])
        assertNull("slot baru kosong", p.translations["2"])
        assertEquals("ASAL-BAWAH", p.sourceText["3"])
    }

    @Test
    fun sisipDiAkhirTidakMengubahNomorLama() {
        val p = page(intArrayOf(100, 100, 300, 200))
        p.translations["1"] = "satu"
        val pos = BoxEdit.sisipkan(p, intArrayOf(100, 900, 300, 1000), kananKeKiri = true)
        assertEquals(1, pos)
        assertEquals("satu", p.translations["1"])
    }

    // ---------- hapus ----------

    @Test
    fun hapusMenurunkanNomorSesudahnya() {
        val p = page(
            intArrayOf(100, 100, 300, 200),
            intArrayOf(100, 300, 300, 400),
            intArrayOf(100, 500, 300, 600)
        )
        p.translations["1"] = "a"
        p.translations["2"] = "b"
        p.translations["3"] = "c"
        p.colors[BoxEdit.kunciKotak(intArrayOf(100, 300, 300, 400))] =
            Palette.Colors(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), true)

        assertTrue(BoxEdit.hapus(p, 2))

        assertEquals(2, p.boxes.size)
        assertEquals("a", p.translations["1"])
        assertEquals("c", p.translations["2"])
        assertNull(p.translations["3"])
        assertTrue("warna yatim ikut dibuang", p.colors.isEmpty())
    }

    @Test
    fun hapusNomorTidakSahDitolak() {
        val p = page(intArrayOf(100, 100, 300, 200))
        assertFalse(BoxEdit.hapus(p, 0))
        assertFalse(BoxEdit.hapus(p, 5))
        assertEquals(1, p.boxes.size)
    }

    @Test
    fun hapusMenjagaWarnaKotakLainBerkoordinatSama() {
        // mergeOverlapping bisa meninggalkan dua kotak identik; membuang warna
        // saat salah satunya dihapus akan mengubah tampilan kotak yang tersisa.
        val sama = intArrayOf(100, 100, 300, 200)
        val p = page(sama.copyOf(), sama.copyOf())
        p.colors[BoxEdit.kunciKotak(sama)] =
            Palette.Colors(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), true)
        BoxEdit.hapus(p, 1)
        assertEquals("warna harus bertahan", 1, p.colors.size)
    }

    // ---------- perbarui ----------

    @Test
    fun perbaruiMemindahkanKunciWarnaDanTeksLepas() {
        val lama = intArrayOf(100, 100, 300, 200)
        val p = page(lama)
        val kLama = BoxEdit.kunciKotak(lama)
        p.colors[kLama] = Palette.Colors(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), true)
        p.freeText.add(kLama)

        val baru = intArrayOf(110, 120, 310, 220)
        BoxEdit.perbarui(p, 1, baru)
        val kBaru = BoxEdit.kunciKotak(baru)

        assertArrayEquals(baru, p.boxes[0])
        assertNull("kunci lama dibuang", p.colors[kLama])
        assertEquals(0xFF102030.toInt(), p.colors[kBaru]?.background)
        assertTrue("teks-lepas ikut pindah", p.freeText.contains(kBaru))
        assertFalse(p.freeText.contains(kLama))
    }

    @Test
    fun kunciKotakSamaPersisDenganFormatPipeline() {
        // Pipeline memakai "x1,y1,x2,y2"; beda format = warna tidak pernah ketemu.
        assertEquals("10,20,30,40", BoxEdit.kunciKotak(intArrayOf(10, 20, 30, 40)))
    }
}
