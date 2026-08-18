package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Cache terjemahan.
 *
 * Sidik gambar diuji dengan GraphicsMode.NATIVE: tanpa itu Canvas Robolectric
 * tidak menggambar apa pun dan setiap bitmap menghasilkan sidik identik,
 * sehingga tes "dua balon berbeda punya sidik berbeda" lulus secara semu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationCacheTest {

    private lateinit var dir: File
    private lateinit var berkas: File

    @Before
    fun siap() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        dir = File(ctx.cacheDir, "cachetest_${System.nanoTime()}").apply { mkdirs() }
        berkas = File(dir, "cache.json")
    }

    /** Bitmap dengan satu bentuk gelap; posisi bentuk membedakan isinya. */
    private fun potongan(w: Int, h: Int, geser: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        c.drawRect(
            (10 + geser).toFloat(), 10f,
            (10 + geser + w / 3).toFloat(), (h - 10).toFloat(), p
        )
        return bmp
    }

    // ------------------------------------------------------------------
    // Sidik gambar
    // ------------------------------------------------------------------

    @Test
    fun `gambar identik menghasilkan sidik sama`() {
        val a = potongan(120, 200, 0)
        val b = potongan(120, 200, 0)
        assertEquals(TranslationCache.sidik(a), TranslationCache.sidik(b))
    }

    @Test
    fun `gambar berbeda menghasilkan sidik berbeda`() {
        val a = potongan(120, 200, 0)
        val b = potongan(120, 200, 40)
        assertNotEquals(TranslationCache.sidik(a), TranslationCache.sidik(b))
    }

    /**
     * Batas yang disengaja, didokumentasikan supaya tidak disangka bug.
     *
     * Sidiknya EKSAK, jadi mengubah resolusi kerja mengubah interpolasi tepi
     * huruf dan menghasilkan sidik berbeda - cache meleset. Itu dipilih di atas
     * sidik perseptual: cache yang meleset hanya memakan satu request, cache
     * yang salah menyisipkan terjemahan balon lain tanpa jejak.
     *
     * Tes ini mengunci keputusan tersebut. Bila suatu saat sidik perseptual
     * dipakai, tes ini HARUS gagal dan ditinjau, bukan diam-diam lolos.
     */
    @Test
    fun `skala berbeda sengaja dianggap berbeda`() {
        val kecil = potongan(120, 200, 0)
        val besar = potongan(240, 400, 0)
        assertNotEquals(
            "sidik eksak tidak tahan skala; lihat catatan di TranslationCache",
            TranslationCache.sidik(kecil), TranslationCache.sidik(besar)
        )
    }

    /**
     * Yang benar-benar penting untuk kasus pemakaian nyata: menjalankan ulang
     * berkas yang sama dengan setelan yang sama menghasilkan potongan identik
     * bit demi bit, sehingga sidiknya kena.
     */
    @Test
    fun `derau kecerahan ringan tetap kena`() {
        val bersih = potongan(120, 200, 0)
        // Geser seluruh piksel satu tingkat: masih dalam satu ember kuantisasi.
        val berderau = potongan(120, 200, 0).also { bmp ->
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (i in px.indices) {
                val p = px[i]
                val r = ((p shr 16 and 0xFF) + 1).coerceAtMost(255)
                val g = ((p shr 8 and 0xFF) + 1).coerceAtMost(255)
                val b = ((p and 0xFF) + 1).coerceAtMost(255)
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        }
        assertEquals(TranslationCache.sidik(bersih), TranslationCache.sidik(berderau))
    }

    @Test
    fun `kunci memisahkan bahasa dan model`() {
        val s = "abc123"
        val a = TranslationCache.kunci(s, "Indonesian", "gemini-flash-latest")
        val b = TranslationCache.kunci(s, "English", "gemini-flash-latest")
        val c = TranslationCache.kunci(s, "Indonesian", "gpt-4o")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        // Beda huruf besar/kecil dan spasi bukan beda kunci.
        assertEquals(a, TranslationCache.kunci(s, " indonesian ", "GEMINI-FLASH-LATEST"))
    }

    // ------------------------------------------------------------------
    // Simpan dan ambil
    // ------------------------------------------------------------------

    @Test
    fun `simpan lalu ambil mengembalikan teks yang sama`() {
        val c = TranslationCache(berkas)
        c.simpan("k1", "Halo dunia", 0.6f)
        assertEquals("Halo dunia", c.ambil("k1", 0.6f))
        assertEquals(1, c.kena)
    }

    @Test
    fun `kunci tidak dikenal mengembalikan null`() {
        val c = TranslationCache(berkas)
        assertNull(c.ambil("belum-ada", 0.6f))
        assertEquals(1, c.meleset)
    }

    /**
     * Teks kosong tidak boleh terkunci di cache: itu berarti model gagal
     * menjawab, dan menyimpannya membuat balon tersebut tidak akan pernah
     * diterjemahkan lagi selamanya.
     */
    @Test
    fun `terjemahan kosong tidak disimpan`() {
        val c = TranslationCache(berkas)
        c.simpan("k1", "", 0.6f)
        c.simpan("k2", "   ", 0.6f)
        assertEquals(0, c.ukuran)
        assertNull(c.ambil("k1", 0.6f))
    }

    /**
     * Penjaga tabrakan: sidik sama tapi bentuk balon jauh berbeda berarti
     * hampir pasti dua balon berlainan yang kebetulan mirip pada 32x32.
     */
    @Test
    fun `rasio aspek berbeda dianggap meleset`() {
        val c = TranslationCache(berkas)
        c.simpan("k1", "Ya", 0.5f)
        assertNull("balon jauh lebih lebar tidak boleh cocok", c.ambil("k1", 2.5f))
        assertEquals("Ya", c.ambil("k1", 0.53f))
    }

    @Test
    fun `toleransi rasio bekerja dua arah`() {
        assertTrue(TranslationCache.rasioCocok(1.0f, 1.1f))
        assertTrue(TranslationCache.rasioCocok(1.1f, 1.0f))
        assertFalse(TranslationCache.rasioCocok(1.0f, 2.0f))
        assertFalse("rasio nol tidak sah", TranslationCache.rasioCocok(0f, 1f))
    }

    // ------------------------------------------------------------------
    // Ketahanan di disk
    // ------------------------------------------------------------------

    @Test
    fun `cache bertahan setelah ditulis dan dibaca ulang`() {
        val c = TranslationCache(berkas)
        c.simpan("k1", "Selamat pagi", 0.8f)
        c.simpan("k2", "Terima kasih", 1.2f)
        c.simpanKeDisk()
        assertTrue(berkas.exists())

        val lagi = TranslationCache(berkas)
        assertEquals(2, lagi.ukuran)
        assertEquals("Selamat pagi", lagi.ambil("k1", 0.8f))
        assertEquals("Terima kasih", lagi.ambil("k2", 1.2f))
    }

    @Test
    fun `berkas rusak tidak menggagalkan apa pun`() {
        berkas.writeText("{ ini bukan json yang sah ")
        val c = TranslationCache(berkas)
        assertEquals(0, c.ukuran)
        c.simpan("k1", "tetap jalan", 1f)
        assertEquals("tetap jalan", c.ambil("k1", 1f))
    }

    @Test
    fun `versi berbeda diabaikan alih-alih salah tafsir`() {
        berkas.writeText("""{"versi":99,"entri":{"k1":{"t":"lama","r":1.0,"u":0}}}""")
        val c = TranslationCache(berkas)
        assertEquals(0, c.ukuran)
    }

    @Test
    fun `bersihkan mengosongkan memori dan disk`() {
        val c = TranslationCache(berkas)
        c.simpan("k1", "x", 1f)
        c.simpanKeDisk()
        assertTrue(berkas.exists())

        c.bersihkan()
        assertEquals(0, c.ukuran)
        assertFalse(berkas.exists())
    }

    @Test
    fun `tidak menulis berkas bila tidak ada perubahan`() {
        val c = TranslationCache(berkas)
        c.simpanKeDisk()
        assertFalse("cache kosong tidak perlu berkas", berkas.exists())
    }

    /**
     * Pemangkasan LRU. Entri yang paling lama tidak dipakai yang dibuang -
     * bukan yang paling lama dibuat - supaya balon yang sering muncul (efek
     * suara, sapaan) tetap tersimpan.
     */
    @Test
    fun `pemangkasan menjaga batas jumlah entri`() {
        val c = TranslationCache(berkas)
        val batas = TranslationCache.MAKS_ENTRI
        for (i in 0 until batas + 50) c.simpan("k$i", "t$i", 1f)
        c.simpanKeDisk()

        val lagi = TranslationCache(berkas)
        assertTrue("ukuran=${lagi.ukuran}", lagi.ukuran <= batas)
    }
}
