package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

/**
 * Uji kontur balon memakai potongan halaman komik sungguhan, bukan bentuk sintetis.
 *
 * Fixture sintetis membuktikan aljabar isi banjir jalan; halaman nyata membuktikan
 * hal yang berbeda dan lebih penting: balon punya garis tepi tak rata hasil kompresi
 * JPEG, ekor yang menjulur ke luar, latar hitam pekat di sekitarnya, dan bintik
 * derau. Di sinilah kontur betulan bisa bocor ke seluruh panel.
 *
 * Berkas .rgb: dua bilangan 32-bit big-endian (lebar, tinggi), lalu w*h piksel ARGB
 * 32-bit big-endian.
 */
class BubbleContourRealPageTest {

    private class Potongan(val nama: String, val lebar: Int, val tinggi: Int, val piksel: IntArray)

    private fun muat(nama: String): Potongan {
        val aliran = javaClass.classLoader!!.getResourceAsStream("page/$nama.rgb")
            ?: throw IllegalStateException("fixture halaman hilang: $nama")
        DataInputStream(aliran.buffered()).use { d ->
            val w = d.readInt()
            val h = d.readInt()
            val piksel = IntArray(w * h)
            for (i in piksel.indices) piksel[i] = d.readInt()
            return Potongan(nama, w, h, piksel)
        }
    }

    private val semua = listOf(
        "oval_besar", "tinggi_kiri", "atas", "kanan_atas", "kanan_tengah", "kiri_bawah"
    )

    /** Piksel dianggap tertutup bila alfanya melebihi setengah. */
    private fun tertutup(h: BubbleContour.Hasil, i: Int): Boolean = h.alfa[i] > 0.5f

    private fun fraksi(h: BubbleContour.Hasil): Float {
        var n = 0
        for (v in h.alfa) if (v > 0.5f) n++
        return n.toFloat() / h.alfa.size
    }

    @Test
    fun `setiap balon nyata menghasilkan kontur yang sah`() {
        for (nama in semua) {
            val p = muat(nama)
            val hasil = BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
            assertTrue("$nama: kontur ditolak, seharusnya sah", hasil.sah)
        }
    }

    @Test
    fun `kontur menutupi sebagian besar balon tanpa memenuhi seluruh kotak`() {
        for (nama in semua) {
            val p = muat(nama)
            val hasil = BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
            val f = fraksi(hasil)
            // Balon lonjong di dalam kotak pembatasnya menempati kira-kira pi/4 = 0.785
            // dari luas kotak. Nilai di bawah 0.3 berarti konturnya rontok; nilai di
            // atas 0.97 berarti ia bocor keluar dan memakan seluruh kotak.
            assertTrue("$nama: kontur terlalu kecil, fraksi $f", f > 0.30f)
            assertTrue("$nama: kontur hampir penuh, fraksi $f", f < 0.97f)
        }
    }

    @Test
    fun `piksel tengah balon selalu termasuk dalam kontur`() {
        for (nama in semua) {
            val p = muat(nama)
            val hasil = BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
            val tengah = (p.tinggi / 2) * p.lebar + (p.lebar / 2)
            assertTrue("$nama: titik pusat balon justru di luar kontur", tertutup(hasil, tengah))
        }
    }

    @Test
    fun `sudut kotak berada di luar kontur karena balon lonjong tidak menyentuhnya`() {
        // Latar gelap di luar balon adalah lawan sesungguhnya: jika isi banjir bocor
        // lewat celah garis tepi, sudut-sudut inilah yang pertama ikut tertelan.
        for (nama in listOf("oval_besar", "kanan_tengah", "kiri_bawah", "atas")) {
            val p = muat(nama)
            val hasil = BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
            val sudut = intArrayOf(
                0,
                p.lebar - 1,
                (p.tinggi - 1) * p.lebar,
                (p.tinggi - 1) * p.lebar + (p.lebar - 1)
            )
            var kena = 0
            for (i in sudut) if (tertutup(hasil, i)) kena++
            assertTrue("$nama: $kena dari 4 sudut ikut terkena, kontur bocor", kena <= 1)
        }
    }

    @Test
    fun `hasil bersifat deterministik pada masukan yang sama`() {
        val p = muat("oval_besar")
        val a = BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
        val b = BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
        assertEquals(a.sah, b.sah)
        assertEquals(a.alfa.size, b.alfa.size)
        for (i in a.alfa.indices) {
            assertEquals("beda di piksel $i", a.alfa[i], b.alfa[i], 0f)
        }
    }

    @Test
    fun `masukan tidak diubah oleh perhitungan`() {
        val p = muat("kanan_atas")
        val salinan = p.piksel.copyOf()
        BubbleContour.hitung(p.piksel, p.lebar, p.tinggi)
        for (i in salinan.indices) {
            assertEquals("piksel masukan $i ikut berubah", salinan[i], p.piksel[i])
        }
    }
}
