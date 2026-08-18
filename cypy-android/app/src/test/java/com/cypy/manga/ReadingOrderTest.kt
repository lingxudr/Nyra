package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Urutan balon menentukan penomoran ID merah pada mosaik, dan ID itulah yang
 * dibaca model sebagai urutan dialog. Kalau urutannya salah, tiap kalimat
 * memang diterjemahkan, tetapi percakapannya jadi kacau - dan konteks halaman
 * yang kita simpan ikut tercatat dalam urutan yang salah.
 *
 * Manga Jepang dibaca KANAN ke KIRI. Sebelum ini pipeline mengurutkan
 * `compareBy(y, x)`, yaitu kiri ke kanan, sekaligus tanpa pengelompokan baris:
 * dua balon bersebelahan yang selisih y-nya beberapa piksel bisa terbalik.
 */
class ReadingOrderTest {

    private fun kotak(x1: Int, y1: Int, x2: Int, y2: Int) = intArrayOf(x1, y1, x2, y2)
    private fun pojok(l: List<IntArray>) = l.map { it[0] to it[1] }

    @Test
    fun duaBalonSebarisDibacaKananDuluUntukManga() {
        val kiri = kotak(50, 100, 250, 300)
        val kanan = kotak(400, 100, 600, 300)
        val hasil = BoxUtils.urutBaca(listOf(kiri, kanan), kananKeKiri = true)
        assertEquals(listOf(400 to 100, 50 to 100), pojok(hasil))
    }

    @Test
    fun modeKiriKeKananTetapTersedia() {
        val kiri = kotak(50, 100, 250, 300)
        val kanan = kotak(400, 100, 600, 300)
        val hasil = BoxUtils.urutBaca(listOf(kanan, kiri), kananKeKiri = false)
        assertEquals(listOf(50 to 100, 400 to 100), pojok(hasil))
    }

    @Test
    fun barisAtasSelaluMendahuluiBarisBawah() {
        val atasKiri = kotak(50, 50, 250, 200)
        val atasKanan = kotak(400, 60, 600, 210)
        val bawahKanan = kotak(400, 500, 600, 650)
        val bawahKiri = kotak(50, 510, 250, 660)
        val hasil = BoxUtils.urutBaca(
            listOf(bawahKiri, atasKiri, bawahKanan, atasKanan), kananKeKiri = true
        )
        assertEquals(
            listOf(400 to 60, 50 to 50, 400 to 500, 50 to 510),
            pojok(hasil)
        )
    }

    @Test
    fun selisihYKecilTidakMembalikUrutanSatuBaris() {
        // Inti cacat lama: `compareBy(y, x)` menganggap 100 dan 103 dua baris
        // berbeda, jadi balon kiri menang hanya karena y-nya lebih kecil.
        val kanan = kotak(400, 103, 600, 300)
        val kiri = kotak(50, 100, 250, 297)
        val hasil = BoxUtils.urutBaca(listOf(kiri, kanan), kananKeKiri = true)
        assertEquals("keduanya sebaris, kanan harus lebih dulu",
            listOf(400 to 103, 50 to 100), pojok(hasil))
    }

    @Test
    fun balonYangBenarBenarDiBawahTidakDianggapSebaris() {
        val atas = kotak(400, 100, 600, 200)
        val bawah = kotak(50, 400, 250, 500)
        val hasil = BoxUtils.urutBaca(listOf(bawah, atas), kananKeKiri = true)
        assertEquals(listOf(400 to 100, 50 to 400), pojok(hasil))
    }

    @Test
    fun tigaBalonSatuBarisUrutKananKeKiri() {
        val a = kotak(50, 100, 200, 300)
        val b = kotak(300, 105, 450, 305)
        val c = kotak(550, 95, 700, 295)
        val hasil = BoxUtils.urutBaca(listOf(a, b, c), kananKeKiri = true)
        assertEquals(listOf(550 to 95, 300 to 105, 50 to 100), pojok(hasil))
    }

    @Test
    fun balonTinggiYangMenaungiDuaBalonPendekTidakMenelanSemuanya() {
        // Balon tinggi di kanan sejajar dengan dua balon pendek di kiri.
        // Pengelompokan tidak boleh rakus sampai menggabung seluruh halaman.
        val tinggi = kotak(500, 100, 650, 600)
        val pendekAtas = kotak(50, 100, 250, 250)
        val pendekBawah = kotak(50, 450, 250, 600)
        val hasil = BoxUtils.urutBaca(
            listOf(pendekBawah, pendekAtas, tinggi), kananKeKiri = true
        )
        assertEquals("balon tinggi dibaca lebih dulu", 500 to 100, pojok(hasil).first())
        assertEquals("yang atas mendahului yang bawah",
            listOf(50 to 100, 50 to 450), pojok(hasil).drop(1))
    }

    @Test
    fun daftarKosongDanTunggalAman() {
        assertEquals(0, BoxUtils.urutBaca(emptyList(), true).size)
        val satu = kotak(10, 20, 30, 40)
        assertEquals(listOf(10 to 20), pojok(BoxUtils.urutBaca(listOf(satu), true)))
    }

    @Test
    fun tidakAdaKotakYangHilangAtauGanda() {
        val acak = (0 until 40).map { i ->
            val x = (i * 137) % 800
            val y = (i * 241) % 1200
            kotak(x, y, x + 120, y + 90)
        }
        val hasil = BoxUtils.urutBaca(acak, kananKeKiri = true)
        assertEquals(acak.size, hasil.size)
        assertEquals(
            acak.map { it.toList() }.toSet(),
            hasil.map { it.toList() }.toSet()
        )
    }

    @Test
    fun urutanBersifatStabilDanIdempoten() {
        val acak = (0 until 25).map { i ->
            val x = (i * 311) % 700
            val y = (i * 97) % 1000
            kotak(x, y, x + 150, y + 120)
        }
        val sekali = BoxUtils.urutBaca(acak, kananKeKiri = true)
        val duaKali = BoxUtils.urutBaca(sekali, kananKeKiri = true)
        assertEquals(pojok(sekali), pojok(duaKali))
    }
}
