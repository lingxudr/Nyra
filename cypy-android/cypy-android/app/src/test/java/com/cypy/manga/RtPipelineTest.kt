package com.cypy.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Jalur deteksi RT-DETR di dalam pipeline sungguhan, memakai seam
 * rtDetectorOverride yang memutar ulang keluaran tiga-kelas.
 *
 * Kotak yang diputar ulang di sini BUKAN karangan: bentuknya meniru keluaran
 * nyata det_s_int8.onnx atas halaman bukti pengguna (balon hitam berskor 0.97
 * dengan text_bubble 0.89 di dalamnya, plus satu text_free "EFFRONTE" 0.80).
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RtPipelineTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

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
        for (i in 1..n) o.put(i.toString(), "Terjemahan $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody =
            """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    /** Halaman uji: satu balon putih, satu balon hitam, satu teks lepas. */
    private fun halamanUji(): Bitmap {
        val bmp = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(180, 180, 180))
        c.drawOval(RectF(60f, 80f, 420f, 320f), Paint().apply {
            color = Color.WHITE; isAntiAlias = true
        })
        c.drawOval(RectF(380f, 500f, 740f, 760f), Paint().apply {
            color = Color.rgb(8, 8, 8); isAntiAlias = true
        })
        val hitam = Paint().apply { color = Color.BLACK }
        val putih = Paint().apply { color = Color.WHITE }
        var x = 140f
        while (x < 340f) { c.drawRect(x, 170f, x + 14f, 240f, hitam); x += 30f }
        x = 460f
        while (x < 660f) { c.drawRect(x, 590f, x + 14f, 660f, putih); x += 30f }
        // teks lepas di luar balon
        x = 100f
        while (x < 300f) { c.drawRect(x, 950f, x + 14f, 1010f, hitam); x += 30f }
        return bmp
    }

    private fun pngDi(dir: File, nama: String, bmp: Bitmap): File {
        val f = File(dir, nama)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun det(cls: Int, score: Float, x1: Int, y1: Int, x2: Int, y2: Int) =
        RtDetector.Det(intArrayOf(x1, y1, x2, y2), cls, score)

    private fun pipa(cfg: Config): Pipeline = Pipeline(
        ctx, cfg,
        log = { synchronized(logLines) { logLines.add(it) } },
        progress = { _, _ -> }
    )

    private fun konfigurasi(): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.warnaOtomatis = true
        cfg.ocrTeksLepas = true
        return cfg
    }

    /**
     * Jumlah kotak yang benar-benar tergambar, dibaca dari log pipeline
     * ("Rendered <nama> (N bubble(s) translated)").
     */
    private fun jumlahTergambar(): Int {
        val re = Regex("\\((\\d+) bubble")
        return logLines.sumOf { l -> re.find(l)?.groupValues?.get(1)?.toInt() ?: 0 }
    }

    private fun provider() = OpenAICompatProvider(
        apiKey = "", modelName = "test-model",
        baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
        providerName = "Custom", jsonMode = false, requiresKey = false
    )

    /** Keluaran tiga-kelas untuk halamanUji(). */
    private val deteksi = listOf(
        det(RtDetector.CLASS_BUBBLE, 0.96f, 60, 80, 420, 320),
        det(RtDetector.CLASS_BUBBLE, 0.97f, 380, 500, 740, 760),
        det(RtDetector.CLASS_TEXT_BUBBLE, 0.91f, 135, 165, 345, 245),
        det(RtDetector.CLASS_TEXT_BUBBLE, 0.89f, 455, 585, 665, 665),
        det(RtDetector.CLASS_TEXT_FREE, 0.80f, 95, 945, 305, 1015)
    )

    @Test
    fun rtdetrMenerjemahkanBalonDanTeksLepasTanpaOcrTerpisah() {
        val work = File(ctx.cacheDir, "rt1").apply { mkdirs() }
        pngDi(work, "page.png", halamanUji())
        val outDir = File(ctx.cacheDir, "rtout1").apply { mkdirs() }

        balasan(8)
        val cfg = konfigurasi()
        val p = pipa(cfg)
        p.rtDetectorOverride = { deteksi }
        // Kalau jalur RT-DETR benar-benar dipakai, detektor teks lama tidak
        // boleh dipanggil sama sekali.
        var ocrDipanggil = 0
        p.textDetectorOverride = { ocrDipanggil++; emptyList() }
        p.providerOverride = provider()

        val r = p.run(
            inputs = listOf(Uri.fromFile(File(work, "page.png"))),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()

        assertEquals("tidak boleh ada halaman gagal", 0, r.failed)
        assertTrue("harus ada keluaran", r.outputs.isNotEmpty())
        assertEquals("OCR PP-OCRv5 tidak boleh dipakai lagi", 0, ocrDipanggil)
        // 2 balon + 1 teks lepas
        assertEquals(3, jumlahTergambar())
        assertTrue(
            "log harus melaporkan teks luar balon",
            logLines.any { it.contains("teks di luar balon") }
        )
        assertTrue(
            "log harus melaporkan balon gelap dipertahankan",
            logLines.any { it.contains("balon gelap") }
        )
    }

    @Test
    fun balonGelapTidakDicatPutih() {
        val work = File(ctx.cacheDir, "rt2").apply { mkdirs() }
        pngDi(work, "page.png", halamanUji())
        val outDir = File(ctx.cacheDir, "rtout2").apply { mkdirs() }

        balasan(8)
        val cfg = konfigurasi()
        val p = pipa(cfg)
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = provider()
        p.run(
            inputs = listOf(Uri.fromFile(File(work, "page.png"))),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()

        val keluar = outDir.walkTopDown().filter { it.extension == "png" }.firstOrNull()
        assertTrue("berkas keluaran harus ada", keluar != null && keluar.length() > 0)

        val img = BitmapFactory.decodeFile(keluar!!.absolutePath)!!
        // Titik di dalam balon gelap tetapi di luar kotak teks: harus tetap gelap.
        fun luminansi(x: Int, y: Int): Double {
            val p2 = img.getPixel(x, y)
            val r = ((p2 shr 16) and 0xFF).toDouble()
            val g = ((p2 shr 8) and 0xFF).toDouble()
            val b = (p2 and 0xFF).toDouble()
            return 0.299 * r + 0.587 * g + 0.114 * b
        }
        val diBalonGelap = luminansi(560, 530)
        assertTrue(
            "balon hitam harus tetap gelap setelah render, dapat $diBalonGelap",
            diBalonGelap < 100.0
        )
        // Balon putih harus tetap terang.
        val diBalonTerang = luminansi(240, 110)
        assertTrue(
            "balon putih harus tetap terang, dapat $diBalonTerang",
            diBalonTerang > 170.0
        )
    }

    @Test
    fun sakelarWarnaOtomatisMatiMengembalikanPerilakuLama() {
        val work = File(ctx.cacheDir, "rt3").apply { mkdirs() }
        pngDi(work, "page.png", halamanUji())
        val outDir = File(ctx.cacheDir, "rtout3").apply { mkdirs() }

        balasan(8)
        val cfg = konfigurasi()
        cfg.warnaOtomatis = false
        val p = pipa(cfg)
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = provider()
        val r = p.run(
            inputs = listOf(Uri.fromFile(File(work, "page.png"))),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        assertEquals(0, r.failed)
        assertTrue(
            "tanpa warna otomatis tidak ada laporan balon gelap",
            logLines.none { it.contains("balon gelap") }
        )
    }

    @Test
    fun detectorOverrideLamaTetapMenempuhJalurYolo() {
        val work = File(ctx.cacheDir, "rt4").apply { mkdirs() }
        pngDi(work, "page.png", halamanUji())
        val outDir = File(ctx.cacheDir, "rtout4").apply { mkdirs() }

        balasan(4)
        val cfg = konfigurasi()
        val p = pipa(cfg)
        var yoloDipanggil = 0
        // Tes-tes lama memasang seam ini; RT-DETR tidak boleh membajaknya.
        p.detectorOverride = { _: Bitmap ->
            yoloDipanggil++
            listOf(intArrayOf(60, 80, 420, 320))
        }
        p.textDetectorOverride = { emptyList() }
        p.providerOverride = provider()
        val r = p.run(
            inputs = listOf(Uri.fromFile(File(work, "page.png"))),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        assertTrue("jalur YOLO lama harus tetap dipakai", yoloDipanggil > 0)
        assertEquals(1, jumlahTergambar())
    }

    @Test
    fun teksLepasDimatikanLewatSakelar() {
        val work = File(ctx.cacheDir, "rt5").apply { mkdirs() }
        pngDi(work, "page.png", halamanUji())
        val outDir = File(ctx.cacheDir, "rtout5").apply { mkdirs() }

        balasan(8)
        val cfg = konfigurasi()
        cfg.ocrTeksLepas = false
        val p = pipa(cfg)
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = provider()
        val r = p.run(
            inputs = listOf(Uri.fromFile(File(work, "page.png"))),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        assertEquals("hanya 2 balon, tanpa teks lepas", 2, jumlahTergambar())
    }

    /**
     * Regresi ronde 16 — bug umur-warna.
     *
     * Warna dulu disimpan di satu peta milik Pipeline yang dibersihkan setiap
     * kali detectBoxes() jalan. Deteksi terjadi di pass 1 untuk SEMUA halaman,
     * penggambaran baru di pass 3, jadi saat halaman 1 digambar isi peta itu
     * sudah menjadi milik halaman terakhir. Hanya halaman terakhir yang pernah
     * mendapat warna aslinya; sisanya jatuh ke putih/hitam.
     *
     * Di sini halaman 1 memakai balon HITAM dan halaman 2 balon PUTIH. Kalau
     * bugnya kembali, teks halaman 1 akan digambar hitam di atas balon hitam
     * alias hilang. Tes memeriksa piksel keluaran halaman 1 sungguhan.
     */
    @Test
    fun warnaBertahanUntukSemuaHalamanBukanHanyaYangTerakhir() {
        val work = File(ctx.cacheDir, "rt_umur").apply { mkdirs() }
        val outDir = File(ctx.cacheDir, "rtout_umur").apply { mkdirs() }

        // Halaman 1: satu balon HITAM berteks putih.
        val h1 = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(h1).apply {
            drawColor(Color.rgb(180, 180, 180))
            drawOval(RectF(60f, 60f, 540f, 420f), Paint().apply {
                color = Color.rgb(8, 8, 8); isAntiAlias = true
            })
            val putih = Paint().apply { color = Color.WHITE }
            var x = 160f
            while (x < 440f) { drawRect(x, 200f, x + 14f, 280f, putih); x += 30f }
        }
        // Halaman 2: satu balon PUTIH berteks hitam.
        val h2 = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(h2).apply {
            drawColor(Color.rgb(180, 180, 180))
            drawOval(RectF(60f, 60f, 540f, 420f), Paint().apply {
                color = Color.WHITE; isAntiAlias = true
            })
            val hitam = Paint().apply { color = Color.BLACK }
            var x = 160f
            while (x < 440f) { drawRect(x, 200f, x + 14f, 280f, hitam); x += 30f }
        }
        pngDi(work, "a.png", h1)
        pngDi(work, "b.png", h2)

        val satuBalon = listOf(
            det(RtDetector.CLASS_BUBBLE, 0.96f, 60, 60, 540, 420),
            det(RtDetector.CLASS_TEXT_BUBBLE, 0.90f, 155, 195, 445, 285)
        )

        balasan(8)
        val cfg = konfigurasi()
        cfg.ocrTeksLepas = false
        val p = pipa(cfg)
        p.rtDetectorOverride = { satuBalon }
        p.providerOverride = provider()

        val r = p.run(
            inputs = listOf(
                Uri.fromFile(File(work, "a.png")),
                Uri.fromFile(File(work, "b.png"))
            ),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()

        assertEquals(0, r.failed)
        assertEquals("dua halaman harus keluar", 2, r.outputs.size)

        // Halaman 1 (balon hitam): harus ada piksel TERANG di area teks,
        // yaitu huruf putih. Bug lama menggambarnya hitam -> tidak ada.
        val jalur1 = r.outputs[0].localPath!!
        val keluar1 = BitmapFactory.decodeFile(jalur1)
        var terang = 0
        for (y in 195 until 285) {
            for (x in 155 until 445) {
                if (Palette.luminance(keluar1.getPixel(x, y)) > 200f) terang++
            }
        }
        assertTrue(
            "teks halaman PERTAMA harus tetap putih di balon hitam " +
                "(piksel terang=$terang) - warna tidak boleh bocor antar halaman",
            terang > 200
        )

        // Latar balon halaman 1 harus tetap gelap, bukan ditambal putih.
        assertTrue(
            "balon halaman pertama harus tetap gelap",
            Palette.luminance(keluar1.getPixel(300, 120)) < 90f
        )
    }
}
