package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sisa teks sumber yang masih terlihat di sekitar balon setelah diterjemahkan.
 * Semua angka diukur dari halaman manga sungguhan yang dikirim pengguna.
 */
class LeftoverTextTest {

    @Test
    fun maskMarginCoversGlyphOverhangMeasuredOnRealPages() {
        val base = 18; val ratio = 0.12f; val cap = 90
        val kecil = Mosaic.effectiveMaskMargin(335, 111, base, ratio, cap)
        assertTrue("balon 335x111 butuh >= 29 px, dapat $kecil", kecil >= 29)
        val besar = Mosaic.effectiveMaskMargin(986, 1053, base, ratio, cap)
        assertTrue("balon 986x1053 butuh >= 77 px, dapat $besar", besar >= 77)
    }

    /** Rumus lama memakai sisi terpendek: balon 335x111 hanya dapat 6 px. */
    @Test
    fun maskMarginScalesOffTheLongerSideNotTheShorterOne() {
        val lebar = Mosaic.effectiveMaskMargin(1000, 100, 0, 0.12f, 1000)
        assertEquals("harus 12% dari 1000, bukan dari 100", 120, lebar)
        val tinggi = Mosaic.effectiveMaskMargin(100, 1000, 0, 0.12f, 1000)
        assertEquals("orientasi tidak boleh mengubah margin", lebar, tinggi)
    }

    @Test
    fun maskMarginIsCapped() {
        assertEquals(90, Mosaic.effectiveMaskMargin(5000, 5000, 18, 0.12f, 90))
    }

    @Test
    fun tinyBubbleStillGetsTheBaseMargin() {
        assertEquals(18, Mosaic.effectiveMaskMargin(40, 30, 18, 0.12f, 90))
    }

    /**
     * Filter bentuk hanya boleh ada di SATU tempat: dropAbsurd saat deteksi.
     * Filter kembar yang dulu ada di tahap render memakai ukuran halaman
     * mentah, sehingga balon yang sudah diterjemahkan (dan dibayar) bisa
     * dibuang tanpa jejak log. Penyisiran ini membuktikan filter itu kode mati.
     */
    @Test
    fun noShapeSurvivesDetectionOnlyToBeRefusedAtRenderTime() {
        val halaman = listOf(
            1080 to 1500, 1080 to 2400, 900 to 1119, 800 to 1800,
            1080 to 5000, 1080 to 11700, 282 to 409, 1500 to 1000
        )
        var bentrok = 0
        for ((w, h) in halaman) {
            var bw = 4
            while (bw <= w) {
                var bh = 4
                while (bh <= h) {
                    val box = intArrayOf(0, 0, bw, bh)
                    val lolosDeteksi = BoxUtils.dropAbsurd(listOf(box), w, h).isNotEmpty()
                    val ratio = bw.toFloat() / bh.toFloat()
                    val areaRatio = (bw.toFloat() * bh.toFloat()) / (w.toFloat() * h.toFloat())
                    val ditolakRenderLama =
                        (ratio >= 3.2f && bw >= w * 0.35f) ||
                        (areaRatio >= 0.035f && ratio >= 2.8f)
                    if (lolosDeteksi && ditolakRenderLama) bentrok++
                    bh += maxOf(1, h / 60)
                }
                bw += maxOf(1, w / 60)
            }
        }
        assertEquals("tidak boleh ada kotak lolos deteksi tapi ditolak saat render", 0, bentrok)
    }

    /**
     * Balon gelap, diukur dari screenshot pengguna. Balon biru gelap:
     * whiteRatio 0.096, blackRatio 0.884, edgeRatio 0.048. Teks putih di atas
     * balon gelap adalah dialog biasa, bukan efek suara.
     */
    @Test
    fun darkBubblesAreNotMistakenForArtwork() {
        val blackThr = 0.16f; val edgeThr = 0.11f; val whiteSafe = 0.62f
        fun dibuang(white: Float, black: Float, edge: Float, areaRatio: Float, ratio: Float): Boolean {
            if (white >= whiteSafe) return false
            val sfxOrArt = areaRatio > 0.018f && black > blackThr && edge > edgeThr
            val flat = ratio > 2.2f && edge > maxOf(0.07f, edgeThr - 0.03f) && white < whiteSafe
            val bigArt = areaRatio > 0.045f && white < 0.55f && edge > 0.075f
            return sfxOrArt || flat || bigArt
        }
        assertTrue("balon biru gelap harus lolos filter SFX",
            !dibuang(0.096f, 0.884f, 0.048f, 0.0777f, 1.4f))
        assertTrue("balon hitam harus lolos filter SFX",
            !dibuang(0.780f, 0.097f, 0.061f, 0.0560f, 1.37f))
        assertTrue("SFX bertepi ramai harus tetap dibuang",
            dibuang(0.30f, 0.40f, 0.25f, 0.06f, 1.5f))
    }
}
