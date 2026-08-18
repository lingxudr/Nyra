package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode

/**
 * Warna garis luar huruf.
 *
 * Bahaya utama fitur ini bukan gagal mendeteksi, melainkan SALAH mendeteksi:
 * setiap huruf yang digambar dengan anti-aliasing dikelilingi piksel campuran
 * teks+latar. Kalau piksel itu disangka garis luar, SEMUA balon normal akan
 * mendapat garis luar abu-abu yang tidak pernah ada di gambar aslinya.
 *
 * Karena itu pembedanya geometris: campuran anti-aliasing selalu terletak di
 * ruas garis fg-bg dalam ruang RGB, sedangkan garis luar sungguhan tidak.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeColorTest {

    // ---- geometri ruas ----

    @Test
    fun campuranTepatDiTengahBerjarakNol() {
        val abu = Color.rgb(128, 128, 128)
        val d = Palette.jarakKeRuas(abu, Color.BLACK, Color.WHITE)
        assertTrue("campuran hitam-putih harus ada di ruas, dapat $d", d < 1f)
    }

    @Test
    fun warnaDiLuarRuasBerjarakJauh() {
        // Merah tidak bisa dihasilkan dari campuran hitam dan putih.
        val d = Palette.jarakKeRuas(Color.RED, Color.BLACK, Color.WHITE)
        assertTrue("merah harus jauh dari ruas hitam-putih, dapat $d", d > 100f)
    }

    @Test
    fun fgSamaDenganBgTidakMembuatPembagianNol() {
        val d = Palette.jarakKeRuas(Color.RED, Color.WHITE, Color.WHITE)
        assertTrue("harus berupa angka wajar, dapat $d", d.isFinite() && d > 0f)
    }

    // ---- penaksiran warna ----

    /** Balon normal: teks hitam, latar putih, tepi huruf abu-abu. */
    @Test
    fun antiAliasingBiasaTidakDianggapGarisLuar() {
        val piksel = ArrayList<Int>()
        repeat(300) { piksel.add(Color.WHITE) }
        repeat(120) { piksel.add(Color.BLACK) }
        // Seluruh tangga abu-abu yang mungkin dihasilkan anti-aliasing.
        for (v in 20..240 step 10) repeat(4) { piksel.add(Color.rgb(v, v, v)) }

        assertNull(
            "tepi huruf tidak boleh disangka garis luar",
            Palette.warnaGarisLuar(piksel, Color.WHITE, Color.BLACK)
        )
    }

    /** Teks hitam bergaris luar putih di atas panel gelap. */
    @Test
    fun garisLuarPutihTerdeteksiDiPanelGelap() {
        val gelap = Color.rgb(30, 30, 40)
        val piksel = ArrayList<Int>()
        repeat(200) { piksel.add(gelap) }
        repeat(100) { piksel.add(Color.BLACK) }
        repeat(80) { piksel.add(Color.WHITE) }

        val hasil = Palette.warnaGarisLuar(piksel, gelap, Color.BLACK)
        assertNotNull("garis luar putih harus terdeteksi", hasil)
        assertTrue("harus mendekati putih, dapat ${Integer.toHexString(hasil!!)}",
            Palette.luminance(hasil) > 200f)
    }

    @Test
    fun beberapaPikselNyasarTidakCukupJadiBukti() {
        val piksel = ArrayList<Int>()
        repeat(500) { piksel.add(Color.WHITE) }
        repeat(200) { piksel.add(Color.BLACK) }
        repeat(3) { piksel.add(Color.RED) }   // debu kompresi JPEG

        assertNull(
            "tiga piksel nyasar tidak boleh mengubah tipografi seluruh balon",
            Palette.warnaGarisLuar(piksel, Color.WHITE, Color.BLACK)
        )
    }

    @Test
    fun daftarKosongAman() {
        assertNull(Palette.warnaGarisLuar(emptyList(), Color.WHITE, Color.BLACK))
    }

    // ---- integrasi dengan sample() ----

    /**
     * Empat "huruf" tebal di dalam kotak teks 40..160 x 70..130.
     *
     * Perbandingan ukurannya sengaja dibuat seperti tipografi sungguhan:
     * badan huruf 16 px, garis luar hanya 3 px. Itu penting - kalau garis
     * luarnya dibuat setebal hurufnya, bagian dalam pita garis luar sendiri
     * jadi lebih luas daripada isi huruf, dan "isi" kehilangan artinya.
     */
    private fun bitmapTeks(
        latar: Int, isiHuruf: Int, garis: Int?, aa: Boolean
    ): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(latar)
        val p = Paint().apply { isAntiAlias = aa }
        var x = 45f
        while (x <= 135f) {
            if (garis != null) {
                p.color = garis
                c.drawRect(x - 3f, 77f, x + 19f, 123f, p)
            }
            p.color = isiHuruf
            c.drawRect(x, 80f, x + 16f, 120f, p)
            x += 30f
        }
        return bmp
    }

    @Test
    fun balonPutihBiasaTidakMendapatGarisLuar() {
        val bmp = bitmapTeks(Color.WHITE, Color.BLACK, null, aa = true)
        val col = Palette.sample(bmp, intArrayOf(10, 10, 190, 190), intArrayOf(40, 70, 160, 130))
        assertTrue("latar harus terang", Palette.luminance(col.background) > 200f)
        assertNull("balon normal harus tetap memakai garis luar bawaan", col.garisLuar)
    }

    /**
     * Kasus nyata yang jadi alasan fitur ini ada: teks lepas di atas panel
     * (SFX / teriakan), huruf putih bergaris luar hitam. Sebelum ronde ini
     * garis luarnya digambar memakai warna latar panel, sehingga huruf putih
     * kehilangan batas dan lebur ke gambar.
     */
    @Test
    fun sfxPutihBergarisLuarHitamTerukur() {
        val panel = Color.rgb(128, 128, 128)
        val bmp = bitmapTeks(panel, Color.WHITE, Color.BLACK, aa = false)
        val col = Palette.sample(bmp, intArrayOf(10, 10, 190, 190), intArrayOf(40, 70, 160, 130))

        assertTrue(
            "isi huruf harus terbaca putih, dapat ${Integer.toHexString(col.foreground)}",
            Palette.luminance(col.foreground) > 200f
        )
        assertNotNull("garis luar hitam harus terukur", col.garisLuar)
        assertTrue(
            "garis luar harus gelap, dapat ${Integer.toHexString(col.garisLuar!!)}",
            Palette.luminance(col.garisLuar!!) < 70f
        )
    }

    /**
     * Isi dan tepi tidak boleh tertukar. Ini regresi yang sesungguhnya:
     * aturan lama memilih warna yang paling jauh dari latar sebagai warna
     * huruf, dan pada teks bergaris luar yang terpilih justru garis luarnya.
     */
    @Test
    fun isiDanTepiTidakTertukar() {
        val panel = Color.rgb(128, 128, 128)
        val bmp = bitmapTeks(panel, Color.WHITE, Color.BLACK, aa = false)
        val col = Palette.sample(bmp, intArrayOf(10, 10, 190, 190), intArrayOf(40, 70, 160, 130))
        assertTrue(
            "huruf harus lebih terang daripada garis luarnya",
            Palette.luminance(col.foreground) > Palette.luminance(col.garisLuar ?: Color.BLACK)
        )
    }

    /**
     * Garis luar yang senada dengan warna hurufnya tidak berguna: menggambarnya
     * hanya menebalkan huruf, dan pada kasus buruk menghapus bentuknya.
     */
    @Test
    fun garisLuarSewarnaHurufDitolak() {
        val hasil = Palette.warnaGarisLuar(
            List(200) { Color.WHITE } + List(100) { Color.rgb(10, 10, 10) } +
                List(80) { Color.rgb(40, 40, 40) },
            Color.WHITE, Color.rgb(10, 10, 10)
        )
        // Boleh null, tapi kalau tidak null ia wajib kontras dengan hurufnya.
        if (hasil != null) {
            assertTrue(
                "garis luar tidak boleh senada dengan huruf",
                kotlin.math.abs(Palette.luminance(hasil) - Palette.luminance(Color.rgb(10, 10, 10))) >= 60f
            )
        }
    }

    /** Nilai bawaan wajib tetap tanpa garis luar supaya perilaku lama utuh. */
    @Test
    fun nilaiBawaanTidakPunyaGarisLuar() {
        assertNull(Palette.DEFAULT.garisLuar)
        assertEquals(Color.WHITE, Palette.DEFAULT.background)
    }
}
