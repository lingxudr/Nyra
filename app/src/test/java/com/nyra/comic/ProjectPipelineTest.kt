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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Proses sungguhan harus meninggalkan proyek yang benar-benar bisa disunting.
 *
 * Janji fitur ini sangat spesifik: setelah satu bab diterjemahkan, mengubah
 * satu balon TIDAK boleh memerlukan deteksi ulang maupun panggilan LLM lagi.
 * Karena itu yang diuji di sini bukan sekadar "berkas JSON tercipta", tapi
 * bahwa menggambar ulang dari proyek benar-benar menghasilkan piksel — dengan
 * detektor dan server terjemahan yang sudah DIMATIKAN.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
// Tanpa NATIVE, Canvas di Robolectric adalah operasi kosong: setiap
// perbandingan piksel akan lulus dengan beda=0 walau tidak ada yang digambar.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectPipelineTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    private val kotak1 = intArrayOf(80, 80, 380, 260)
    private val kotak2 = intArrayOf(500, 600, 800, 800)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Project.rootDir(ctx).deleteRecursively()
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun buatHalaman(file: File) {
        val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK }
        for (b in listOf(kotak1, kotak2)) {
            c.drawRect(
                (b[0] + 15).toFloat(), (b[1] + 15).toFloat(),
                (b[2] - 15).toFloat(), (b[3] - 15).toFloat(), p
            )
        }
        Storage.savePng(bmp, file)
        bmp.recycle()
    }

    private fun cfgUji(): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = false
        cfg.ocrTeksLepas = false
        cfg.inpaintLama = false
        cfg.simpanProyek = true
        cfg.maxBubblesPerRequest = 20
        return cfg
    }

    private fun jalankan(cfg: Config, halaman: File): Pipeline {
        val o = JSONObject().put("1", "Balon satu").put("2", "Balon dua")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""

        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        pipeline.detectorOverride = { _: Bitmap -> listOf(kotak1, kotak2) }
        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        val outDir = File(ctx.cacheDir, "outprj").apply { mkdirs() }
        pipeline.run(
            inputs = listOf(Uri.fromFile(halaman)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        return pipeline
    }

    @Test
    fun runProducesReEditableProject() {
        val work = File(ctx.cacheDir, "prjrun").apply { deleteRecursively(); mkdirs() }
        val hal = File(work, "bab1.png")
        buatHalaman(hal)

        val cfg = cfgUji()
        val pipeline = jalankan(cfg, hal)
        pipeline.close()

        val log = logLines.joinToString("\n")

        // ---- proyek benar-benar tersimpan ----
        val daftar = Project.list(ctx)
        assertEquals("satu proyek harus tercipta\n$log", 1, daftar.size)
        val prj = daftar[0]
        assertEquals("bab1.png", prj.name)
        assertEquals("indonesian", prj.targetLanguage)
        assertEquals(1, prj.pages.size)

        val page = prj.pages[0]
        assertEquals("kotak deteksi harus ikut tersimpan", 2, page.boxes.size)
        assertEquals(kotak1.toList(), page.boxes[0].toList())
        assertEquals("Balon satu", page.translations["1"])
        assertEquals("Balon dua", page.translations["2"])

        // Salinan halaman BERSIH harus ada — tanpa itu tidak ada yang bisa
        // digambari ulang setelah cache kerja dihapus.
        val src = prj.pageFile(ctx, page)
        assertTrue("salinan halaman harus tersimpan: ${src.absolutePath}", src.exists())
        assertTrue(src.length() > 100)

        // ---- gambar ulang TANPA detektor dan TANPA jaringan ----
        server.stop()   // membuktikan tidak ada panggilan LLM lagi
        val editor = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        // detectorOverride sengaja TIDAK dipasang: kalau renderProjectPage
        // diam-diam mendeteksi lagi, YoloDetector akan gagal memuat ONNX.
        val hasil = editor.renderProjectPage(prj, page)
        assertNotNull("halaman harus bisa digambar ulang dari proyek", hasil)
        assertEquals(900, hasil!!.width)
        editor.close()
        hasil.recycle()
    }

    @Test
    fun editedTextSurvivesReloadAndChangesThePixels() {
        val work = File(ctx.cacheDir, "prjedit").apply { deleteRecursively(); mkdirs() }
        val hal = File(work, "bab2.png")
        buatHalaman(hal)

        val cfg = cfgUji()
        jalankan(cfg, hal).close()

        val prj = Project.list(ctx).first()
        val page = prj.pages[0]

        val editor = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        val sebelum = editor.renderProjectPage(prj, page)!!
        val salinanSebelum = sebelum.copy(Bitmap.Config.ARGB_8888, false)
        sebelum.recycle()

        // Pengguna membetulkan satu balon.
        page.translations["1"] = "TEKS SUDAH DIPERBAIKI"
        prj.save(ctx)

        // Dimuat ulang dari disk, seperti membuka lagi aplikasi.
        val dimuat = Project.load(ctx, prj.id)!!
        assertEquals("TEKS SUDAH DIPERBAIKI", dimuat.pages[0].translations["1"])

        val sesudah = editor.renderProjectPage(dimuat, dimuat.pages[0])!!

        // Piksel di dalam balon 1 harus berubah; kalau tidak, penyuntingan
        // hanya mengubah JSON tanpa pernah sampai ke gambar.
        var beda = 0
        var y = kotak1[1]
        while (y < kotak1[3]) {
            var x = kotak1[0]
            while (x < kotak1[2]) {
                if (salinanSebelum.getPixel(x, y) != sesudah.getPixel(x, y)) beda++
                x += 3
            }
            y += 3
        }
        assertTrue("teks baru harus mengubah piksel balon (beda=$beda)", beda > 50)

        salinanSebelum.recycle(); sesudah.recycle()
        editor.close()
    }

    @Test
    fun reRenderIsIdempotentSoEditsDoNotStack() {
        // Menggambar dari salinan BERSIH, bukan dari hasil gambar sebelumnya.
        // Kalau salah, teks lama akan tertinggal di bawah teks baru dan balon
        // berubah jadi tumpukan huruf setelah beberapa kali suntingan.
        val work = File(ctx.cacheDir, "prjidem").apply { deleteRecursively(); mkdirs() }
        val hal = File(work, "bab3.png")
        buatHalaman(hal)

        val cfg = cfgUji()
        jalankan(cfg, hal).close()

        val prj = Project.list(ctx).first()
        val page = prj.pages[0]
        val editor = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })

        val a = editor.renderProjectPage(prj, page)!!
        val salinanA = a.copy(Bitmap.Config.ARGB_8888, false)
        a.recycle()
        val b = editor.renderProjectPage(prj, page)!!

        var beda = 0
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                if (salinanA.getPixel(x, y) != b.getPixel(x, y)) beda++
                x += 7
            }
            y += 7
        }
        assertEquals("dua kali render dengan teks sama harus identik", 0, beda)

        salinanA.recycle(); b.recycle()
        editor.close()
    }

    @Test
    fun projectRecordingCanBeTurnedOff() {
        val work = File(ctx.cacheDir, "prjoff").apply { deleteRecursively(); mkdirs() }
        val hal = File(work, "bab4.png")
        buatHalaman(hal)

        val cfg = cfgUji()
        cfg.simpanProyek = false
        jalankan(cfg, hal).close()

        assertEquals("tidak boleh ada proyek saat sakelar mati", 0, Project.list(ctx).size)
    }
}
