package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import java.io.File

/**
 * Kotak yang penata teks PASTI tolak tidak boleh dibayar ke provider.
 *
 * Ronde 28 — audit "masih ada yang tidak ketranslate" menemukan kebocoran
 * biaya yang diam. Alurnya dulu begini:
 *
 *  1. pass 1 memotong SETIAP kotak hasil deteksi, termasuk yang bentuknya
 *     jelas bukan balon (panel memanjang, SFX selebar halaman);
 *  2. potongan itu ikut masuk mosaik dan dikirim ke model — token terpakai,
 *     kuota terpakai, waktu tunggu bertambah;
 *  3. di pass 3 drawText memanggil bentukDitolak() dan mengembalikan false,
 *     jadi teksnya TIDAK PERNAH digambar;
 *  4. tidak ada satu baris log pun tentang langkah 3.
 *
 * Hasilnya: pengguna melihat "N balon diterjemahkan", tagihan naik, tapi
 * halamannya masih berbahasa asli di bagian itu, tanpa penjelasan apa pun.
 *
 * Pada berkas oracle repo ini 61 dari 480 kotak (12,7%) masuk golongan itu.
 *
 * Sekarang saringannya dipindah ke DEPAN: kotak yang akan ditolak penata teks
 * tidak pernah jadi potongan, tidak pernah masuk mosaik, dan jumlahnya
 * dilaporkan. Tes ini mengunci ketiga hal itu sekaligus.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class BentukDitolakTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    /** Balon normal: 300x200, rasio 1,5 — harus lolos. */
    private val kotakBalon = intArrayOf(100, 100, 400, 300)

    /**
     * Spanduk 900x80 pada halaman selebar 1080: rasio 11,25 dan lebarnya
     * 83% lebar halaman. Kena aturan pertama bentukDitolak
     * (ratio >= 3.2 && w >= imgW * 0.35).
     *
     * Bentuk ini sengaja dipilih supaya LOLOS saringan teks lepas:
     * TextRegionMath.dropNoise cuma menolak yang lebarnya >= 90% halaman,
     * dan 83% masih di bawahnya. Itulah celah yang sebenarnya — kotak balon
     * lewat BoxUtils.dropAbsurd (yang memakai aturan sama dengan penggambar),
     * tapi kotak TEKS LEPAS ditambahkan SESUDAH dropAbsurd dan tidak pernah
     * disaring bentuknya.
     */
    private val kotakSpanduk = intArrayOf(90, 700, 990, 780)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun buatHalaman(file: File, kotak: List<IntArray>) {
        val bmp = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK }
        for (b in kotak) {
            c.drawRect(
                (b[0] + 20).toFloat(), (b[1] + 20).toFloat(),
                (b[2] - 20).toFloat(), (b[3] - 20).toFloat(), p
            )
        }
        Storage.savePng(bmp, file)
        bmp.recycle()
    }

    /** Provider palsu yang mencatat berapa nomor yang benar-benar diminta. */
    private fun balasSemua() {
        val o = JSONObject()
        for (i in 1..20) o.put(i.toString(), "Teks $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    private fun pipelineSiap(cfg: Config): Pipeline {
        val p = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        p.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        return p
    }

    private fun cfgDasar(): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.warnaOtomatis = false
        cfg.detektorRtdetr = false
        cfg.ocrTeksLepas = true
        cfg.inpaintLama = false
        cfg.cacheTerjemahan = false
        cfg.konteksHalaman = false
        cfg.gambarRujukan = false
        cfg.maxBubblesPerRequest = 20
        cfg.requestParalel = 1
        return cfg
    }

    /**
     * Inti perbaikan: kotak berbentuk spanduk tidak boleh ikut dipotong.
     *
     * Diukur dari jumlah balon yang dilaporkan pipeline sebagai "extracted",
     * bukan dari isi gambar: yang sedang diuji adalah keputusan MENGIRIM atau
     * TIDAK, dan itulah yang menentukan biaya.
     */
    @Test
    fun kotakSpandukTidakPernahDipotongDanTidakDikirim() {
        val work = File(ctx.cacheDir, "bentuktolak1").apply { deleteRecursively(); mkdirs() }
        val hal = File(work, "hal.png")
        buatHalaman(hal, listOf(kotakBalon, kotakSpanduk))

        balasSemua()
        val pipeline = pipelineSiap(cfgDasar())
        // Balon normal dari detektor balon; spanduk datang sebagai TEKS LEPAS,
        // persis seperti narasi/SFX selebar panel di halaman sungguhan.
        pipeline.detectorOverride = { _: Bitmap -> listOf(kotakBalon) }
        pipeline.textDetectorOverride = { _: Bitmap -> listOf(kotakSpanduk) }

        val outDir = File(ctx.cacheDir, "bentuktolak1out").apply { mkdirs() }
        val result = pipeline.run(
            inputs = listOf(Uri.fromFile(hal)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        pipeline.close()

        val log = logLines.joinToString("\n")
        assertEquals("pipeline melaporkan galat:\n$log", null, result.error)

        // Prasyarat: spanduk memang lolos saringan teks lepas dan sampai ke
        // tahap potong. Tanpa ini tes bisa hijau tanpa menguji apa pun.
        assertTrue(
            "fixture harus menghasilkan teks lepas\n$log",
            log.contains("teks di luar balon ikut diterjemahkan")
        )

        // Hanya SATU kotak yang benar-benar diekstrak dan dibayar.
        assertTrue(
            "hanya balon normal yang boleh diekstrak\n$log",
            log.contains("Extracted 1 speech bubbles")
        )
        assertTrue(
            "jumlah kotak yang dilewati harus dilaporkan\n$log",
            log.contains("1 kotak dilewati sebelum dikirim")
        )
    }

    /** Halaman tanpa kotak aneh tidak boleh kehilangan apa pun. */
    @Test
    fun balonNormalTidakIkutTersaring() {
        val work = File(ctx.cacheDir, "bentuktolak2").apply { deleteRecursively(); mkdirs() }
        val hal = File(work, "hal.png")
        val kotakLain = intArrayOf(500, 900, 800, 1150)
        buatHalaman(hal, listOf(kotakBalon, kotakLain))

        balasSemua()
        val pipeline = pipelineSiap(cfgDasar())
        pipeline.detectorOverride = { _: Bitmap -> listOf(kotakBalon, kotakLain) }
        pipeline.textDetectorOverride = { _: Bitmap -> emptyList() }

        val outDir = File(ctx.cacheDir, "bentuktolak2out").apply { mkdirs() }
        pipeline.run(
            inputs = listOf(Uri.fromFile(hal)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        pipeline.close()

        val log = logLines.joinToString("\n")
        assertTrue(
            "dua balon wajar harus dikirim semua\n$log",
            log.contains("Extracted 2 speech bubbles")
        )
        assertFalse(
            "tidak boleh ada laporan kotak dilewati\n$log",
            log.contains("kotak dilewati sebelum dikirim")
        )
    }

    /**
     * Aturan penyaring pass 1 harus SAMA PERSIS dengan aturan drawText.
     *
     * Kalau keduanya berbeda sedikit saja, kebocoran lama kembali: kotak yang
     * lolos saringan tapi ditolak penggambar tetap jadi token terbuang.
     * Diperiksa langsung pada bentukDitolak lewat berbagai bentuk.
     */
    @Test
    fun aturanPenyaringSamaDenganAturanPenggambar() {
        val cfg = cfgDasar()
        val pipeline = pipelineSiap(cfg)
        val m = Pipeline::class.java.getDeclaredMethod(
            "bentukDitolak", IntArray::class.java, Int::class.java, Int::class.java
        ).apply { isAccessible = true }

        fun tolak(b: IntArray) = m.invoke(pipeline, b, 1080, 1500) as Boolean

        // Balon wajar — lolos.
        assertFalse("balon 300x200 harus lolos", tolak(kotakBalon))
        assertFalse("balon tinggi harus lolos", tolak(intArrayOf(100, 100, 300, 500)))
        assertFalse("balon kecil harus lolos", tolak(intArrayOf(10, 10, 90, 70)))

        // Aturan 1: rasio >= 3,2 DAN lebar >= 35% halaman.
        assertTrue("spanduk selebar halaman harus ditolak", tolak(kotakSpanduk))
        // Rasionya ekstrem tapi sempit: aturan 1 tidak kena karena lebarnya
        // cuma 200 px (<378), dan aturan 2 tidak kena karena areanya kecil.
        assertFalse("garis pendek memanjang harus lolos", tolak(intArrayOf(0, 0, 200, 40)))

        // Aturan 2: area >= 3,5% halaman DAN rasio >= 2,8.
        // 700x250 = 175.000 px = 10,8% halaman, rasio 2,8.
        assertTrue("blok lebar berarea besar harus ditolak", tolak(intArrayOf(0, 0, 700, 250)))

        pipeline.close()
    }
}
