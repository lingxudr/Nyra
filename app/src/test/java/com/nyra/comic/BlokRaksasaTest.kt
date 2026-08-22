package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresi untuk cacat lapangan ronde 38: serpihan OCR dari panel dan balon
 * yang berbeda runtuh menjadi satu blok seukuran halaman. Akibatnya seluruh
 * dialog halaman dikirim sebagai satu permintaan (semua kalimat menumpuk di
 * satu balon), dan masker inpaint seukuran blok itu meninggalkan bidang
 * zaitun gelap di atas artwork.
 *
 * Angka pada kasus uji di bawah diambil dari halaman nyata 1127x1600
 * (uploads/001.jpg dan 005.jpg) yang memicu cacat itu.
 */
class BlokRaksasaTest {

    private val W = 1127
    private val H = 1600

    /** Kolom teks Jepang tegak: serpihan sempit yang bertumpuk vertikal. */
    private fun kolom(x: Int, y: Int, lebar: Int, tinggi: Int, n: Int): List<IntArray> =
        (0 until n).map { i ->
            val t = tinggi / n
            intArrayOf(x, y + i * t, x + lebar, y + i * t + t - 4)
        }

    @Test
    fun toleransiTidakIkutTumbuhSaatBlokMembesar() {
        // Rantai serpihan yang masing-masing berdekatan dengan tetangganya,
        // lalu satu serpihan jauh di seberang halaman. Versi lama menyerap
        // yang jauh karena toleransinya sudah membengkak mengikuti akumulator.
        val rantai = kolom(x = 120, y = 100, lebar = 40, tinggi = 600, n = 12)
        val jauh = intArrayOf(1000, 1400, 1060, 1520)

        val hasil = TextRegionMath.groupNearby(rantai + jauh, imgW = W, imgH = H)

        assertEquals("serpihan jauh tidak boleh ikut tersedot", 2, hasil.size)
        val terbesar = hasil.maxByOrNull { (it[2] - it[0]).toLong() * (it[3] - it[1]) }!!
        assertTrue(
            "blok terbesar tidak boleh menutupi separuh halaman",
            (terbesar[2] - terbesar[0]).toLong() * (terbesar[3] - terbesar[1]) <
                W.toLong() * H * 0.25
        )
    }

    @Test
    fun balonBerjauhanYangSebarisTetapTerpisah() {
        // Rumus lama memakai tumpang tindih sumbu, bukan celah sungguhan:
        // dua balon sebaris di tepi kiri dan kanan halaman selalu digabung
        // berapa pun jaraknya.
        val kiri = intArrayOf(80, 700, 300, 780)
        val kanan = intArrayOf(850, 700, 1070, 780)

        val hasil = TextRegionMath.groupNearby(listOf(kiri, kanan), imgW = W, imgH = H)

        assertEquals("dua balon sebaris di sisi berlawanan tetap terpisah", 2, hasil.size)
    }

    @Test
    fun satuKolomTegakTetapMenyatu() {
        // Penjagaan jangan sampai kebablasan: satu kolom teks tegak harus
        // tetap menjadi satu blok, bukan pecah per aksara.
        val satuKolom = kolom(x = 500, y = 300, lebar = 44, tinggi = 400, n = 8)

        val hasil = TextRegionMath.groupNearby(satuKolom, imgW = W, imgH = H)

        assertEquals("satu kolom tegak harus tetap satu blok", 1, hasil.size)
        assertEquals(500, hasil[0][0])
        assertEquals(300, hasil[0][1])
    }

    @Test
    fun blokTidakBolehMelampauiPagarUkuran() {
        // Deretan rapat yang membentang melewati batas 0.55 sisi halaman.
        val lebar = 60
        val deret = (0 until 20).map { i ->
            val x = 40 + i * (lebar + 10)
            intArrayOf(x, 800, x + lebar, 900)
        }

        val hasil = TextRegionMath.groupNearby(deret, imgW = W, imgH = H)

        val batas = (W * TextRegionMath.MAX_BLOCK_SIDE_RATIO).toInt()
        for (b in hasil) {
            assertTrue(
                "lebar blok ${b[2] - b[0]} melewati pagar $batas",
                b[2] - b[0] <= batas
            )
        }
        assertTrue("deret panjang harus pecah jadi beberapa blok", hasil.size > 1)
    }

    @Test
    fun dropNoiseMembuangBlokSeluasHalaman() {
        // Blok 945x1400: lebarnya 0.84 halaman sehingga lolos penjaga lebar
        // 0.90, padahal luasnya 73 % halaman. Inilah kotak yang menghasilkan
        // tambalan zaitun di lapangan.
        val raksasa = intArrayOf(90, 120, 1035, 1520)
        val wajar = intArrayOf(500, 300, 700, 480)

        val hasil = TextRegionMath.dropNoise(listOf(raksasa, wajar), W, H)

        assertEquals("hanya kotak wajar yang bertahan", 1, hasil.size)
        assertEquals(500, hasil[0][0])
    }

    @Test
    fun urutanMasukanTidakMengubahJumlahBlok() {
        val campur = kolom(120, 100, 40, 400, 8) +
            kolom(900, 1000, 40, 400, 8) +
            listOf(intArrayOf(520, 640, 600, 700))

        val maju = TextRegionMath.groupNearby(campur, imgW = W, imgH = H).size
        val mundur = TextRegionMath.groupNearby(campur.reversed(), imgW = W, imgH = H).size

        assertEquals(maju, mundur)
    }
}
