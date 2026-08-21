package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pengujian penambal lokal.
 *
 * Yang diuji adalah hasil piksel sungguhan, bukan sekadar "fungsi berjalan":
 * setiap tes membangun gambar sintetis yang jawabannya sudah diketahui,
 * merusaknya, lalu mengukur seberapa dekat hasil tambalan ke gambar asli.
 */
class TambalLokalTest {

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    /** Selisih warna rata-rata pada satu kotak, 0 berarti identik. */
    private fun selisih(a: IntArray, b: IntArray, w: Int, kotak: IntArray): Double {
        var total = 0.0
        var n = 0
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) {
                val i = y * w + x
                total += abs(r(a[i]) - r(b[i])) + abs(g(a[i]) - g(b[i])) + abs(b(a[i]) - b(b[i]))
                n += 3
            }
        }
        return if (n == 0) 0.0 else total / n
    }

    @Test
    fun latarPolosDipulihkanNyarisSempurna() {
        val w = 80
        val h = 80
        val asli = IntArray(w * h) { argb(230, 230, 230) }
        val kerja = asli.copyOf()

        // Coret hitam di tengah, seperti watermark di atas langit polos.
        val kotak = intArrayOf(30, 30, 50, 42)
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) kerja[y * w + x] = argb(0, 0, 0)
        }

        val diisi = TambalLokal.tambal(kerja, w, h, listOf(kotak))
        assertTrue("harus ada piksel yang diisi", diisi > 0)

        val beda = selisih(asli, kerja, w, kotak)
        assertTrue("latar polos harus pulih hampir sempurna, beda=$beda", beda < 3.0)
    }

    @Test
    fun gradasiDiteruskanBukanDiratakan() {
        // Gradasi mendatar: nilai hanya bergantung pada x.
        val w = 90
        val h = 60
        val asli = IntArray(w * h) { i ->
            val x = i % w
            val v = (40 + x * 2).coerceAtMost(255)
            argb(v, v, v)
        }
        val kerja = asli.copyOf()
        val kotak = intArrayOf(40, 20, 58, 40)
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) kerja[y * w + x] = argb(255, 0, 0)
        }

        TambalLokal.tambal(kerja, w, h, listOf(kotak))

        // Rata-rata datar akan meleset jauh di tepi kiri/kanan kotak; tambalan
        // yang benar mengikuti gradasi.
        val beda = selisih(asli, kerja, w, kotak)
        assertTrue("gradasi harus diteruskan, beda=$beda", beda < 14.0)

        // Sifat gradasi harus bertahan: sisi kanan lebih terang dari sisi kiri.
        val y = 30
        val kiri = r(kerja[y * w + 41])
        val kanan = r(kerja[y * w + 56])
        assertTrue("gradasi harus tetap menanjak: $kiri -> $kanan", kanan > kiri + 10)
    }

    @Test
    fun piksdiLuarKotakTidakBolehTersentuh() {
        val w = 64
        val h = 64
        val asli = IntArray(w * h) { i -> argb(i % 256, (i * 7) % 256, (i * 13) % 256) }
        val kerja = asli.copyOf()
        val kotak = intArrayOf(20, 20, 30, 30)

        TambalLokal.tambal(kerja, w, h, listOf(kotak))

        // Kotak dilebarkan oleh maskPad, jadi bandingkan di luar area itu.
        val pad = InpaintMath.maskPad(kotak)
        val d = InpaintMath.dilate(kotak, pad, w, h)
        var berubah = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val diDalam = x >= d[0] && x < d[2] && y >= d[1] && y < d[3]
                if (diDalam) continue
                if (asli[y * w + x] != kerja[y * w + x]) berubah++
            }
        }
        assertEquals("piksel di luar area hapus tidak boleh berubah", 0, berubah)
    }

    @Test
    fun tidakAdaSisaWarnaWatermark() {
        // Watermark merah menyala di atas latar abu: kalau ada satu piksel
        // merah tersisa, tambalannya gagal.
        val w = 70
        val h = 70
        val pix = IntArray(w * h) { argb(200, 200, 200) }
        val kotak = intArrayOf(25, 25, 45, 35)
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) pix[y * w + x] = argb(255, 0, 0)
        }

        TambalLokal.tambal(pix, w, h, listOf(kotak))

        var merah = 0
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) {
                val p = pix[y * w + x]
                if (r(p) > 230 && g(p) < 60 && b(p) < 60) merah++
            }
        }
        assertEquals("tidak boleh ada sisa piksel watermark", 0, merah)
    }

    @Test
    fun beberapaKotakSekaligus() {
        val w = 100
        val h = 100
        val asli = IntArray(w * h) { argb(180, 190, 200) }
        val kerja = asli.copyOf()
        val kotak = listOf(
            intArrayOf(10, 10, 25, 20),
            intArrayOf(60, 70, 80, 82),
        )
        for (k in kotak) {
            for (y in k[1] until k[3]) for (x in k[0] until k[2]) kerja[y * w + x] = argb(0, 0, 0)
        }

        TambalLokal.tambal(kerja, w, h, kotak)

        for (k in kotak) {
            val beda = selisih(asli, kerja, w, k)
            assertTrue("kotak ${k.toList()} harus pulih, beda=$beda", beda < 3.0)
        }
    }

    @Test
    fun masukanTakLazimTidakMembuatCrash() {
        val w = 20
        val h = 20
        val pix = IntArray(w * h) { argb(10, 10, 10) }

        assertEquals(0, TambalLokal.tambal(pix, w, h, emptyList()))
        assertEquals(0, TambalLokal.tambal(pix, 0, 0, listOf(intArrayOf(1, 1, 2, 2))))
        // Kotak menutupi seluruh gambar: tidak ada sumber, harus menyerah rapi.
        assertEquals(0, TambalLokal.tambal(pix, w, h, listOf(intArrayOf(0, 0, w, h))))
        // Kotak terbalik/kosong tidak boleh melempar.
        TambalLokal.tambal(pix, w, h, listOf(intArrayOf(5, 5, 5, 5)))
        // Kotak di luar batas dijepit oleh dilate.
        TambalLokal.tambal(pix, w, h, listOf(intArrayOf(-10, -10, 5, 5)))
    }
}
