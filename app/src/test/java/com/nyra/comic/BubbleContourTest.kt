package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kontur balon (ronde 24).
 *
 * Fixture dibuat sintetis supaya bentuk sebenarnya diketahui persis, jadi
 * asersinya bisa membandingkan mask dengan kebenaran geometris — bukan sekadar
 * "tidak crash".
 */
class BubbleContourTest {

    /**
     * Membangun piksel ARGB tanpa android.graphics.Color: di unit test JVM
     * seluruh metode Color adalah stub yang mengembalikan 0, yang akan membuat
     * setiap fixture menjadi bidang hitam seragam dan asersinya tak bermakna.
     */
    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val PUTIH = rgb(250, 250, 250)
    private val HITAM = rgb(10, 10, 10)
    private val KELABU = rgb(128, 128, 128)

    /**
     * Balon oval putih bergaris tepi hitam di atas latar bercorak gelap.
     *
     * Latarnya sengaja TIDAK putih polos: kalau latarnya putih juga, isi banjir
     * yang bocor tidak akan ketahuan.
     */
    private fun ovalBalon(
        w: Int, h: Int,
        isi: Int = PUTIH,
        latar: Int = KELABU,
        tepi: Int = HITAM,
        tebalTepi: Float = 2.5f
    ): IntArray {
        val p = IntArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        val rx = w * 0.42f
        val ry = h * 0.42f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                val d = sqrt(nx * nx + ny * ny)
                // Lebar cincin tepi dinormalkan terhadap jari-jari.
                val lebarCincin = tebalTepi / minOf(rx, ry)
                p[y * w + x] = when {
                    d > 1f -> latar
                    d > 1f - lebarCincin -> tepi
                    else -> isi
                }
            }
        }
        return p
    }

    private fun hitungKena(hasil: BubbleContour.Hasil, ambang: Float = 0.5f): Int =
        hasil.alfa.count { it >= ambang }

    @Test
    fun `balon oval menghasilkan mask oval bukan persegi`() {
        val w = 120
        val h = 90
        val hasil = BubbleContour.hitung(ovalBalon(w, h), w, h)

        assertTrue("kontur harus sah: ${hasil.alasan}", hasil.sah)

        // Luas oval = pi*rx*ry; bandingkan dengan luas kotak.
        // Rasio teoretisnya pi*0.42*0.42 = 0.554.
        val fraksi = hasil.fraksi
        assertTrue("fraksi $fraksi harus mendekati luas oval 0.55", fraksi in 0.45f..0.62f)

        // Yang paling penting: keempat sudut kotak TIDAK boleh termask,
        // sebab di sanalah persegi membulat merusak artwork.
        val sudut = listOf(
            0, w - 1, (h - 1) * w, (h - 1) * w + w - 1
        )
        for (i in sudut) {
            assertEquals("sudut harus bebas", 0f, hasil.alfa[i], 0.01f)
        }

        // Tengah harus tertutup penuh.
        assertEquals(1f, hasil.alfa[(h / 2) * w + w / 2], 0.01f)
    }

    @Test
    fun `huruf di dalam balon ikut tertutup lewat penutupan lubang`() {
        val w = 120
        val h = 90
        val p = ovalBalon(w, h)
        // Gambar "huruf" hitam di tengah balon.
        for (y in 40 until 50) {
            for (x in 45 until 75) {
                p[y * w + x] = HITAM
            }
        }
        val hasil = BubbleContour.hitung(p, w, h)
        assertTrue(hasil.sah)

        // Kalau lubang tidak ditutup, teks asli menyembul lewat sini.
        for (y in 42 until 48) {
            for (x in 50 until 70) {
                assertEquals(
                    "piksel huruf ($x,$y) harus ikut tertimpa",
                    1f, hasil.alfa[y * w + x], 0.01f
                )
            }
        }
    }

    @Test
    fun `balon hitam berteks putih terdeteksi lewat polaritas terbalik`() {
        val w = 120
        val h = 90
        // Isi gelap, tepi terang, latar kelabu terang.
        val p = ovalBalon(
            w, h,
            isi = rgb(12, 12, 20),
            latar = rgb(200, 200, 200),
            tepi = rgb(245, 245, 245)
        )
        val hasil = BubbleContour.hitung(p, w, h)

        assertTrue("balon gelap harus tetap terdeteksi: ${hasil.alasan}", hasil.sah)
        assertEquals(1f, hasil.alfa[(h / 2) * w + w / 2], 0.01f)
        assertEquals("sudut harus bebas", 0f, hasil.alfa[0], 0.01f)
    }

    @Test
    fun `teks bebas tanpa garis tepi ditolak supaya jatuh ke persegi lama`() {
        val w = 100
        val h = 60
        // Latar rata terang dengan beberapa goresan huruf gelap, tanpa balon.
        val p = IntArray(w * h) { PUTIH }
        for (y in 20 until 40) {
            for (x in 10 until 90 step 7) {
                p[y * w + x] = HITAM
            }
        }
        val hasil = BubbleContour.hitung(p, w, h)

        // Tidak ada dinding, jadi isi banjir menyentuh seluruh tepi.
        assertFalse("teks bebas harus ditolak, bukan dipakai", hasil.sah)
    }

    @Test
    fun `kotak terlalu kecil ditolak`() {
        val w = 10
        val h = 40
        val hasil = BubbleContour.hitung(IntArray(w * h) { PUTIH }, w, h)
        assertFalse(hasil.sah)
        assertTrue(hasil.alasan.contains("kecil"))
    }

    @Test
    fun `otsu memisahkan dua puncak histogram`() {
        // 60% piksel gelap nilai 40, 40% terang nilai 200.
        val abu = IntArray(1000) { if (it < 600) 40 else 200 }
        val t = BubbleContour.otsu(abu)
        // Konvensi kelas ini: piksel <= ambang dianggap gelap. Untuk histogram
        // dua nilai, ambang yang benar justru jatuh TEPAT di puncak gelap (40),
        // bukan di tengah-tengah keduanya. Yang diuji adalah pemisahannya.
        assertTrue("gelap harus masuk sisi gelap", 40 <= t)
        assertTrue("terang harus masuk sisi terang", 200 > t)
    }

    @Test
    fun `kikis mengecilkan mask sebesar radius`() {
        val w = 20
        val h = 20
        val mask = BooleanArray(w * h)
        for (y in 5 until 15) for (x in 5 until 15) mask[y * w + x] = true

        val out = BubbleContour.kikis(mask, w, h, 2)
        // Persegi 10x10 dikikis radius 2 menjadi 6x6.
        assertEquals(36, out.count { it })
        assertTrue(out[7 * w + 7])
        assertFalse("tepi lama harus hilang", out[5 * w + 5])
    }

    @Test
    fun `bulu menghasilkan tepi bergradasi bukan tangga`() {
        val w = 20
        val h = 20
        val mask = BooleanArray(w * h)
        for (y in 5 until 15) for (x in 5 until 15) mask[y * w + x] = true

        val a = BubbleContour.bulu(mask, w, h, 2)
        // Pusat tetap padat.
        assertEquals(1f, a[10 * w + 10], 0.001f)
        // Jauh di luar tetap nol.
        assertEquals(0f, a[0], 0.001f)
        // Tepat di tepi harus berada di antaranya.
        val tepi = a[10 * w + 5]
        assertTrue("tepi $tepi harus bergradasi", tepi > 0.05f && tepi < 0.95f)
    }

    @Test
    fun `mask yang memenuhi seluruh kotak ditolak`() {
        val w = 60
        val h = 60
        // Warna rata sempurna: tidak ada balon, tidak ada tepi.
        val hasil = BubbleContour.hitung(IntArray(w * h) { PUTIH }, w, h)
        assertFalse("bidang rata harus ditolak", hasil.sah)
    }

    @Test
    fun `balon persegi membulat tetap menyisakan sudut bebas`() {
        val w = 100
        val h = 80
        val p = IntArray(w * h) { KELABU }
        val r = 18f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = x + 0.5f
                val fy = y + 0.5f
                val dx = maxOf(8f - fx, fx - (w - 8f), 0f)
                val dy = maxOf(8f - fy, fy - (h - 8f), 0f)
                val luar = fx < 8f || fx > w - 8f || fy < 8f || fy > h - 8f
                // Jarak ke persegi dalam, untuk membulatkan sudut.
                val d = sqrt(dx.pow(2) + dy.pow(2))
                if (!luar) {
                    p[y * w + x] = PUTIH
                } else if (d <= r) {
                    p[y * w + x] = if (d > r - 3f) HITAM else PUTIH
                }
            }
        }
        // Beri garis tepi tegas di sisi lurus.
        for (x in 8 until w - 8) {
            p[8 * w + x] = HITAM
            p[(h - 9) * w + x] = HITAM
        }
        for (y in 8 until h - 8) {
            p[y * w + 8] = HITAM
            p[y * w + w - 9] = HITAM
        }

        val hasil = BubbleContour.hitung(p, w, h)
        assertTrue("balon persegi harus terdeteksi: ${hasil.alasan}", hasil.sah)
        assertEquals(1f, hasil.alfa[(h / 2) * w + w / 2], 0.01f)
        assertEquals("sudut luar harus bebas", 0f, hasil.alfa[0], 0.01f)
    }
}
