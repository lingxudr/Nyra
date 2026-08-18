package com.cypy.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode

/**
 * Pengambilan warna balon. Inilah yang menutup celah lama: balon hitam/navy
 * terdeteksi dengan benar tetapi dicat putih saat render.
 *
 * Bitmap dibuat sintetis meniru bukti pengguna: balon hitam berisi teks putih
 * ("I AM PELLIN...") dan balon navy berisi teks putih.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class PaletteTest {

    private fun halaman(
        warnaLatar: Int,
        warnaBalon: Int,
        warnaTeks: Int
    ): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(warnaLatar)
        c.drawOval(RectF(50f, 50f, 350f, 350f), Paint().apply {
            color = warnaBalon; isAntiAlias = true
        })
        // "teks": beberapa batang tebal di tengah balon
        val p = Paint().apply { color = warnaTeks; isAntiAlias = false }
        var x = 130f
        while (x < 270f) {
            c.drawRect(x, 170f, x + 12f, 230f, p)
            x += 26f
        }
        return bmp
    }

    /** Kotak teks yang meniru keluaran text_bubble RT-DETR. */
    private val kotakTeks = intArrayOf(125, 165, 275, 235)
    private val kotakBalon = intArrayOf(50, 50, 350, 350)

    @Test
    fun balonPutihMenghasilkanPutihDanHitam() {
        val bmp = halaman(Color.rgb(200, 220, 240), Color.WHITE, Color.BLACK)
        val c = Palette.sample(bmp, kotakBalon, kotakTeks)
        assertTrue(c.diukur)
        assertFalse(Palette.isDark(c.background))
        assertTrue("latar harus terang", Palette.luminance(c.background) > 200f)
        assertTrue("teks harus gelap", Palette.luminance(c.foreground) < 90f)
    }

    @Test
    fun balonHitamTetapHitamBukanDicatPutih() {
        val bmp = halaman(Color.WHITE, Color.rgb(8, 8, 8), Color.WHITE)
        val c = Palette.sample(bmp, kotakBalon, kotakTeks)
        assertTrue(c.diukur)
        assertTrue("balon hitam harus terbaca gelap", Palette.isDark(c.background))
        assertTrue(Palette.luminance(c.background) < 40f)
        assertTrue("teksnya harus terang", Palette.luminance(c.foreground) > 180f)
    }

    @Test
    fun balonNavyMempertahankanRonaBiru() {
        val navy = Color.rgb(12, 20, 64)
        val bmp = halaman(Color.WHITE, navy, Color.WHITE)
        val c = Palette.sample(bmp, kotakBalon, kotakTeks)
        assertTrue(Palette.isDark(c.background))
        assertTrue(
            "komponen biru harus dominan",
            Color.blue(c.background) > Color.red(c.background)
        )
        assertTrue(Palette.luminance(c.foreground) > 180f)
    }

    @Test
    fun tanpaKotakTeksWarnaLatarTetapTerukur() {
        val bmp = halaman(Color.WHITE, Color.rgb(8, 8, 8), Color.WHITE)
        val c = Palette.sample(bmp, kotakBalon, null)
        assertTrue(c.diukur)
        assertTrue(Palette.isDark(c.background))
        // Tanpa kotak teks, warna huruf jatuh ke lawan latar.
        assertEquals(Color.WHITE, c.foreground)
    }

    @Test
    fun kotakTerlaluKecilJatuhKeBawaan() {
        val bmp = halaman(Color.WHITE, Color.BLACK, Color.WHITE)
        val c = Palette.sample(bmp, intArrayOf(10, 10, 12, 12), null)
        assertFalse(c.diukur)
        assertEquals(Color.WHITE, c.background)
        assertEquals(Color.BLACK, c.foreground)
    }

    @Test
    fun kontrasRendahDipaksaKembaliKeHitamPutih() {
        // Balon abu-abu dengan "teks" abu-abu nyaris sama: harus dipaksa aman.
        val abu = Color.rgb(130, 130, 130)
        val bmp = halaman(Color.WHITE, abu, Color.rgb(138, 138, 138))
        val c = Palette.sample(bmp, kotakBalon, kotakTeks)
        val beda = kotlin.math.abs(
            Palette.luminance(c.foreground) - Palette.luminance(c.background)
        )
        assertTrue("kontras hasil harus layak baca", beda >= 60f)
    }

    /**
     * Regresi ronde 12. RT-DETR mengembalikan kotak balon yang selalu lebih
     * longgar daripada ovalnya, sehingga keempat sudut kotak berisi latar
     * halaman. Dulu seluruh isi kotak ikut disampel dan latar terang itu
     * memenangkan modus warna, membuat balon navy pada bukti pengguna
     * (Screenshot 2026-08-17 07-59-08) salah terbaca putih.
     *
     * Di sini ovalnya sengaja digambar jauh lebih kecil dari kotak balon.
     */
    @Test
    fun ovalGelapDidalamKotakLonggarTidakTerbacaTerang() {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        // Oval hanya mengisi bagian tengah kotak balon 50..350.
        c.drawOval(RectF(92f, 92f, 308f, 308f), Paint().apply {
            color = Color.rgb(8, 24, 72); isAntiAlias = true
        })
        val p = Paint().apply { color = Color.WHITE; isAntiAlias = false }
        var x = 130f
        while (x < 270f) {
            c.drawRect(x, 170f, x + 12f, 230f, p)
            x += 26f
        }
        val warna = Palette.sample(bmp, kotakBalon, kotakTeks)
        assertTrue(warna.diukur)
        assertTrue(
            "sudut kotak yang berisi latar halaman tidak boleh menang suara",
            Palette.isDark(warna.background)
        )
        assertTrue(Palette.luminance(warna.background) < 60f)
        assertTrue(
            "rona biru balon harus bertahan",
            Color.blue(warna.background) > Color.red(warna.background)
        )
        assertTrue("teksnya harus terang", Palette.luminance(warna.foreground) > 180f)
    }

    /**
     * Halaman dengan DUA baris teks berwarna berbeda di satu balon —
     * persis bukti pengguna ronde 16: "GIVE ME" hijau tua di atas
     * "FOOD." hitam.
     */
    private fun halamanDuaWarna(warna1: Int, warna2: Int): Bitmap {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(200, 220, 240))
        c.drawOval(RectF(50f, 50f, 350f, 350f), Paint().apply {
            color = Color.WHITE; isAntiAlias = true
        })
        val p1 = Paint().apply { color = warna1; isAntiAlias = false }
        val p2 = Paint().apply { color = warna2; isAntiAlias = false }
        var x = 130f
        while (x < 270f) {
            c.drawRect(x, 150f, x + 12f, 195f, p1)   // baris 1
            c.drawRect(x, 215f, x + 12f, 260f, p2)   // baris 2
            x += 26f
        }
        return bmp
    }

    private val kotakDuaBaris = intArrayOf(125, 145, 275, 265)

    @Test
    fun duaBarisBerbedaWarnaTerekamTerpisah() {
        val hijau = Color.rgb(0, 90, 58)
        val bmp = halamanDuaWarna(hijau, Color.BLACK)
        val c = Palette.sample(bmp, kotakBalon, kotakDuaBaris)
        assertTrue(c.diukur)
        assertEquals("harus mengenali 2 baris", 2, c.warnaBaris.size)
        val b1 = c.warnaBaris[0]
        val b2 = c.warnaBaris[1]
        assertTrue(
            "baris 1 harus hijau, bukan hitam: got ${Integer.toHexString(b1)}",
            Color.green(b1) > 60 && Color.green(b1) > Color.red(b1) + 40
        )
        assertTrue(
            "baris 2 harus gelap netral: got ${Integer.toHexString(b2)}",
            Palette.luminance(b2) < 40f && Color.green(b2) < 40
        )
    }

    /**
     * Inti bug ronde 16: merata-rata hijau dan hitam menghasilkan hijau
     * lumpur yang tidak ada di gambar. Modus harus mengembalikan warna nyata.
     */
    @Test
    fun warnaDominanMemilihWarnaNyataBukanRataRata() {
        val hijau = Color.rgb(0, 90, 58)
        val piksel = ArrayList<Int>()
        repeat(60) { piksel.add(hijau) }
        repeat(40) { piksel.add(Color.BLACK) }
        val d = Palette.warnaDominan(piksel)!!
        assertEquals("harus hijau utuh", 0, Color.red(d))
        assertEquals(90, Color.green(d))
        assertEquals(58, Color.blue(d))
        // Rata-rata hijau+hitam akan memberi hijau lumpur (~54); modus tidak.
        assertTrue("tidak boleh jadi rata-rata lumpur", Color.green(d) != 54)
    }

    @Test
    fun teksSatuWarnaTidakMengisiWarnaBaris() {
        val bmp = halaman(Color.rgb(200, 220, 240), Color.WHITE, Color.BLACK)
        val c = Palette.sample(bmp, kotakBalon, kotakTeks)
        assertTrue(
            "teks satu warna tidak perlu daftar per baris",
            c.warnaBaris.isEmpty()
        )
    }

    @Test
    fun pemetaanBarisProporsional() {
        val hijau = Color.rgb(0, 90, 58)
        val warna = Palette.Colors(Color.WHITE, Color.BLACK, true, listOf(hijau, Color.BLACK))
        val r = TextRenderer(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        // 2 baris terjemahan -> pemetaan satu-satu
        assertEquals(hijau, r.warnaBarisKe(0, 2, warna))
        assertEquals(Color.BLACK, r.warnaBarisKe(1, 2, warna))
        // 1 baris terjemahan -> warna baris pertama
        assertEquals(hijau, r.warnaBarisKe(0, 1, warna))
        // 3 baris terjemahan -> tengah membulat ke salah satu ujung
        assertEquals(hijau, r.warnaBarisKe(0, 3, warna))
        assertEquals(Color.BLACK, r.warnaBarisKe(2, 3, warna))
    }

    @Test
    fun tanpaWarnaBarisJatuhKeForeground() {
        val warna = Palette.Colors(Color.WHITE, Color.rgb(10, 20, 30), true)
        val r = TextRenderer(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertEquals(Color.rgb(10, 20, 30), r.warnaBarisKe(0, 2, warna))
        assertEquals(Color.rgb(10, 20, 30), r.warnaBarisKe(1, 2, warna))
    }

    @Test
    fun luminansiMengikutiRec601() {
        assertEquals(0f, Palette.luminance(Color.BLACK), 0.01f)
        assertEquals(255f, Palette.luminance(Color.WHITE), 0.01f)
        assertTrue(Palette.isDark(Color.rgb(20, 20, 20)))
        assertFalse(Palette.isDark(Color.rgb(240, 240, 240)))
    }
}
