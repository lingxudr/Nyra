package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tes tipografi adaptif.
 *
 * Semuanya berjalan di JVM biasa: Typography sengaja tidak menyentuh
 * android.graphics, jadi tidak perlu Robolectric.
 *
 * Halaman uji dibuat sebagai IntArray ARGB. PUTIH untuk latar, HITAM untuk
 * tinta, supaya kepadatan yang diharapkan bisa dihitung dengan tangan dan
 * dibandingkan dengan hasil pengukuran.
 */
class TypographyTest {

    private val PUTIH = 0xFFFFFFFF.toInt()
    private val HITAM = 0xFF000000.toInt()

    /** Halaman putih polos. */
    private fun halaman(w: Int, h: Int): IntArray = IntArray(w * h) { PUTIH }

    /** Menggambar garis tinta mendatar setebal [tebal] px pada baris [y]. */
    private fun garis(p: IntArray, w: Int, y: Int, x1: Int, x2: Int, tebal: Int) {
        for (dy in 0 until tebal) {
            val baris = (y + dy) * w
            for (x in x1 until x2) p[baris + x] = HITAM
        }
    }

    // ------------------------------------------------------------------
    // ukur() - hal-hal yang menjaga paritas
    // ------------------------------------------------------------------

    @Test
    fun `tanpa blok teks asli hasilnya bawaan dan tidak terukur`() {
        val g = Typography.ukur(halaman(100, 100), 100, 100, intArrayOf(0, 0, 100, 100), null)
        assertFalse(g.terukur)
        assertEquals(Typography.ISI_BAWAAN, g.rasioIsi, 1e-6f)
        assertEquals(Typography.Berat.NORMAL, g.berat)
    }

    @Test
    fun `blok lebih kecil dari batas ukur ditolak`() {
        val kecil = Typography.MIN_SISI_UKUR - 1
        val g = Typography.ukur(
            halaman(100, 100), 100, 100,
            intArrayOf(0, 0, 100, 100),
            intArrayOf(0, 0, kecil, kecil)
        )
        assertFalse("blok $kecil px harus ditolak", g.terukur)
    }

    @Test
    fun `blok tepat di batas ukur diterima`() {
        val p = halaman(100, 100)
        garis(p, 100, 2, 0, Typography.MIN_SISI_UKUR, 1)
        val g = Typography.ukur(
            p, 100, 100,
            intArrayOf(0, 0, 100, 100),
            intArrayOf(0, 0, Typography.MIN_SISI_UKUR, Typography.MIN_SISI_UKUR)
        )
        assertTrue(g.terukur)
    }

    @Test
    fun `piksel kurang dari ukuran halaman ditolak alih-alih membaca di luar batas`() {
        val g = Typography.ukur(IntArray(10), 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(0, 0, 50, 50))
        assertFalse(g.terukur)
    }

    // ------------------------------------------------------------------
    // ukur() - orientasi
    // ------------------------------------------------------------------

    @Test
    fun `blok jauh lebih tinggi daripada lebar terbaca tegak`() {
        val p = halaman(200, 200)
        garis(p, 200, 20, 20, 30, 2)
        val g = Typography.ukur(p, 200, 200, intArrayOf(0, 0, 200, 200), intArrayOf(20, 10, 60, 160))
        assertEquals(Typography.Arah.TEGAK, g.arah)
    }

    @Test
    fun `blok jauh lebih lebar daripada tinggi terbaca datar`() {
        val p = halaman(200, 200)
        garis(p, 200, 20, 20, 30, 2)
        val g = Typography.ukur(p, 200, 200, intArrayOf(0, 0, 200, 200), intArrayOf(10, 20, 160, 60))
        assertEquals(Typography.Arah.DATAR, g.arah)
    }

    @Test
    fun `blok hampir bujur sangkar tidak dipaksa punya arah`() {
        val p = halaman(200, 200)
        garis(p, 200, 30, 30, 40, 2)
        // 100x100: tepat 1.0, di bawah kedua ambang 1.30.
        val g = Typography.ukur(p, 200, 200, intArrayOf(0, 0, 200, 200), intArrayOf(20, 20, 120, 120))
        assertEquals(Typography.Arah.TIDAK_JELAS, g.arah)
    }

    // ------------------------------------------------------------------
    // ukur() - rasio isi
    // ------------------------------------------------------------------

