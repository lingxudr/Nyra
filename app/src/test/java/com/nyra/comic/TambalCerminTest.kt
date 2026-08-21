package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Pengujian penambal salin-cermin, termasuk adu langsung melawan difusi.
 *
 * Tes terpenting di berkas ini adalah [cerminMengalahkanDifusiPadaGarisTinta]:
 * seluruh alasan keberadaan [TambalCermin] adalah klaim bahwa ia lebih baik
 * daripada Telea untuk gambar bergaris. Klaim itu diukur di sini, jadi kalau
 * suatu saat tidak lagi benar, pengujian yang memberi tahu - bukan pengguna.
 */
class TambalCerminTest {

    private fun argb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun r(p: Int) = (p shr 16) and 0xFF

    /** Persen piksel yang pulih dalam toleransi kanal <= 32. */
    private fun persenMirip(asli: IntArray, hasil: IntArray, w: Int, kotak: IntArray): Double {
        var cocok = 0
        var n = 0
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) {
                val i = y * w + x
                if (abs(r(asli[i]) - r(hasil[i])) <= 32) cocok++
                n++
            }
        }
        return if (n == 0) 0.0 else 100.0 * cocok / n
    }

    /**
     * Halaman tiruan bergaya manga: garis tinta tegak rapat berselang
     * screentone, persis jenis tekstur yang membuat difusi gagal.
     */
    private fun halamanGaris(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val garis = (x % 7) < 2
        val tone = ((x + y) % 3) == 0
        argb(if (garis) 15 else if (tone) 120 else 240)
    }

    @Test
    fun cerminMengalahkanDifusiPadaGarisTinta() {
        val w = 200
        val h = 200
        val asli = halamanGaris(w, h)
        // Pita watermark mendatar, bentuk paling umum.
        val kotak = intArrayOf(30, 90, 170, 112)

        val aCermin = asli.copyOf()
        val aDifusi = asli.copyOf()
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) {
                aCermin[y * w + x] = argb(0)
                aDifusi[y * w + x] = argb(0)
            }
        }

        TambalLokal.tambal(aCermin, w, h, listOf(kotak), TambalLokal.RADIUS, TambalLokal.Mode.CERMIN)
        TambalLokal.tambal(aDifusi, w, h, listOf(kotak), TambalLokal.RADIUS, TambalLokal.Mode.DIFUSI)

        val sCermin = persenMirip(asli, aCermin, w, kotak)
        val sDifusi = persenMirip(asli, aDifusi, w, kotak)

        assertTrue(
            "cermin ($sCermin%) harus mengungguli difusi ($sDifusi%) pada garis tinta",
            sCermin > sDifusi,
        )
        assertTrue("cermin harus memulihkan mayoritas piksel, dapat $sCermin%", sCermin > 60.0)
    }

    @Test
    fun modeOtomatisMemilihCermin() {
        val w = 200
        val h = 200
        val asli = halamanGaris(w, h)
        val kotak = intArrayOf(30, 90, 170, 112)
        val kerja = asli.copyOf()
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) kerja[y * w + x] = argb(0)
        }

        TambalLokal.tambal(kerja, w, h, listOf(kotak))
        val s = persenMirip(asli, kerja, w, kotak)
        assertTrue("mode otomatis harus sebaik cermin, dapat $s%", s > 60.0)
    }

    @Test
    fun sumbuDipilihSesuaiArahTekstur() {
        // Garis MENDATAR: memantulkan secara tegak akan merusak polanya,
        // memantulkan secara datar mempertahankannya. Uji cincin harus
        // menemukan itu sendiri.
        val w = 160
        val h = 160
        val asli = IntArray(w * h) { i -> argb(if ((i / w) % 6 < 2) 20 else 235) }
        val kotak = intArrayOf(60, 40, 100, 120)
        val kerja = asli.copyOf()
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) kerja[y * w + x] = argb(255)
        }

        TambalLokal.tambal(kerja, w, h, listOf(kotak))
        val s = persenMirip(asli, kerja, w, kotak)
        assertTrue("garis mendatar harus pulih dengan sumbu yang tepat, dapat $s%", s > 85.0)
    }

    @Test
    fun latarPolosTetapPulihSempurna() {
        val w = 100
        val h = 100
        val asli = IntArray(w * h) { argb(222) }
        val kerja = asli.copyOf()
        val kotak = intArrayOf(30, 40, 70, 55)
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) kerja[y * w + x] = argb(0)
        }

        TambalLokal.tambal(kerja, w, h, listOf(kotak))
        assertEquals("latar polos harus identik", 100.0, persenMirip(asli, kerja, w, kotak), 0.01)
    }

    @Test
    fun tidakMenyisakanWarnaWatermark() {
        val w = 120
        val h = 120
        val asli = halamanGaris(w, h)
        val kerja = asli.copyOf()
        val kotak = intArrayOf(20, 50, 100, 68)
        // Nilai 77 tidak pernah muncul di halamanGaris (15/120/240).
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) kerja[y * w + x] = argb(77)
        }

        TambalLokal.tambal(kerja, w, h, listOf(kotak))

        var sisa = 0
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) if (r(kerja[y * w + x]) == 77) sisa++
        }
        assertEquals("tidak boleh ada sisa piksel watermark", 0, sisa)
    }

    @Test
    fun sumberDiambilDariGambarAsliBukanHasilTambalan() {
        // Dua kotak bersebelahan: kalau kotak kedua menyalin dari hasil kotak
        // pertama, tekstur akan menggandakan dirinya dan meleset.
        val w = 150
        val h = 150
        val asli = halamanGaris(w, h)
        val kerja = asli.copyOf()
        val kotak = listOf(intArrayOf(30, 40, 120, 55), intArrayOf(30, 60, 120, 75))
        for (k in kotak) {
            for (y in k[1] until k[3]) for (x in k[0] until k[2]) kerja[y * w + x] = argb(0)
        }

        TambalLokal.tambal(kerja, w, h, kotak)
        for (k in kotak) {
            val s = persenMirip(asli, kerja, w, k)
            assertTrue("kotak ${k.toList()} harus pulih baik, dapat $s%", s > 55.0)
        }
    }

    @Test
    fun lubangMepetTepiTetapDitangani() {
        // Cermin butuh ruang; di pojok ia harus menyerah dan difusi yang
        // mengambil alih. Yang penting: watermark tetap hilang.
        val w = 80
        val h = 80
        val pix = IntArray(w * h) { argb(200) }
        val kotak = intArrayOf(0, 0, 30, 12)
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) pix[y * w + x] = argb(0)
        }

        val n = TambalLokal.tambal(pix, w, h, listOf(kotak))
        assertTrue("harus ada piksel terisi walau mepet tepi", n > 0)

        var hitam = 0
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) if (r(pix[y * w + x]) < 40) hitam++
        }
        assertEquals("watermark di pojok harus tetap hilang", 0, hitam)
    }

    @Test
    fun masukanTakLazimAman() {
        val w = 40
        val h = 40
        val pix = IntArray(w * h) { argb(128) }
        assertEquals(0, TambalCermin.tambal(pix, w, h, emptyList()))
        assertEquals(0, TambalCermin.tambal(pix, 0, 0, listOf(intArrayOf(1, 1, 3, 3))))
        // Kotak menutupi seluruh gambar: tidak ada sumber sama sekali.
        assertEquals(0, TambalLokal.tambal(pix, w, h, listOf(intArrayOf(0, 0, w, h))))
        // Kotak cacat tidak boleh melempar.
        TambalCermin.tambal(pix, w, h, listOf(intArrayOf(9, 9, 9, 9)))
        TambalCermin.tambal(pix, w, h, listOf(intArrayOf(-5, -5, 8, 8)))
    }

    @Test
    fun derauAcakTetapDitutupTanpaMeninggalkanLubang() {
        // Tekstur acak adalah kasus terburuk: tidak ada yang bisa menebaknya
        // dengan benar. Syaratnya cuma satu - jangan ada piksel yang lolos
        // tidak terisi.
        val w = 120
        val h = 120
        val rnd = Random(7)
        val pix = IntArray(w * h) { argb(rnd.nextInt(60, 200)) }
        val kotak = intArrayOf(30, 30, 90, 50)
        val tanda = argb(1)
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) pix[y * w + x] = tanda
        }

        TambalLokal.tambal(pix, w, h, listOf(kotak))

        var sisa = 0
        for (y in kotak[1] until kotak[3]) {
            for (x in kotak[0] until kotak[2]) if (pix[y * w + x] == tanda) sisa++
        }
        assertEquals("seluruh lubang harus terisi", 0, sisa)
    }
}
