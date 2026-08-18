package com.cypy.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Menghapus hanya boleh terjadi kalau penggantinya pasti digambar.
 *
 * Bug yang dikunci di sini datang dari screenshot pengguna: bercak buram di
 * atas topi penyihir, tanpa teks apa pun di atasnya. Penyebabnya dua daftar
 * yang tidak sinkron - inpaint menghapus SEMUA teks lepas, sementara drawText
 * menolak kotak yang terlalu lebar dan gepeng. Kotak di celah itu dihapus LaMa
 * lalu ditinggalkan kosong.
 *
 * Kotak uji di bawah meniru geometri bercak itu: rasio 3.37 dan lebar 56% dari
 * halaman, tepat melewati ambang `ratio >= 3.2 && w >= 0.35*lebar`.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InpaintSmearTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context

    /** Balon normal - harus diterjemahkan dan digambar. */
    private val kotakBalon = intArrayOf(120, 120, 420, 330)

    /** Teks lepas bentuk normal - boleh dihapus lalu digambari ulang. */
    private val kotakLepasSah = intArrayOf(120, 500, 400, 640)

    /** Teks lepas SANGAT gepeng - drawText menolaknya, jadi jangan dihapus. */
    private val kotakLepasGepeng = intArrayOf(20, 800, 525, 950)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun halaman(f: File) {
        val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK }
        for (b in listOf(kotakBalon, kotakLepasSah, kotakLepasGepeng)) {
            c.drawRect(
                (b[0] + 8).toFloat(), (b[1] + 8).toFloat(),
                (b[2] - 8).toFloat(), (b[3] - 8).toFloat(), p
            )
        }
        Storage.savePng(bmp, f)
        bmp.recycle()
    }

    @Test
    fun neverErasesABoxThatWillNotBeRedrawn() {
        val work = File(ctx.cacheDir, "smear").apply { deleteRecursively(); mkdirs() }
        val src = File(work, "hal.png")
        halaman(src)

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = false
        cfg.ocrTeksLepas = true       // wajib: penanda teks-lepas hanya diisi di jalur ini
        cfg.simpanProyek = false
        cfg.inpaintLama = true            // inti pengujian
        cfg.maxBubblesPerRequest = 20

        val o = JSONObject()
            .put("1", "Balon biasa")
            .put("2", "Teks lepas")
            .put("3", "Teks gepeng")
        server.responseCode = 200
        server.responseBody =
            """{"choices":[{"message":{"content":"${o.toString().replace("\"", "\\\"")}"}}]}"""

        val pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        pipeline.detectorOverride = { _: Bitmap -> listOf(kotakBalon) }
        // Kedua kotak terakhir ditandai sebagai teks di luar balon.
        pipeline.textDetectorOverride = { _: Bitmap -> listOf(kotakLepasSah, kotakLepasGepeng) }

        // Tangkap apa yang HENDAK dihapus, tanpa menjalankan LaMa sungguhan.
        val dihapus = mutableListOf<IntArray>()
        pipeline.inpaintOverride = { sasaran -> dihapus.addAll(sasaran); sasaran.size }

        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        val outDir = File(ctx.cacheDir, "smearout").apply { mkdirs() }
        pipeline.run(listOf(Uri.fromFile(src)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()

        val kunci = dihapus.map { "${it[0]},${it[1]},${it[2]},${it[3]}" }.toSet()

        // Kotak gepeng: drawText pasti menolaknya, jadi menghapusnya hanya
        // meninggalkan bercak buram. Inilah bug dari screenshot pengguna.
        assertTrue(
            "kotak gepeng TIDAK boleh dihapus karena tidak akan digambari ulang; " +
                "yang dihapus=$kunci",
            "20,800,525,950" !in kunci
        )

        // Teks lepas yang bentuknya wajar tetap harus dibersihkan - kalau tidak,
        // perbaikan ini diam-diam mematikan seluruh fitur inpaint.
        assertTrue(
            "teks lepas normal harus tetap dihapus; yang dihapus=$kunci",
            "120,500,400,640" in kunci
        )

        // Balon biasa bukan teks lepas: LaMa tidak boleh menyentuhnya.
        assertTrue(
            "balon di dalam bubble tidak boleh dihapus; yang dihapus=$kunci",
            "120,120,420,330" !in kunci
        )
    }

    @Test
    fun emptyOrSkipTranslationIsNeverErased() {
        // LLM mengembalikan SKIP untuk teks yang tidak perlu diterjemahkan.
        // drawText menolaknya, jadi menghapusnya berarti melenyapkan teks asli
        // dan tidak menaruh apa pun sebagai gantinya.
        val work = File(ctx.cacheDir, "smear2").apply { deleteRecursively(); mkdirs() }
        val src = File(work, "hal2.png")
        halaman(src)

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = false
        cfg.ocrTeksLepas = true       // wajib: penanda teks-lepas hanya diisi di jalur ini
        cfg.simpanProyek = false
        cfg.inpaintLama = true
        cfg.maxBubblesPerRequest = 20

        val o = JSONObject()
            .put("1", "Balon biasa")
            .put("2", "SKIP")
            .put("3", "   ")
        server.responseCode = 200
        server.responseBody =
            """{"choices":[{"message":{"content":"${o.toString().replace("\"", "\\\"")}"}}]}"""

        val pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
        pipeline.detectorOverride = { _: Bitmap -> listOf(kotakBalon) }
        pipeline.textDetectorOverride = { _: Bitmap -> listOf(kotakLepasSah, kotakLepasGepeng) }
        val dihapus = mutableListOf<IntArray>()
        pipeline.inpaintOverride = { sasaran -> dihapus.addAll(sasaran); sasaran.size }
        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        val outDir = File(ctx.cacheDir, "smearout2").apply { mkdirs() }
        pipeline.run(listOf(Uri.fromFile(src)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()

        assertEquals(
            "SKIP dan teks kosong tidak boleh menghapus apa pun; yang dihapus=" +
                dihapus.map { it.toList() },
            0, dihapus.size
        )
    }
}