    @Test
    fun `rasio isi memakai akar perbandingan luas bukan luas mentah`() {
        val p = halaman(200, 200)
        garis(p, 200, 20, 20, 30, 2)
        // Blok 100x100 di dalam balon 200x200: luas = 0.25, akarnya = 0.5,
        // lalu dijepit ke ISI_MIN. Kalau perbandingan luas dipakai mentah
        // hasilnya juga terjepit, jadi diuji dengan blok yang tidak terjepit
        // di tes berikutnya. Di sini cukup pastikan tidak melampaui batas.
        val g = Typography.ukur(p, 200, 200, intArrayOf(0, 0, 200, 200), intArrayOf(50, 50, 150, 150))
        assertEquals(Typography.ISI_MIN, g.rasioIsi, 1e-6f)
    }

    @Test
    fun `rasio isi di tengah rentang sama dengan akar perbandingan luas`() {
        val p = halaman(200, 200)
        garis(p, 200, 20, 20, 30, 2)
        // Blok 160x160 dalam balon 200x200 -> luas 0.64 -> akar 0.8.
        val g = Typography.ukur(p, 200, 200, intArrayOf(0, 0, 200, 200), intArrayOf(20, 20, 180, 180))
        assertEquals(0.8f, g.rasioIsi, 1e-4f)
        // Bukan 0.64: itu yang akan terjadi kalau akarnya lupa diambil.
        assertTrue(abs(g.rasioIsi - 0.64f) > 0.1f)
    }

    @Test
    fun `rasio isi dijepit di batas atas`() {
        val p = halaman(200, 200)
        garis(p, 200, 20, 20, 30, 2)
        val g = Typography.ukur(p, 200, 200, intArrayOf(0, 0, 200, 200), intArrayOf(0, 0, 200, 200))
        assertEquals(Typography.ISI_MAKS, g.rasioIsi, 1e-6f)
    }

    // ------------------------------------------------------------------
    // ukur() - kepadatan dan berat
    // ------------------------------------------------------------------

