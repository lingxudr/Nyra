package com.cypy.manga

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Bukti bahwa glosarium benar-benar sampai ke penyedia, bukan sekadar terparsir
 * dengan rapi di dalam unit test.
 *
 * Pemeriksaannya dilakukan atas body HTTP yang ditangkap TinyHttpServer, jadi
 * kalau suatu saat ada yang memutus rantai Config -> Pipeline -> buildPrompt,
 * tes ini gagal walaupun GlossaryTest tetap hijau.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlossaryPipelineTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
        val o = JSONObject()
        for (i in 1..8) o.put(i.toString(), "Terjemahan $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    @After
    fun tearDown() = server.stop()

    private fun halaman(): Bitmap {
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(180, 180, 180))
        c.drawOval(RectF(60f, 80f, 420f, 320f), Paint().apply {
            color = Color.WHITE; isAntiAlias = true
        })
        val hitam = Paint().apply { color = Color.BLACK }
        var x = 140f
        while (x < 340f) { c.drawRect(x, 170f, x + 14f, 240f, hitam); x += 30f }
        return bmp
    }

    private fun konfigurasi(): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.ocrTeksLepas = false
        return cfg
    }

    private fun pipa(cfg: Config) = Pipeline(
        ctx, cfg,
        log = { synchronized(logLines) { logLines.add(it) } },
        progress = { _, _ -> }
    )

    private fun provider() = OpenAICompatProvider(
        apiKey = "", modelName = "test-model",
        baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
        providerName = "Custom", jsonMode = false, requiresKey = false
    )

    private val deteksi = listOf(
        RtDetector.Det(intArrayOf(60, 80, 420, 320), RtDetector.CLASS_BUBBLE, 0.96f),
        RtDetector.Det(intArrayOf(135, 165, 345, 245), RtDetector.CLASS_TEXT_BUBBLE, 0.91f)
    )

    /** Jalankan pipeline sekali dan kembalikan body permintaan yang terkirim. */
    private fun jalankan(tag: String, glosarium: List<Glossary.Entry>?): String {
        val work = File(ctx.cacheDir, "gl_$tag").apply { mkdirs() }
        val f = File(work, "page.png")
        f.outputStream().use { halaman().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outDir = File(ctx.cacheDir, "glout_$tag").apply { mkdirs() }

        val p = pipa(konfigurasi())
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = provider()
        glosarium?.let { p.glossaryOverride = it }
        val r = p.run(
            inputs = listOf(Uri.fromFile(f)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        assertEquals("halaman tidak boleh gagal", 0, r.failed)
        return server.lastBody
    }

    @Test
    fun istilahGlosariumTerkirimKePenyedia() {
        val body = jalankan(
            "isi",
            listOf(
                Glossary.Entry("青魔法塔", "Menara Sihir Biru", "nama tempat"),
                Glossary.Entry("Pellin", "Pellin")
            )
        )
        assertTrue("prompt harus memuat blok glosarium", body.contains("GLOSSARY RULE"))
        // JSON body meng-escape non-ASCII, jadi cek pasangan yang aman diperiksa.
        assertTrue("istilah harus ada di prompt", body.contains("Pellin => Pellin"))
        assertTrue("catatan harus ikut", body.contains("nama tempat"))
    }

    @Test
    fun tanpaGlosariumPromptTetapBersih() {
        val body = jalankan("kosong", emptyList())
        assertFalse("tidak boleh ada blok glosarium", body.contains("GLOSSARY RULE"))
        // Aturan lama harus tetap utuh saat glosarium tidak dipakai.
        assertTrue(body.contains("HONORIFICS RULE"))
        assertTrue(body.contains("OUTPUT FORMAT"))
    }

    /**
     * Prompt tanpa glosarium harus IDENTIK dengan prompt sebelum fitur ini ada.
     * Ini yang menjaga agar pengguna yang tidak memakai glosarium tidak
     * mengalami perubahan kualitas terjemahan sama sekali.
     */
    @Test
    fun promptTanpaGlosariumTidakBerubahSatuKarakterPun() {
        val p = pipa(konfigurasi())
        val tanpa = p.buildPrompt("indonesian")
        p.close()

        val q = pipa(konfigurasi())
        q.glossaryOverride = emptyList()
        val kosongEksplisit = q.buildPrompt("indonesian")
        q.close()

        assertEquals(tanpa, kosongEksplisit)
        assertFalse(tanpa.contains("GLOSSARY"))
        // Blok glosarium harus menyisip tepat sebelum OUTPUT FORMAT.
        val r = pipa(konfigurasi())
        r.glossaryOverride = listOf(Glossary.Entry("A", "B"))
        val dengan = r.buildPrompt("indonesian")
        r.close()
        val iGlos = dengan.indexOf("GLOSSARY RULE")
        val iHon = dengan.indexOf("HONORIFICS RULE")
        val iOut = dengan.indexOf("OUTPUT FORMAT")
        assertTrue("urutan harus honorifik -> glosarium -> format keluaran",
            iHon in 1 until iGlos && iGlos < iOut)
    }

    @Test
    fun glosariumTerlaluBesarDipotongSebelumMasukPrompt() {
        val banyak = (1..400).map { Glossary.Entry("istilah$it", "terjemah$it") }
        val body = jalankan("besar", banyak)
        assertTrue(body.contains("istilah1 => terjemah1"))
        assertFalse("istilah di luar anggaran tidak boleh dikirim",
            body.contains("istilah399 => terjemah399"))
        assertTrue("log harus menyebut jumlah istilah",
            logLines.any { it.contains("Glosarium") })
    }
}
