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

    /**
     * @param varian menggeser pola teks di dalam balon. Halaman dengan varian
     *   berbeda menghasilkan sidik cache berbeda, yang diperlukan oleh tes yang
     *   menyalakan cache: dua halaman identik akan sama-sama kena cache dan
     *   tidak menyisakan permintaan untuk diperiksa.
     */
    private fun halaman(varian: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(180, 180, 180))
        c.drawOval(RectF(60f, 80f, 420f, 320f), Paint().apply {
            color = Color.WHITE; isAntiAlias = true
        })
        val hitam = Paint().apply { color = Color.BLACK }
        var x = 140f
        val tebal = 14f + varian * 6f
        while (x < 340f) { c.drawRect(x, 170f, x + tebal, 240f, hitam); x += 30f }
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
        // Kedua halaman fixture identik, jadi cache akan menjawab halaman kedua
        // tanpa permintaan dan tes ini kehilangan permintaan yang mau diperiksa.
        // Interaksi cache-dengan-konteks diuji terpisah di bawah.
        cfg.cacheTerjemahan = false
        // Satu balon per permintaan, dan permintaan dikirim berurutan.
        //
        // Yang diuji di sini adalah aliran konteks ANTAR permintaan, jadi
        // harus ada lebih dari satu. Sejak gambar lepas yang berurutan
        // digabung menjadi satu batch, kedua halaman fixture ini muat dalam
        // satu chunk dan hanya menghasilkan satu permintaan - konteksnya tidak
        // hilang, tapi tidak ada permintaan kedua untuk memeriksanya. Memecah
        // per balon mengembalikan skenarionya; requestParalel=1 menjamin hasil
        // permintaan pertama sudah masuk riwayat sebelum yang kedua disusun.
        cfg.maxBubblesPerRequest = 1
        cfg.requestParalel = 1
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

    /**
     * Regresi: balon yang dijawab dari cache TETAP harus masuk riwayat konteks.
     *
     * Bug yang ditemukan saat cache diperkenalkan: penyaringan cache terjadi
     * sebelum blok pencatatan konteks, sehingga halaman yang seluruh balonnya
     * kena cache tidak menyumbang apa pun ke riwayat. Akibatnya justru pada bab
     * yang paling sering diproses ulang, halaman berikutnya kehilangan konteks
     * lintas halaman - diam-diam, karena hasilnya tetap terlihat wajar.
     *
     * Di sini halaman pertama diproses dua kali dengan cache menyala, lalu
     * halaman kedua harus tetap menerima blok konteks.
     */
    @Test
    fun konteksTetapTercatatWalauDijawabCache() {
        val work = File(ctx.cacheDir, "pc_cache").apply { mkdirs() }
        val f1 = File(work, "001.png")
        val f2 = File(work, "002.png")
        // Isi kedua halaman sengaja BERBEDA: kalau sama, halaman kedua ikut
        // kena cache dan tidak ada permintaan tersisa untuk diperiksa.
        f1.outputStream().use { halaman(0).compress(Bitmap.CompressFormat.PNG, 100, it) }
        f2.outputStream().use { halaman(1).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outDir = File(ctx.cacheDir, "pcout_cache").apply { mkdirs() }
        val cache = TranslationCache(File(work, "cache.json"))

        fun jalan(inputs: List<Uri>): Pipeline {
            val cfg = konfigurasi(true).apply { cacheTerjemahan = true }
            val p = Pipeline(
                ctx, cfg,
                log = { synchronized(logLines) { logLines.add(it) } },
                progress = { _, _ -> }
            )
            p.rtDetectorOverride = { deteksi }
            p.cacheOverride = cache
            p.providerOverride = OpenAICompatProvider(
                apiKey = "", modelName = "test-model",
                baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
                providerName = "Custom", jsonMode = false, requiresKey = false
            )
            p.run(inputs, "indonesian", Uri.fromFile(outDir))
            return p
        }

        // Isi cache lebih dulu dengan satu halaman.
        jalan(listOf(Uri.fromFile(f1))).close()
        server.bodies.clear()

        // Sekarang dua halaman: halaman pertama kena cache sepenuhnya, jadi
        // permintaan yang tersisa hanyalah milik halaman kedua.
        jalan(listOf(Uri.fromFile(f1), Uri.fromFile(f2))).close()

        val bodies = server.bodies.toList()
        assertTrue("halaman kedua tetap harus mengirim permintaan", bodies.isNotEmpty())
        assertTrue(
            "permintaan halaman kedua harus membawa konteks dari halaman yang kena cache:\n" +
                bodies.first().take(400),
            bodies.first().contains("PREVIOUS PAGES CONTEXT")
        )
        assertTrue(
            "nama halaman yang dijawab cache harus muncul di konteks",
            bodies.first().contains("001.png")
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
