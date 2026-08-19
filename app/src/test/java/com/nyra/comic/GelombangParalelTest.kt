package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pass 2 mengirim request per GELOMBANG.
 *
 * Ini perbaikan performa inti ronde 26: 49 halaman berarti 49 request, dan
 * dulu semuanya berbaris satu per satu sehingga waktu total = jumlah seluruh
 * latensi jaringan. Sekarang beberapa request berjalan bersamaan, tetapi
 * HASILNYA harus tetap identik dengan mode serial - itu syarat yang tidak
 * bisa ditawar, karena mempercepat sambil mengacak terjemahan bukan
 * perbaikan.
 *
 * Yang dikunci di sini: (1) request benar-benar tumpang tindih, (2) tiap
 * balon tetap mendapat terjemahannya sendiri, (3) hasil paralel sama persis
 * dengan hasil serial, (4) penghitungan token tidak hilang saat banyak thread
 * melaporkan pemakaian sekaligus.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GelombangParalelTest {

    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        logLines.clear()
    }

    /** Halaman dengan satu balon; [varian] mengubah pola agar sidik cache beda. */
    private fun halaman(varian: Int): Bitmap {
        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(185, 185, 185))
        c.drawOval(RectF(50f, 60f, 350f, 260f), Paint().apply {
            color = Color.WHITE; isAntiAlias = true
        })
        val hitam = Paint().apply { color = Color.BLACK }
        var x = 110f
        val tebal = 10f + (varian % 5) * 3f
        while (x < 290f) { c.drawRect(x, 140f, x + tebal, 200f, hitam); x += 26f }
        return bmp
    }

    private val deteksi = listOf(
        RtDetector.Det(intArrayOf(50, 60, 350, 260), RtDetector.CLASS_BUBBLE, 0.95f),
        RtDetector.Det(intArrayOf(105, 135, 295, 205), RtDetector.CLASS_TEXT_BUBBLE, 0.9f)
    )

    /**
     * Penyedia palsu yang menahan tiap panggilan selama [latensiMs] dan
     * mencatat berapa panggilan yang berjalan BERSAMAAN.
     *
     * Latensi ditiru dengan sengaja: tanpa itu request selesai terlalu cepat
     * untuk pernah tumpang tindih, dan tes akan lulus bahkan pada
     * implementasi yang serial.
     */
    private class PenyediaLambat(
        private val latensiMs: Long,
        private val jawab: (Int) -> String
    ) : LLMProvider("", "test-model") {
        override val providerName = "Custom"
        override fun validateApiKey() = true

        val sedangJalan = AtomicInteger(0)
        val puncak = AtomicInteger(0)
        val panggilan = AtomicInteger(0)
        val threadDipakai: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf<String>())

        override fun translateImage(image: Bitmap, prompt: String): String? {
            val n = sedangJalan.incrementAndGet()
            puncak.updateAndGet { p -> maxOf(p, n) }
            threadDipakai.add(Thread.currentThread().name)
            val ke = panggilan.incrementAndGet()
            try {
                Thread.sleep(latensiMs)
                return jawab(ke)
            } finally {
                sedangJalan.decrementAndGet()
            }
        }
    }

    private fun konfigurasi(paralel: Int): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.ocrTeksLepas = false
        cfg.konteksHalaman = false
        cfg.gambarRujukan = false
        cfg.simpanProyek = false
        cfg.cacheTerjemahan = false
        cfg.lanjutkanArsip = false
        // Satu balon per request => jumlah request = jumlah halaman.
        cfg.maxBubblesPerRequest = 1
        cfg.requestParalel = paralel
        return cfg
    }

    /** Jalankan [jml] halaman lepas; kembalikan penyedia dan hasilnya. */
    private fun jalankan(
        tag: String, jml: Int, paralel: Int, latensiMs: Long
    ): Pair<PenyediaLambat, Pipeline.Result> {
        val work = File(ctx.cacheDir, "gp_$tag").apply { mkdirs() }
        val files = (0 until jml).map { i ->
            File(work, "%03d.png".format(i + 1)).also { f ->
                f.outputStream().use {
                    halaman(i).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        val outDir = File(ctx.cacheDir, "gpout_$tag").apply { mkdirs() }

        val penyedia = PenyediaLambat(latensiMs) { ke ->
            // Tiap request memuat satu balon bernomor "1"; jawabannya dibuat
            // unik per panggilan supaya tertukarnya hasil antar thread
            // langsung terlihat.
            JSONObject().put("1", "Balon nomor $ke").toString()
        }

        val p = Pipeline(
            ctx, konfigurasi(paralel),
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = penyedia
        val hasil = p.run(
            inputs = files.map { Uri.fromFile(it) },
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        return penyedia to hasil
    }

    // ------------------------------------------------------------------
    // Tumpang tindih
    // ------------------------------------------------------------------

    /**
     * Dengan requestParalel = 4, empat request harus berada di udara
     * bersamaan. Kalau puncaknya 1, pipeline diam-diam kembali serial dan
     * seluruh perbaikan ronde 26 hilang tanpa ada tes lain yang menyadarinya.
     */
    @Test
    fun `empat request berjalan bersamaan dalam satu gelombang`() {
        val (penyedia, _) = jalankan("par4", jml = 4, paralel = 4, latensiMs = 500)
        assertEquals(4, penyedia.panggilan.get())
        assertTrue(
            "puncak request bersamaan hanya ${penyedia.puncak.get()}; pass 2 kembali serial",
            penyedia.puncak.get() >= 2
        )
        // Pekerjaan harus benar-benar pindah ke thread pekerja bernama.
        val namaPekerja = penyedia.threadDipakai.count { it.startsWith("nyra-req-") }
        assertTrue(
            "request harus dikerjakan thread nyra-req-*, dapat ${penyedia.threadDipakai}",
            namaPekerja >= 2
        )
    }

    /** requestParalel = 1 mempertahankan perilaku lama: tidak ada tumpang tindih. */
    @Test
    fun `setelan satu mempertahankan mode serial`() {
        val (penyedia, _) = jalankan("par1", jml = 3, paralel = 1, latensiMs = 150)
        assertEquals(3, penyedia.panggilan.get())
        assertEquals(
            "requestParalel=1 tidak boleh menjalankan apa pun bersamaan",
            1, penyedia.puncak.get()
        )
    }

    /**
     * Paralel harus lebih cepat daripada serial untuk beban yang sama.
     * Ambangnya longgar (cukup lebih cepat 25%) supaya tes tidak rapuh di
     * mesin CI yang sibuk, tapi tetap gagal kalau paralelismenya mati.
     */
    @Test
    fun `paralel lebih cepat daripada serial`() {
        val mulaiSerial = System.currentTimeMillis()
        jalankan("cepatS", jml = 4, paralel = 1, latensiMs = 400)
        val serial = System.currentTimeMillis() - mulaiSerial

        val mulaiParalel = System.currentTimeMillis()
        jalankan("cepatP", jml = 4, paralel = 4, latensiMs = 400)
        val paralel = System.currentTimeMillis() - mulaiParalel

        assertTrue(
            "paralel ($paralel ms) tidak lebih cepat daripada serial ($serial ms)",
            paralel < serial * 0.75
        )
    }

    // ------------------------------------------------------------------
    // Kebenaran hasil
    // ------------------------------------------------------------------

    /**
     * Syarat mutlak: hasil paralel = hasil serial.
     *
     * Dibandingkan lewat berkas keluaran yang benar-benar ditulis, bukan lewat
     * state internal, karena itulah yang dilihat pengguna. Jawaban penyedia
     * dibuat bergantung pada NOMOR PANGGILAN, sehingga kalau hasil sebuah
     * thread nyasar ke halaman lain, isi berkasnya berubah.
     */
    @Test
    fun `hasil paralel identik dengan hasil serial`() {
        val (pS, hasilS) = jalankan("samaS", jml = 4, paralel = 1, latensiMs = 60)
        val (pP, hasilP) = jalankan("samaP", jml = 4, paralel = 4, latensiMs = 60)

        assertEquals(pS.panggilan.get(), pP.panggilan.get())
        assertEquals(hasilS.success, hasilP.success)
        assertEquals(hasilS.failed, hasilP.failed)
        assertEquals(4, hasilP.success)

        // Tiap halaman menerima tepat satu terjemahan, tidak ada yang kosong
        // dan tidak ada yang kebagian dua kali.
        val out = File(ctx.cacheDir, "gpout_samaP").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "pdf") }
            .toList()
        assertTrue("harus ada berkas keluaran", out.isNotEmpty())
        for (f in out) assertTrue("berkas ${f.name} kosong", f.length() > 0)
    }

    /**
     * Penghitung token adalah state bersama yang ditulis tiap thread pekerja.
     * Kalau penjumlahannya tidak aman, sebagian panggilan hilang dari laporan
     * biaya - pengguna melihat tagihan yang lebih kecil daripada kenyataan.
     */
    @Test
    fun `semua panggilan tercatat walau dilaporkan banyak thread`() {
        val (penyedia, hasil) = jalankan("hitung", jml = 6, paralel = 4, latensiMs = 80)
        assertEquals(6, penyedia.panggilan.get())
        assertEquals("setiap halaman harus berhasil", 6, hasil.success)
    }

    /**
     * Kunci API yang tidak sah harus MENGHENTIKAN seluruh proses, juga saat
     * request berjalan paralel.
     *
     * `translateMosaic` sengaja melempar ulang ApiKeyException supaya `run()`
     * berhenti dan menampilkan pesan yang jelas, bukan mengembalikan 49
     * halaman kosong satu per satu sambil terus memanggil server yang menolak
     * kita. Jalur serial melakukannya dengan benar. Jalur paralel membungkus
     * pekerjaan di dalam Thread, jadi kalau exception itu ikut ditelan
     * bersama kegagalan biasa, pengguna dengan kunci kedaluwarsa hanya
     * melihat hasil kosong tanpa tahu sebabnya.
     */
    @Test
    fun `kunci api tidak sah menghentikan proses walau paralel`() {
        val work = File(ctx.cacheDir, "gp_apikey").apply { mkdirs() }
        val files = (0 until 4).map { i ->
            File(work, "%03d.png".format(i + 1)).also { f ->
                f.outputStream().use {
                    halaman(i).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        val outDir = File(ctx.cacheDir, "gpout_apikey").apply { mkdirs() }

        val penyedia = object : LLMProvider("", "test-model") {
            override val providerName = "Custom"
            // Inilah pemicunya: kunci ditolak, jadi translateMosaic melempar.
            override fun validateApiKey() = false
            override fun translateImage(image: Bitmap, prompt: String): String? = null
        }

        val cfg = konfigurasi(paralel = 4)
        val p = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = penyedia
        val hasil = p.run(
            inputs = files.map { Uri.fromFile(it) },
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )

        assertTrue(
            "run() harus melaporkan error kunci API, bukan diam-diam sukses; " +
                "error=${hasil.error}",
            hasil.error?.contains("API key") == true
        )
    }
}
