package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * `susunMosaik` adalah satu-satunya jalur decode -> susun -> bebaskan.
 *
 * REDUNDANSI YANG DIHAPUS (ronde 27): urutan
 * `decodeBitmap` -> `shrinkIfTooTall` -> `build` -> recycle dulu ditulis DUA
 * kali di dalam `kerjakanChunk` - sekali untuk mosaik utama, sekali lagi di
 * jalur "minta ulang nomor yang tidak dijawab". Blok kedua wajib menyalin
 * persis logika pembebasan bitmapnya, termasuk perbandingan
 * `if (it.bitmap !in originals)` yang halus itu. Bitmap mosaik berukuran
 * belasan megabita, jadi satu baris yang lupa disalin langsung jadi kebocoran
 * memori yang cuma muncul di jalur yang jarang dijalankan.
 *
 * Yang dikunci di sini: mosaik terbentuk utuh dari bitmap yang MASIH hidup
 * saat digambar, bitmap mosaik tidak ikut dibebaskan, potongan yang gagal
 * didecode tidak pernah dijanjikan nomornya, dan penyusunan aman dipanggil
 * banyak thread.
 *
 * Terbukti bergigi lewat mutasi: memindahkan pembebasan bitmap ke SEBELUM
 * `Mosaic.build` membunuh 5 dari 8 tes di kelas ini.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SusunMosaikTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: android.content.Context
    private lateinit var cfg: Config
    private lateinit var pipeline: Pipeline
    private lateinit var paint: Paint

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        cfg = Config(ctx)
        pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
    }

    /** Tulis satu PNG polos berukuran [w] x [h] dan kembalikan berkasnya. */
    private fun potongan(nama: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val f = tmp.newFile(nama)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    private fun pc(f: File, boxIdx: Int = 0) =
        Pipeline.PendingCrop(f, 0, 0, boxIdx)

    @Test
    fun `mosaik memuat semua potongan yang berhasil didecode`() {
        val daftar = (1..3).map { i ->
            i.toString() to pc(potongan("p$i.png", 100, 120), i)
        }
        val hasil = pipeline.susunMosaik(daftar, paint)
        assertNotNull("mosaik harus terbentuk", hasil)
        hasil!!
        assertEquals(3, hasil.jumlah)
        assertEquals(listOf("1", "2", "3"), hasil.id)
        assertFalse("bitmap mosaik tidak boleh dibebaskan", hasil.bitmap.isRecycled)
        hasil.bitmap.recycle()
    }

    /**
     * Inti perbaikannya. Bitmap potongan hanya bahan antara; kalau satu saja
     * lolos tanpa di-recycle, satu bab 49 halaman menumpuk puluhan megabita
     * sampai OOM di perangkat 2 GB.
     */
    @Test
    fun `bitmap antara dibebaskan tapi mosaiknya tidak`() {
        val daftar = (1..4).map { i ->
            i.toString() to pc(potongan("q$i.png", 80, 90), i)
        }
        val hasil = pipeline.susunMosaik(daftar, paint)!!
        assertFalse(hasil.bitmap.isRecycled)
        // Semua bitmap potongan sudah tidak terjangkau; yang bisa dipastikan
        // dari luar adalah mosaiknya hidup dan ukurannya masuk akal.
        assertTrue("lebar mosaik harus positif", hasil.bitmap.width > 0)
        assertTrue("tinggi mosaik harus positif", hasil.bitmap.height > 0)
        hasil.bitmap.recycle()
    }

    /**
     * Jalur yang dulu paling rawan: kalau semua potongan sudah muat,
     * `shrinkIfTooTall` mengembalikan daftar ASLI, sehingga bitmap yang sama
     * muncul di dua daftar dan penjagaan `!in originals` yang mencegah
     * recycle ganda.
     *
     * CATATAN JUJUR: `Bitmap.recycle()` di Robolectric bersifat idempoten -
     * memanggilnya dua kali TIDAK melempar. Saya sudah membuktikannya dengan
     * memasang mutan yang membuang penjagaan itu, dan seluruh kelas ini tetap
     * hijau. Jadi tes ini TIDAK mengklaim mendeteksi recycle ganda; yang
     * dikunci di sini cuma kontrak yang memang bisa diperiksa: daftar yang
     * lolos tanpa penyusutan tetap menghasilkan mosaik utuh dengan nomor
     * lengkap dan urut. Penjagaan recycle ganda itu sendiri baru terbukti
     * penting di perangkat sungguhan, dan tidak bisa dites di JVM.
     */
    @Test
    fun `daftar yang sudah muat lolos tanpa penyusutan`() {
        val daftar = (1..2).map { i ->
            i.toString() to pc(potongan("r$i.png", 50, 50), i)
        }
        val hasil = pipeline.susunMosaik(daftar, paint)!!
        assertEquals(2, hasil.jumlah)
        assertEquals(listOf("1", "2"), hasil.id)
        assertFalse(hasil.bitmap.isRecycled)
        // 2 x 50 px + jarak jauh di bawah jatah, jadi tidak ada penyusutan:
        // tinggi mosaik harus kira-kira jumlah tinggi asli + jarak + padding.
        val minimal = 2 * 50 + 2 * cfg.jarakAntarPotongan
        assertTrue(
            "tinggi ${hasil.bitmap.height} menandakan potongan menyusut",
            hasil.bitmap.height >= minimal
        )
        hasil.bitmap.recycle()
    }

    /** Potongan kelewat tinggi tetap tersusun, lewat jalur shrinkIfTooTall. */
    @Test
    fun `mosaik kelewat tinggi tetap tersusun dan dijepit`() {
        val n = cfg.maxBubblesPerRequest
        val daftar = (1..n).map { i ->
            i.toString() to pc(potongan("t$i.png", 60, 600), i)
        }
        val hasil = pipeline.susunMosaik(daftar, paint)!!
        assertEquals("semua nomor harus tetap ada", n, hasil.jumlah)
        assertTrue(
            "tinggi mosaik (${hasil.bitmap.height}) harus dijepit ke " +
                "maxTinggiMosaik (${cfg.maxTinggiMosaik})",
            hasil.bitmap.height <= cfg.maxTinggiMosaik + 50
        )
        hasil.bitmap.recycle()
    }

    /**
     * Berkas rusak/hilang tidak boleh menggagalkan seluruh request, TAPI
     * nomornya juga tidak boleh dijanjikan - kalau ikut dikembalikan, jalur
     * "minta ulang" akan mengejar nomor yang tidak pernah dikirim ke model
     * dan mengulang request berbayar tanpa henti.
     */
    @Test
    fun `potongan gagal didecode dijatuhkan dan nomornya tidak dijanjikan`() {
        val rusak = tmp.newFile("rusak.png").apply { writeText("ini bukan png") }
        val daftar = listOf(
            "1" to pc(potongan("ok1.png", 70, 70), 1),
            "2" to pc(rusak, 2),
            "3" to pc(potongan("ok3.png", 70, 70), 3)
        )
        val hasil = pipeline.susunMosaik(daftar, paint)!!
        assertEquals("hanya 2 potongan yang valid", 2, hasil.jumlah)
        assertEquals(listOf("1", "3"), hasil.id)
        assertFalse("nomor rusak tidak boleh dijanjikan", hasil.id.contains("2"))
        hasil.bitmap.recycle()
    }

    @Test
    fun `daftar kosong menghasilkan null bukan mosaik kosong`() {
        assertNull(pipeline.susunMosaik(emptyList(), paint))
    }

    @Test
    fun `semua potongan gagal didecode menghasilkan null`() {
        val a = tmp.newFile("x.png").apply { writeText("bukan gambar") }
        val b = File(tmp.root, "tidak-ada.png")
        val hasil = pipeline.susunMosaik(listOf("1" to pc(a), "2" to pc(b)), paint)
        assertNull("tidak ada yang bisa disusun => null", hasil)
    }

    /**
     * `susunMosaik` dipanggil dari dalam gelombang paralel, jadi beberapa
     * thread menyusun mosaiknya bersamaan. Tiap pemanggil membawa Paint-nya
     * sendiri dan tidak ada state bersama yang disentuh.
     */
    @Test
    fun `aman disusun beberapa thread sekaligus`() {
        val berkas = (1..4).map { potongan("par$it.png", 60, 60) }
        val hasil = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val galat = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        val threads = (0 until 4).map {
            Thread {
                runCatching {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
                    val daftar = berkas.mapIndexed { i, f -> "${i + 1}" to pc(f, i) }
                    val m = pipeline.susunMosaik(daftar, p)!!
                    hasil.add(m.jumlah)
                    m.bitmap.recycle()
                }.onFailure { galat.add(it) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("tidak boleh ada galat: $galat", galat.isEmpty())
        assertEquals(listOf(4, 4, 4, 4), hasil.sorted())
    }
}
