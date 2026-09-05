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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Cache terjemahan pada tingkat pipeline.
 *
 * Yang diukur adalah PERMINTAAN YANG SAMPAI KE SERVER, bukan keberadaan berkas
 * keluaran. Pelajaran dari fitur resume: pipeline selalu menulis setiap halaman
 * (bahkan saat dibatalkan), jadi "berkas ada" tidak membuktikan apa pun tentang
 * pekerjaan yang dihemat. Hanya jumlah body yang diterima TinyHttpServer yang
 * membuktikan sebuah request benar-benar tidak jadi dikirim.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CachePipelineTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private lateinit var work: File
    private val log = mutableListOf<String>()

    @Before
    fun siap() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
        work = File(ctx.cacheDir, "cachepipe_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun bubar() {
        server.stop()
        work.deleteRecursively()
    }

    /**
     * Halaman dengan dua balon yang jelas berbeda isinya.
     *
     * Isi balon harus berbeda: kalau keduanya identik, sidik cache-nya sama dan
     * balon kedua akan kena cache dari balon pertama dalam proses yang SAMA -
     * yang membuat hitungan request tidak lagi menguji apa yang dimaksud.
     */
    private fun halaman(): File {
        val bmp = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val hitam = Paint().apply { color = Color.BLACK; isAntiAlias = false }

        // Balon 1: satu batang tebal di kiri atas.
        c.drawRect(80f, 120f, 240f, 300f, hitam)
        // Balon 2: pola garis-garis di bawah - jelas beda pada 32x32.
        for (i in 0 until 6) {
            c.drawRect(80f, (560 + i * 24).toFloat(), 500f, (572 + i * 24).toFloat(), hitam)
        }

        val f = File(work, "page.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    private val kotak = listOf(
        intArrayOf(60, 100, 260, 320),
        intArrayOf(60, 540, 520, 720)
    )

    private fun balasDenganDuaBalon() {
        val o = JSONObject().put("1", "Terjemahan satu").put("2", "Terjemahan dua")
        val isi = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody = """{"choices":[{"message":{"content":"$isi"}}],
            "usage":{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280}}"""
            .trimIndent().replace("\n", "")
    }

    private fun pipeline(cfg: Config, cache: TranslationCache?): Pipeline {
        val p = Pipeline(ctx, cfg, log = { synchronized(log) { log.add(it) } }, progress = { _, _ -> })
        p.detectorOverride = { _: Bitmap -> kotak }
        p.textDetectorOverride = { _: Bitmap -> emptyList() }
        p.cacheOverride = cache
        p.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        return p
    }

    private fun konfig(): Config = Config(ctx).apply {
        provider = "custom"
        customBaseUrl = "http://127.0.0.1:${server.port}"
        minRequestDelay = 0.0f
        filterSfxAktif = false
        detektorRtdetr = false
        ocrTeksLepas = false
        simpanProyek = false
        lanjutkanArsip = false
        gambarRujukan = false
        konteksHalaman = false
        inpaintLama = false
        cacheTerjemahan = true
        hitungBiaya = true
        // Pass 2b (self-review) menambah satu panggilan teks; tes ini menghitung
        // permintaan visi yang sampai ke server, jadi review dimatikan.
        selfReview = false
        maxBubblesPerRequest = 20
    }

    /**
     * Inti fitur: menjalankan halaman yang sama dua kali hanya boleh menembak
     * API sekali. Pada jalan kedua seluruh balon sudah dikenal, sehingga tidak
     * ada satu pun body baru yang sampai ke server.
     */
    @Test
    fun `jalan kedua tidak mengirim request apa pun`() {
        val f = halaman()
        val cfg = konfig()
        val outDir = File(work, "out").apply { mkdirs() }
        val cache = TranslationCache(File(work, "cache.json"))
        balasDenganDuaBalon()

        val p1 = pipeline(cfg, cache)
        val r1 = p1.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p1.close()
        val setelahJalanPertama = server.bodies.size

        assertEquals("jalan pertama harus sukses:\n${log.joinToString("\n")}", null, r1.error)
        assertTrue("jalan pertama harus mengirim request", setelahJalanPertama >= 1)
        assertEquals("belum ada yang bisa di-cache pada jalan pertama", 0, r1.cacheKena)

        // Jalan kedua: cache yang sama, halaman yang sama.
        val p2 = pipeline(cfg, cache)
        val r2 = p2.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p2.close()

        assertEquals(null, r2.error)
        assertEquals(
            "jalan kedua tidak boleh menambah request ke server:\n${log.joinToString("\n")}",
            setelahJalanPertama, server.bodies.size
        )
        assertEquals("kedua balon harus dijawab dari cache", 2, r2.cacheKena)
        assertTrue("log harus menyebut cache",
            log.any { it.contains("[Cache]") && it.contains("dijawab dari cache") })
    }

    /**
     * Cache tidak boleh dipakai lintas bahasa. Kalau kunci mengabaikan bahasa,
     * pengguna yang ganti bahasa sasaran akan mendapat teks bahasa lama.
     */
    @Test
    fun `bahasa berbeda tidak memakai cache`() {
        val f = halaman()
        val cfg = konfig()
        val outDir = File(work, "out2").apply { mkdirs() }
        val cache = TranslationCache(File(work, "cache2.json"))
        balasDenganDuaBalon()

        val p1 = pipeline(cfg, cache)
        p1.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p1.close()
        val setelah = server.bodies.size

        val p2 = pipeline(cfg, cache)
        val r2 = p2.run(listOf(Uri.fromFile(f)), "english", Uri.fromFile(outDir))
        p2.close()

        assertTrue("bahasa baru wajib memicu request baru",
            server.bodies.size > setelah)
        assertEquals(0, r2.cacheKena)
    }

    /** Sakelar mati berarti benar-benar mati: cache tidak dibaca maupun ditulis. */
    @Test
    fun `cache dimatikan berarti selalu mengirim request`() {
        val f = halaman()
        val cfg = konfig().apply { cacheTerjemahan = false }
        val outDir = File(work, "out3").apply { mkdirs() }
        val cache = TranslationCache(File(work, "cache3.json"))
        balasDenganDuaBalon()

        val p1 = pipeline(cfg, cache)
        p1.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p1.close()
        val setelah = server.bodies.size

        val p2 = pipeline(cfg, cache)
        val r2 = p2.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p2.close()

        assertTrue("dengan cache mati, jalan kedua harus tetap menembak API",
            server.bodies.size > setelah)
        assertEquals(0, r2.cacheKena)
        assertEquals(0, cache.ukuran)
    }

    /**
     * Token yang dilaporkan server harus muncul di Result dan di konsol.
     * Ini yang membuat biaya bisa dipercaya: angkanya berasal dari respons,
     * bukan taksiran panjang prompt.
     */
    @Test
    fun `token dari respons tercatat di hasil dan konsol`() {
        val f = halaman()
        val cfg = konfig()
        val outDir = File(work, "out4").apply { mkdirs() }
        balasDenganDuaBalon()

        val p = pipeline(cfg, TranslationCache(File(work, "cache4.json")))
        val r = p.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p.close()

        assertEquals(null, r.error)
        assertEquals("token masuk harus dari usage.prompt_tokens", 1200L, r.pemakaian.masuk)
        assertEquals(80L, r.pemakaian.keluar)
        assertTrue("ringkasan biaya harus dicetak:\n${log.joinToString("\n")}",
            log.any { it.startsWith("[Biaya]") })
    }

    /** Sakelar biaya mati: tidak ada baris biaya sama sekali di konsol. */
    @Test
    fun `biaya dimatikan menyembunyikan laporan`() {
        val f = halaman()
        val cfg = konfig().apply { hitungBiaya = false }
        val outDir = File(work, "out5").apply { mkdirs() }
        balasDenganDuaBalon()

        val p = pipeline(cfg, TranslationCache(File(work, "cache5.json")))
        p.run(listOf(Uri.fromFile(f)), "indonesian", Uri.fromFile(outDir))
        p.close()

        assertTrue("tidak boleh ada baris biaya", log.none { it.startsWith("[Biaya]") })
        assertTrue("tidak boleh ada baris token per request", log.none { it.startsWith("  [$]") })
    }
}
