package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
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
 * Bukti bahwa gambar halaman utuh benar-benar ikut terkirim sebagai gambar
 * KEDUA, dan bahwa janji di prompt selalu cocok dengan isi payload.
 *
 * Yang diperiksa lewat kabel sungguhan (loopback HTTP), bukan lewat unit
 * matematika: jumlah bagian gambar dalam body, urutannya, dan keberadaan blok
 * REFERENCE IMAGE.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageReferencePipelineTest {

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

    private val deteksi = listOf(
        RtDetector.Det(intArrayOf(60, 80, 420, 320), RtDetector.CLASS_BUBBLE, 0.96f),
        RtDetector.Det(intArrayOf(135, 165, 345, 245), RtDetector.CLASS_TEXT_BUBBLE, 0.91f)
    )

    private fun konfigurasi(rujukan: Boolean): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.ocrTeksLepas = false
        cfg.konteksHalaman = false
        cfg.gambarRujukan = rujukan
        // Tes ini memeriksa struktur prompt rujukan halaman; review (pass 2b)
        // menambah panggilan teks yang mengubah `server.lastBody`, jadi dimatikan.
        cfg.selfReview = false
        return cfg
    }

    /** Jalankan [jml] halaman terpisah; kembalikan body yang terkirim. */
    private fun jalankan(tag: String, rujukan: Boolean, jml: Int = 1): List<String> {
        val work = File(ctx.cacheDir, "pr_$tag").apply { mkdirs() }
        val files = (1..jml).map { i ->
            File(work, "%03d.png".format(i)).also { f ->
                f.outputStream().use { halaman().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val outDir = File(ctx.cacheDir, "prout_$tag").apply { mkdirs() }

        val p = Pipeline(
            ctx, konfigurasi(rujukan),
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        val r = p.run(
            inputs = files.map { Uri.fromFile(it) },
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        assertEquals("tidak boleh ada halaman gagal", 0, r.failed)
        return server.bodies.toList()
    }

    /** Bagian-bagian pesan multimodal dari satu body OpenAI-compat. */
    private fun konten(body: String): JSONArray =
        JSONObject(body).getJSONArray("messages").getJSONObject(0).getJSONArray("content")

    private fun jumlahGambar(body: String): Int {
        val c = konten(body)
        return (0 until c.length()).count { c.getJSONObject(it).optString("type") == "image_url" }
    }

    private fun teks(body: String): String {
        val c = konten(body)
        for (i in 0 until c.length()) {
            val o = c.getJSONObject(i)
            if (o.optString("type") == "text") return o.getString("text")
        }
        return ""
    }

    @Test
    fun halamanUtuhTerkirimSebagaiGambarKedua() {
        val bodies = jalankan("on", rujukan = true)
        assertTrue("harus ada permintaan", bodies.isNotEmpty())

        val body = bodies.first()
        assertEquals("mosaik + halaman utuh", 2, jumlahGambar(body))

        val c = konten(body)
        assertEquals("gambar harus mendahului teks", "image_url", c.getJSONObject(0).getString("type"))
        assertEquals("image_url", c.getJSONObject(1).getString("type"))
        assertEquals("teks harus paling akhir", "text", c.getJSONObject(2).getString("type"))

        // Keduanya harus gambar yang BERBEDA: kalau mosaik terkirim dua kali,
        // fitur ini tidak menambah konteks apa pun.
        val u0 = c.getJSONObject(0).getJSONObject("image_url").getString("url")
        val u1 = c.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertFalse("rujukan tidak boleh sama dengan mosaik", u0 == u1)

        assertTrue("prompt harus menjelaskan gambar kedua", teks(body).contains("REFERENCE IMAGE"))
        assertTrue("log harus menyebut rujukan", logLines.any { it.contains("Rujukan: halaman utuh") })
    }

    @Test
    fun sakelarMatiMengembalikanSatuGambarDanPromptTanpaJanji() {
        val bodies = jalankan("off", rujukan = false)
        assertTrue(bodies.isNotEmpty())
        for ((i, b) in bodies.withIndex()) {
            assertEquals("permintaan ke-$i harus satu gambar saja", 1, jumlahGambar(b))
            assertFalse(
                "prompt tidak boleh menjanjikan gambar kedua",
                teks(b).contains("REFERENCE IMAGE")
            )
        }
        assertFalse(logLines.any { it.contains("Rujukan: halaman utuh") })
    }

    /**
     * Janji dan isi harus selalu sinkron. Kalau prompt menyebut dua gambar
     * padahal cuma satu yang dikirim, model mencari halaman yang tidak ada.
     */
    @Test
    fun promptHanyaMenjanjikanGambarKeduaSaatBenarBenarDikirim() {
        val p = Pipeline(ctx, konfigurasi(true), log = {}, progress = { _, _ -> })
        assertFalse(p.buildPrompt("indonesian", false).contains("REFERENCE IMAGE"))
        assertTrue(p.buildPrompt("indonesian", true).contains("REFERENCE IMAGE"))
    }

    /** Blok rujukan harus tetap sebelum OUTPUT FORMAT supaya aturannya terbaca. */
    @Test
    fun blokRujukanSebelumFormatKeluaran() {
        val p = Pipeline(ctx, konfigurasi(true), log = {}, progress = { _, _ -> })
        val prompt = p.buildPrompt("indonesian", true)
        assertTrue(prompt.indexOf("REFERENCE IMAGE") < prompt.indexOf("OUTPUT FORMAT"))
    }
}
