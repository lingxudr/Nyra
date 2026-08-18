package com.nyra.comic

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Perilaku pembaca non-destruktif yang paling gampang rusak: kepemilikan bitmap.
 *
 * Pembaca menahan DUA bitmap sekaligus — halaman asli dan halaman terjemahan —
 * lalu menukarnya bolak-balik. PageView aslinya dibuat untuk editor, yang hanya
 * punya satu bitmap sekali pakai dan karena itu mendaur ulang yang lama setiap
 * kali halaman diganti. Kalau perilaku itu terbawa ke pembaca, menekan tombol
 * lapisan dua kali akan memakai bitmap yang sudah dibuang: layar kosong, atau
 * "Canvas: trying to use a recycled bitmap". Tes ini mengunci pemisahan itu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderLayerTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun bmp(w: Int = 40, h: Int = 60): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    // ------------------------------------------------------------------
    // Kepemilikan bitmap
    // ------------------------------------------------------------------

    @Test
    fun `mode editor mendaur ulang bitmap lama supaya hemat memori`() {
        val v = PageView(ctx)
        val lama = bmp()
        v.setPage(lama, emptyList())
        v.setPage(bmp(), emptyList())
        assertTrue("editor seharusnya membuang bitmap lama", lama.isRecycled)
    }

    @Test
    fun `mode pembaca tidak boleh mendaur ulang bitmap milik activity`() {
        val v = PageView(ctx)
        v.milikSendiri = false
        val asli = bmp()
        val terjemahan = bmp()

        // Tukar lapisan bolak-balik, seperti pengguna menekan tombol.
        v.setPage(asli, emptyList())
        v.setPage(terjemahan, emptyList())
        v.setPage(asli, emptyList())
        v.setPage(terjemahan, emptyList())

        assertFalse("bitmap asli ikut dibuang", asli.isRecycled)
        assertFalse("bitmap terjemahan ikut dibuang", terjemahan.isRecycled)
    }

    @Test
    fun `melepas halaman di mode pembaca tidak membuang bitmapnya`() {
        val v = PageView(ctx)
        v.milikSendiri = false
        val b = bmp()
        v.setPage(b, emptyList())
        // Persis yang dilakukan ReaderActivity.onDestroy sebelum membebaskan sendiri.
        v.setPage(null, emptyList())
        assertFalse(b.isRecycled)
        b.recycle()
        assertTrue(b.isRecycled)
    }

    @Test
    fun `memasang bitmap yang sama dua kali tidak membuangnya`() {
        // Terjadi saat halaman tidak punya versi terjemahan dan pembaca jatuh
        // kembali ke bitmap asli untuk kedua lapisan.
        val v = PageView(ctx)
        val b = bmp()
        v.setPage(b, emptyList())
        v.setPage(b, emptyList())
        assertFalse(b.isRecycled)
    }

    // ------------------------------------------------------------------
    // Gambar ulang dari proyek: aslinya tidak pernah berubah
    // ------------------------------------------------------------------

    @Test
    fun `menggambar ulang berkali kali tidak pernah mengubah halaman sumber`() {
        val cfg = Config(ctx)
        cfg.simpanProyek = true
        val prj = Project("prj_t", "Uji", "indonesian", 0L, 0L)
        val dir = Project.dirFor(ctx, prj.id).apply { mkdirs() }
        File(dir, "pages").mkdirs()

        // Halaman putih dengan satu balon.
        val sumber = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        sumber.eraseColor(android.graphics.Color.WHITE)
        val f = File(dir, "pages/p0000.png")
        f.outputStream().use { sumber.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val sebelum = f.readBytes()

        val page = Project.Page("a.png", "pages/p0000.png", 200, 300)
        page.boxes.add(intArrayOf(40, 60, 160, 200))
        page.translations["1"] = "Halo dunia"
        prj.pages.add(page)

        val pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        val a = pipeline.renderProjectPage(prj, page)
        val b = pipeline.renderProjectPage(prj, page)

        assertNotNull(a)
        assertNotNull(b)
        // Inti non-destruktif: berkas sumber di disk identik byte demi byte.
        assertTrue("halaman sumber ikut ditimpa", sebelum.contentEquals(f.readBytes()))

        // Dan hasilnya deterministik: menyunting balon yang sama sepuluh kali
        // tetap memberi gambar yang sama, bukan tumpukan cat yang menumpuk.
        assertEquals(a!!.width, b!!.width)
        assertEquals(a.height, b.height)
        var beda = 0
        for (y in 0 until a.height step 7) {
            for (x in 0 until a.width step 7) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) beda++
            }
        }
        assertEquals("gambar ulang tidak deterministik", 0, beda)

        Project.delete(ctx, prj.id)
    }

    @Test
    fun `halaman terjemahan benar benar berbeda dari halaman asli`() {
        // Kalau keduanya identik, tombol lapisan tidak ada gunanya dan bug
        // semacam itu tidak akan ketahuan lewat tes yang cuma memeriksa null.
        val cfg = Config(ctx)
        val prj = Project("prj_t2", "Uji", "indonesian", 0L, 0L)
        val dir = Project.dirFor(ctx, prj.id).apply { mkdirs() }
        File(dir, "pages").mkdirs()

        val sumber = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        sumber.eraseColor(android.graphics.Color.WHITE)
        File(dir, "pages/p0000.png").outputStream()
            .use { sumber.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val page = Project.Page("a.png", "pages/p0000.png", 200, 300)
        page.boxes.add(intArrayOf(40, 60, 160, 200))
        page.translations["1"] = "HALO"
        prj.pages.add(page)

        val pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        val hasil = pipeline.renderProjectPage(prj, page)!!
        val asli = Storage.decodeBitmap(prj.pageFile(ctx, page), cfg.maxImageSide)!!

        assertEquals(asli.width, hasil.width)
        assertEquals(asli.height, hasil.height)

        var beda = 0
        for (y in 0 until asli.height) {
            for (x in 0 until asli.width) {
                if (asli.getPixel(x, y) != hasil.getPixel(x, y)) beda++
            }
        }
        assertTrue("terjemahan tidak menggambar apa pun (beda=$beda)", beda > 100)

        Project.delete(ctx, prj.id)
    }

    @Test
    fun `halaman tanpa terjemahan tetap bisa digambar dan tidak kosong`() {
        val cfg = Config(ctx)
        val prj = Project("prj_t3", "Uji", "indonesian", 0L, 0L)
        val dir = Project.dirFor(ctx, prj.id).apply { mkdirs() }
        File(dir, "pages").mkdirs()

        val sumber = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        sumber.eraseColor(android.graphics.Color.WHITE)
        File(dir, "pages/p0000.png").outputStream()
            .use { sumber.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val page = Project.Page("a.png", "pages/p0000.png", 120, 160)
        page.boxes.add(intArrayOf(10, 10, 100, 100))
        prj.pages.add(page)

        val pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        val hasil = pipeline.renderProjectPage(prj, page)
        assertNotNull("halaman belum diterjemahkan seharusnya tetap tampil", hasil)
        assertEquals(120, hasil!!.width)

        Project.delete(ctx, prj.id)
    }
}
