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
 * Bukti bahwa konteks halaman benar-benar terkirim ke penyedia.
 *
 * Inti fitur ini adalah hubungan ANTAR permintaan, jadi tesnya memproses dua
 * halaman sekaligus lalu membandingkan body permintaan kedua dengan jawaban
 * yang diberikan pada permintaan pertama.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageContextPipelineTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
        // Kalimat khas supaya gampang dicari lagi di permintaan berikutnya.
        val o = JSONObject()
        for (i in 1..8) o.put(i.toString(), "Pellin si Penyihir Biru $i")
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

    private fun konfigurasi(konteks: Boolean): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.ocrTeksLepas = false
        cfg.konteksHalaman = konteks
        return cfg
    }

    /** Proses dua halaman terpisah; kembalikan seluruh body yang terkirim. */
    private fun jalankanDuaHalaman(tag: String, konteks: Boolean): List<String> {
        val work = File(ctx.cacheDir, "pc_$tag").apply { mkdirs() }
        val f1 = File(work, "001.png")
        val f2 = File(work, "002.png")
        for (f in listOf(f1, f2)) {
            f.outputStream().use { halaman().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val outDir = File(ctx.cacheDir, "pcout_$tag").apply { mkdirs() }

        val p = Pipeline(
            ctx, konfigurasi(konteks),
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
            inputs = listOf(Uri.fromFile(f1), Uri.fromFile(f2)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        assertEquals("tidak boleh ada halaman gagal", 0, r.failed)
        return server.bodies.toList()
    }

    @Test
    fun permintaanKeduaMembawaHasilPermintaanPertama() {
        val bodies = jalankanDuaHalaman("on", konteks = true)
        assertTrue("harus ada minimal 2 permintaan", bodies.size >= 2)

        assertFalse(
            "permintaan pertama belum punya riwayat apa pun",
            bodies.first().contains("PREVIOUS PAGES CONTEXT")
        )
        assertTrue(
            "permintaan kedua harus membawa blok konteks",
            bodies[1].contains("PREVIOUS PAGES CONTEXT")
        )
        assertTrue(
            "terjemahan halaman sebelumnya harus ikut terkirim",
            bodies[1].contains("Pellin si Penyihir Biru")
        )
        assertTrue(
            "nama halaman asal harus disebut",
            bodies[1].contains("001.png")
        )
        assertTrue(
            "log harus menyebut konteks",
            logLines.any { it.contains("Konteks:") }
        )
    }

    @Test
    fun sakelarMatiMembuatSemuaPermintaanBersih() {
        val bodies = jalankanDuaHalaman("off", konteks = false)
        assertTrue("harus ada minimal 2 permintaan", bodies.size >= 2)
        for ((i, b) in bodies.withIndex()) {
            assertFalse(
                "permintaan ke-$i tidak boleh punya konteks saat sakelar mati",
                b.contains("PREVIOUS PAGES CONTEXT")
            )
        }
        assertFalse(logLines.any { it.contains("Konteks:") })
    }

    /**
     * Urutan blok menentukan siapa yang menang saat isinya berbenturan:
     * glosarium ditulis pengguna dan harus mengalahkan riwayat, yang hanya
     * contoh pemakaian sebelumnya.
     */
    @Test
    fun urutanGlosariumSebelumKonteksSebelumFormat() {
        val p = Pipeline(ctx, konfigurasi(true), log = {}, progress = { _, _ -> })
        p.glossaryOverride = listOf(Glossary.Entry("A", "B"))
        p.pageContext.add("001.png", listOf("Baris konteks"))
        val prompt = p.buildPrompt("indonesian")

        val iGlos = prompt.indexOf("GLOSSARY")
        val iKon = prompt.indexOf("PREVIOUS PAGES CONTEXT")
        val iOut = prompt.indexOf("OUTPUT FORMAT")
        assertTrue("glosarium harus ada", iGlos > 0)
        assertTrue("konteks harus ada", iKon > 0)
        assertTrue("urutan harus glosarium -> konteks -> format", iGlos < iKon)
        assertTrue("konteks harus sebelum OUTPUT FORMAT", iKon < iOut)
    }

    @Test
    fun riwayatKosongMenghasilkanPromptIdentik() {
        // Fitur mati.
        val a = Pipeline(ctx, konfigurasi(false), log = {}, progress = { _, _ -> })
        val tanpaFitur = a.buildPrompt("indonesian")

        // Fitur nyala tetapi riwayat masih kosong: halaman pertama tiap bab.
        val b = Pipeline(ctx, konfigurasi(true), log = {}, progress = { _, _ -> })
        val riwayatKosong = b.buildPrompt("indonesian")

        assertEquals(tanpaFitur, riwayatKosong)
        assertFalse(tanpaFitur.contains("PREVIOUS PAGES"))
    }

    /** Satu berkas = satu bab: riwayat tidak boleh bocor antar berkas. */
    @Test
    fun riwayatDiresetSaatPindahBerkas() {
        val p = Pipeline(ctx, konfigurasi(true), log = {}, progress = { _, _ -> })
        p.pageContext.add("bab1_001.png", listOf("Tokoh bab lama"))
        assertEquals(1, p.pageContext.size)
        p.pageContext.clear()
        assertEquals(0, p.pageContext.size)
        assertFalse(p.buildPrompt("indonesian").contains("Tokoh bab lama"))
    }
}
