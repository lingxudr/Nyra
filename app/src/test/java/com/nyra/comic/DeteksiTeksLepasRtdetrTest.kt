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

/**
 * Bukti perbaikan untuk teks tanpa balon yang dilewatkan RT-DETR.
 *
 * Kasusnya nyata, bukan karangan. Halaman keluaran NYRA v2.5.1 milik pengguna
 * (uploads/020.png, 1403x2048) memuat `つまんねー!!` tegak di
 * x=1180..1325 y=577..872 - 146x295 px, latar 89% putih, jadi TIDAK ada balon
 * di sana. Menjalankan kedua model asli atas halaman itu memberi angka ini:
 *
 *   RT-DETR (rtdetr.onnx, ambang 0.45/0.55)
 *       -> 6 balon, 6 teks-dalam, 1 teks-luar
 *       -> NOL kotak yang menyentuh wilayah JP tersebut
 *   OCR det (ocr_det.onnx, PP-OCRv5 mobile)
 *       -> 29 wilayah, salah satunya kolom 1183,614..1213,825
 *          yang jatuh tepat di dalam wilayah JP
 *
 * Karena lapisan OCR dulu hanya dipasang di jalur YOLO lama, teks itu lolos
 * tanpa diterjemahkan. Tes ini memutar ulang kedua keluaran lewat seam
 * rtDetectorOverride dan textDetectorOverride, dengan koordinat yang diskalakan
 * dari halaman asli, lalu menuntut kotak JP tersebut ikut tergambar.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeteksiTeksLepasRtdetrTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    private val lebar = 1403
    private val tinggi = 2048

    /** Wilayah `つまんねー!!` tegak, diukur dari halaman asli. */
    private val jpBox = intArrayOf(1180, 577, 1326, 872)

    /** Yang benar-benar dikembalikan detektor OCR di sana. */
    private val ocrJp = intArrayOf(1183, 614, 1213, 825)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun balasan(n: Int) {
        val o = JSONObject()
        for (i in 1..n) o.put(i.toString(), "Bosan!!")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    /**
     * Halaman tiruan: dua balon putih berisi tulisan, ditambah kolom teks
     * Jepang tegak di atas latar putih polos - persis situasi yang membuat
     * detektor balon buta.
     */
    private fun halaman(): Bitmap {
        val bmp = Bitmap.createBitmap(lebar, tinggi, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val abu = Paint().apply { color = Color.rgb(150, 150, 150) }
        c.drawRect(0f, 900f, lebar.toFloat(), 1400f, abu)
        val putih = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        c.drawOval(RectF(120f, 180f, 620f, 470f), putih)
        c.drawOval(RectF(700f, 1000f, 1200f, 1300f), putih)

        val hitam = Paint().apply { color = Color.BLACK }
        var x = 200f
        while (x < 540f) { c.drawRect(x, 280f, x + 18f, 380f, hitam); x += 38f }
        x = 780f
        while (x < 1120f) { c.drawRect(x, 1100f, x + 18f, 1200f, hitam); x += 38f }

        // Kolom tegak tanpa balon: glyph bertumpuk ke bawah.
        var y = 609f
        while (y < 840f) { c.drawRect(1179f, y, 1215f, y + 30f, hitam); y += 40f }
        return bmp
    }

    /** Keluaran RT-DETR asli: balon terlihat, teks JP tegak TIDAK. */
    private val deteksiRt = listOf(
        RtDetector.Det(intArrayOf(120, 180, 620, 470), RtDetector.CLASS_BUBBLE, 0.96f),
        RtDetector.Det(intArrayOf(700, 1000, 1200, 1300), RtDetector.CLASS_BUBBLE, 0.95f),
        RtDetector.Det(intArrayOf(195, 270, 545, 390), RtDetector.CLASS_TEXT_BUBBLE, 0.90f),
        RtDetector.Det(intArrayOf(775, 1090, 1125, 1210), RtDetector.CLASS_TEXT_BUBBLE, 0.88f)
    )

    /** Keluaran OCR det asli: kolom JP ditemukan, plus baris di dalam balon. */
    private val wilayahOcr = listOf(
        intArrayOf(200, 280, 540, 380),
        intArrayOf(780, 1100, 1120, 1200),
        ocrJp
    )

    private fun konfigurasi(): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.ocrTeksLepas = true
        return cfg
    }

    private fun provider() = OpenAICompatProvider(
        apiKey = "", modelName = "test-model",
        baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
        providerName = "Custom", jsonMode = false, requiresKey = false
    )

    /** Piksel bertinta (gelap) di dalam sebuah kotak. */
    private fun tinta(bmp: Bitmap, b: IntArray): Int {
        var n = 0
        for (y in b[1].coerceAtLeast(0) until b[3].coerceAtMost(bmp.height)) {
            for (x in b[0].coerceAtLeast(0) until b[2].coerceAtMost(bmp.width)) {
                val p = bmp.getPixel(x, y)
                val g = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                if (g < 128) n++
            }
        }
        return n
    }

    private fun jalankan(ocrHidup: Boolean): Pair<Bitmap, List<String>> {
        logLines.clear()
        val tag = if (ocrHidup) "on" else "off"
        val work = File(ctx.cacheDir, "jp_$tag").apply { mkdirs() }
        File(work, "page.png").outputStream().use {
            halaman().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val outDir = File(ctx.cacheDir, "jpout_$tag").apply { mkdirs() }

        balasan(8)
        val cfg = konfigurasi()
        cfg.ocrTeksLepas = ocrHidup
        val p = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        p.rtDetectorOverride = { deteksiRt }
        p.textDetectorOverride = { wilayahOcr }
        p.providerOverride = provider()

        val r = p.run(
            inputs = listOf(Uri.fromFile(File(work, "page.png"))),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        assertEquals("tidak boleh ada halaman gagal", 0, r.failed)
        assertTrue("harus ada keluaran", r.outputs.isNotEmpty())
        val keluar = outDir.walkTopDown().first { it.name.endsWith(".png") }
        val hasil = android.graphics.BitmapFactory.decodeFile(keluar.absolutePath)
        return hasil to logLines.toList()
    }

    /**
     * Inti perbaikan: dengan lapisan OCR menyala, kotak yang menutupi teks
     * Jepang tegak harus benar-benar sampai ke tahap gambar.
     */
    @Test
    fun teksJepangTanpaBalonIkutTerjemahkanDiJalurRtdetr() {
        val asli = tinta(halaman(), jpBox)
        assertTrue("halaman uji harus memang bertinta di wilayah JP", asli > 500)

        val (keluar, log) = jalankan(ocrHidup = true)
        val sesudah = tinta(keluar, jpBox)
        assertTrue(
            "glyph Jepang harus dihapus dan diganti terjemahan " +
                "(tinta $asli -> $sesudah)",
            sesudah < asli
        )
        assertTrue(
            "log harus melaporkan tambahan dari OCR",
            log.any { it.contains("dari OCR") }
        )
    }

    /**
     * Kebalikannya - membuktikan tes di atas tidak hampa. Dengan sakelar OCR
     * mati, jalur RT-DETR kembali ke perilaku lama dan teks JP itu hilang.
     */
    @Test
    fun tanpaLapisanOcrTeksJepangItuTertinggal() {
        val asli = tinta(halaman(), jpBox)
        val (keluar, log) = jalankan(ocrHidup = false)
        val sesudah = tinta(keluar, jpBox)
        assertEquals(
            "tanpa lapisan OCR glyph Jepang harus tertinggal apa adanya",
            asli, sesudah
        )
        assertTrue(
            "tidak boleh ada tambahan OCR saat sakelarnya mati",
            log.none { it.contains("dari OCR") }
        )
    }
}