    @Test
    fun `kepadatan tinta dihitung dari piksel di dalam blok saja`() {
        val p = halaman(100, 100)
        // Blok 50x50 = 2500 piksel; 10 baris penuh selebar 50 = 500 tinta.
        for (y in 10 until 20) garis(p, 100, y, 10, 60, 1)
        val g = Typography.ukur(p, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(500f / 2500f, g.kepadatan, 1e-4f)
    }

    @Test
    fun `tinta di luar blok tidak ikut terhitung`() {
        val bersih = halaman(100, 100)
        for (y in 10 until 20) garis(bersih, 100, y, 10, 60, 1)
        val kotor = bersih.copyOf()
        // Coretan tebal jauh di luar blok yang diukur.
        for (y in 70 until 90) garis(kotor, 100, y, 70, 95, 1)

        val a = Typography.ukur(bersih, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        val b = Typography.ukur(kotor, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(a.kepadatan, b.kepadatan, 1e-6f)
    }

    @Test
    fun `blok padat dinilai tebal dan blok tipis dinilai tipis`() {
        // Padat: 20 dari 50 baris terisi penuh -> 0.40 >= PADAT_TEBAL.
        val padat = halaman(100, 100)
        for (y in 10 until 30) garis(padat, 100, y, 10, 60, 1)
        val gPadat = Typography.ukur(padat, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(Typography.Berat.TEBAL, gPadat.berat)

        // Tipis: 1 dari 50 baris -> 0.02 <= PADAT_TIPIS.
        val tipis = halaman(100, 100)
        garis(tipis, 100, 10, 10, 60, 1)
        val gTipis = Typography.ukur(tipis, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(Typography.Berat.TIPIS, gTipis.berat)
    }

    @Test
    fun `kepadatan di antara kedua ambang dinilai normal`() {
        val p = halaman(100, 100)
        // 4 dari 50 baris -> 0.08, di antara 0.045 dan 0.115.
        for (y in 10 until 14) garis(p, 100, y, 10, 60, 1)
        val g = Typography.ukur(p, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(0.08f, g.kepadatan, 1e-4f)
        assertEquals(Typography.Berat.NORMAL, g.berat)
    }

    @Test
    fun `berat dinilai dari kepadatan bukan dari tebal goresan mentah`() {
        // Goresan sangat tebal (20 px) tetapi jarang: kepadatan tetap rendah.
        // Kalau berat dinilai dari goresan mentah, ini akan salah jadi TEBAL -
        // persis kesalahan yang terjadi pada halaman pindaian resolusi tinggi.
        val p = halaman(400, 400)
        garis(p, 400, 20, 20, 40, 20) // 20x20 = 400 tinta dari 200x200 = 40000
        val g = Typography.ukur(p, 400, 400, intArrayOf(0, 0, 400, 400), intArrayOf(10, 10, 210, 210))
        assertEquals(20f, g.tebalGoresan, 1e-6f)
        assertEquals(0.01f, g.kepadatan, 1e-4f)
        assertEquals(Typography.Berat.TIPIS, g.berat)
    }

    @Test
    fun `tebal goresan adalah median panjang jalur tinta`() {
        val p = halaman(100, 100)
        // Tiga baris dengan panjang jalur 2, 4, 6 -> median 4.
        garis(p, 100, 12, 12, 14, 1)
        garis(p, 100, 14, 12, 16, 1)
        garis(p, 100, 16, 12, 18, 1)
        val g = Typography.ukur(p, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(4f, g.tebalGoresan, 1e-6f)
    }

    @Test
    fun `jalur tinta yang menyentuh tepi kanan blok tetap terhitung`() {
        val p = halaman(100, 100)
        // Jalur berakhir persis di batas blok: kalau penutupan jalur di akhir
        // baris lupa dilakukan, panjang ini hilang dari perhitungan.
        garis(p, 100, 20, 55, 60, 1)
        val g = Typography.ukur(p, 100, 100, intArrayOf(0, 0, 100, 100), intArrayOf(10, 10, 60, 60))
        assertEquals(5f, g.tebalGoresan, 1e-6f)
    }

    // ------------------------------------------------------------------
    // cariUkuran()
    // ------------------------------------------------------------------

    @Test
    fun `cari ukuran menemukan nilai terbesar yang muat`() {
        for (batas in 8..40) {
            val hasil = Typography.cariUkuran(8, 40) { it <= batas }
            assertEquals("batas $batas", batas, hasil)
        }
    }

    @Test
    fun `cari ukuran sama dengan pencarian menurun untuk semua batas`() {
        // Perbandingan langsung dengan algoritme lama yang digantikan.
        for (batas in 0..50) {
            val lama = (40 downTo 8).firstOrNull { it <= batas } ?: 8
            val baru = Typography.cariUkuran(8, 40) { it <= batas }
            assertEquals("batas $batas", lama, baru)
        }
    }

    @Test
    fun `cari ukuran mengembalikan minimum bila tidak ada yang muat`() {
        assertEquals(8, Typography.cariUkuran(8, 40) { false })
    }

    @Test
    fun `cari ukuran memanggil predikat jauh lebih jarang daripada pencarian menurun`() {
        var panggilan = 0
        val hasil = Typography.cariUkuran(8, 96) { panggilan++; it <= 9 }
        assertEquals(9, hasil)
        // Pencarian menurun memerlukan 88 panggilan untuk kasus ini.
        assertTrue("perlu $panggilan panggilan", panggilan <= 10)
    }

    @Test
    fun `cari ukuran menangani rentang satu nilai`() {
        assertEquals(12, Typography.cariUkuran(12, 12) { true })
        assertEquals(12, Typography.cariUkuran(12, 12) { false })
    }

    @Test
    fun `cari ukuran menangani maksimum lebih kecil daripada minimum`() {
        assertEquals(20, Typography.cariUkuran(20, 5) { true })
    }

    // ------------------------------------------------------------------
    // putuskan()
    // ------------------------------------------------------------------

    @Test
    fun `gaya tak terukur menghasilkan rencana yang sama dengan perilaku lama`() {
        val r = Typography.putuskan(Typography.Gaya.BAWAAN, 24, 30, false)
        assertEquals(Typography.SPASI_NORMAL, r.spasiBaris, 1e-6f)
        assertEquals(Typography.ISI_BAWAAN, r.skalaLebar, 1e-6f)
        assertEquals(Typography.ISI_BAWAAN, r.skalaTinggi, 1e-6f)
        assertEquals(Typography.Berat.NORMAL, r.berat)
        assertEquals(24, r.ukuranFont)
    }

    @Test
    fun `balon padat mendapat baris rapat dan balon lengang mendapat baris longgar`() {
        val padat = Typography.Gaya(
            Typography.Arah.TEGAK, 0.8f, 0.20f, 2f, Typography.Berat.TEBAL, true
        )
        val lengang = Typography.Gaya(
            Typography.Arah.DATAR, 0.8f, 0.01f, 1f, Typography.Berat.TIPIS, true
        )
        assertEquals(Typography.SPASI_RAPAT, Typography.putuskan(padat, 20, 30, false).spasiBaris, 1e-6f)
        assertEquals(Typography.SPASI_LONGGAR, Typography.putuskan(lengang, 20, 30, false).spasiBaris, 1e-6f)
    }

    @Test
    fun `teks sangat panjang selalu memaksa baris rapat`() {
        val lengang = Typography.Gaya(
            Typography.Arah.DATAR, 0.8f, 0.01f, 1f, Typography.Berat.TIPIS, true
        )
        val pendek = Typography.putuskan(lengang, 20, 30, false)
        val panjang = Typography.putuskan(lengang, 20, 200, false)
        assertEquals(Typography.SPASI_LONGGAR, pendek.spasiBaris, 1e-6f)
        assertEquals(Typography.SPASI_RAPAT, panjang.spasiBaris, 1e-6f)
        assertNotEquals(pendek.spasiBaris, panjang.spasiBaris)
    }

    @Test
    fun `skala mengikuti rasio isi terukur`() {
        val g = Typography.Gaya(
            Typography.Arah.DATAR, 0.63f, 0.07f, 2f, Typography.Berat.NORMAL, true
        )
        val r = Typography.putuskan(g, 20, 30, false)
        assertEquals(0.63f, r.skalaLebar, 1e-6f)
        assertEquals(0.63f, r.skalaTinggi, 1e-6f)
    }

    @Test
    fun `arah tegak mengikuti bahasa sasaran bukan hasil pengukuran`() {
        // Sumber tategaki, sasaran bahasa mendatar: hasilnya HARUS mendatar.
        // Merender bahasa Indonesia secara tegak memberi satu huruf per baris.
        val tategaki = Typography.Gaya(
            Typography.Arah.TEGAK, 0.8f, 0.07f, 2f, Typography.Berat.NORMAL, true
        )
        assertFalse(Typography.putuskan(tategaki, 20, 30, bahasaTegak = false).tegak)
        assertTrue(Typography.putuskan(tategaki, 20, 30, bahasaTegak = true).tegak)

        // Dan sebaliknya: sumber mendatar, sasaran bahasa tegak.
        val datar = Typography.Gaya(
            Typography.Arah.DATAR, 0.8f, 0.07f, 2f, Typography.Berat.NORMAL, true
        )
        assertTrue(Typography.putuskan(datar, 20, 30, bahasaTegak = true).tegak)
    }

    @Test
    fun `ukuran font dijepit ke rentang yang sah`() {
        val g = Typography.Gaya.BAWAAN
        assertEquals(Typography.FONT_MIN, Typography.putuskan(g, 1, 10, false).ukuranFont)
        assertEquals(Typography.FONT_MAKS, Typography.putuskan(g, 5000, 10, false).ukuranFont)
    }

    // ------------------------------------------------------------------
    // lebarGarisLuar()
    // ------------------------------------------------------------------

    @Test
    fun `garis luar mengikuti berat huruf`() {
        val normal = Typography.lebarGarisLuar(44, Typography.Berat.NORMAL)
        val tebal = Typography.lebarGarisLuar(44, Typography.Berat.TEBAL)
        val tipis = Typography.lebarGarisLuar(44, Typography.Berat.TIPIS)
        assertEquals(4f, normal, 1e-6f)
        assertTrue(tebal > normal)
        assertTrue(tipis < normal)
    }

    @Test
    fun `garis luar tidak pernah lebih tipis daripada satu piksel`() {
        for (ukuran in 1..12) {
            for (b in Typography.Berat.values()) {
                val w = Typography.lebarGarisLuar(ukuran, b)
                assertTrue("ukuran $ukuran berat $b -> $w", w >= 0.75f)
            }
        }
    }

    @Test
    fun `garis luar untuk huruf normal sama dengan rumus lama`() {
        // Paritas: berat NORMAL harus menghasilkan angka yang sama persis
        // dengan max(1f, size / 11f) yang dipakai renderer sebelumnya.
        for (ukuran in 8..96) {
            val lama = maxOf(1f, ukuran / 11f)
            assertEquals(
                "ukuran $ukuran", lama,
                Typography.lebarGarisLuar(ukuran, Typography.Berat.NORMAL), 1e-6f
            )
        }
    }
}
