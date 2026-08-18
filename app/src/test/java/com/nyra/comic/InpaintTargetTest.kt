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
import java.io.File

/**
 * Sasaran inpaint tidak boleh bocor antar halaman.
 *
 * Ronde 18 — pengguna mengirim tangkapan layar dengan bercak buram besar
 * (576x290 px) menutupi artwork, tepat di tempat yang seharusnya cuma balon
 * berisi teks. Penyebabnya: penanda "kotak ini teks lepas" disimpan di satu
 * HashSet milik Pipeline yang tidak pernah dibersihkan, dengan kunci berupa
 * koordinat piksel mentah "x1,y1,x2,y2" tanpa identitas halaman. Deteksi
 * berjalan untuk SEMUA halaman di pass 1, penggambaran baru di pass 3, jadi
 * saat halaman mana pun digambar isinya adalah akumulasi seluruh chapter.
 *
 * Akibatnya kotak di halaman N+1 yang kebetulan berkoordinat sama dengan teks
 * lepas di halaman N ikut dikirim ke LaMa dan artwork di baliknya terhapus.
 * Pada webtoon tabrakan itu sering: lebar halaman tetap dan tata letak balon
 * berulang antar panel.
 *
 * Tes ini memakai dua halaman berukuran sama. Halaman 1 punya satu balon plus
 * satu teks lepas; halaman 2 punya DUA balon, salah satunya persis di
 * koordinat teks lepas halaman 1. Yang diuji adalah PEMILIHAN sasaran, bukan
 * kualitas hasil hapus, jadi Inpainter sungguhan (butuh native onnxruntime
 * yang tidak ada di unit test) disulih lewat inpaintOverride.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class InpaintTargetTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    /** Kotak yang dipakai kedua halaman — inilah sumber tabrakan kunci. */
    private val kotakBalon = intArrayOf(100, 100, 400, 300)
    private val kotakBentrok = intArrayOf(600, 800, 900, 950)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    /** Halaman polos dengan beberapa persegi gelap supaya crop tidak kosong. */
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

    private fun replyWith(n: Int) {
        val o = JSONObject()
        for (i in 1..n) o.put(i.toString(), "Teks $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody =
            """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    @Test
    fun freeTextFlagDoesNotLeakToLaterPages() {
        val work = File(ctx.cacheDir, "inpaintleak").apply { mkdirs() }
        work.deleteRecursively(); work.mkdirs()

        val hal1 = File(work, "hal1.png")
        val hal2 = File(work, "hal2.png")
        buatHalaman(hal1, listOf(kotakBalon, kotakBentrok))
        buatHalaman(hal2, listOf(kotakBalon, kotakBentrok))

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.warnaOtomatis = false
        cfg.detektorRtdetr = false
        cfg.ocrTeksLepas = true
        cfg.inpaintLama = true          // jalur yang sedang diuji
        cfg.maxBubblesPerRequest = 20

        replyWith(4)

        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )

        // Halaman 1: satu balon. Halaman 2: DUA balon, yang kedua tepat di
        // koordinat teks lepas halaman 1.
        var panggilanDeteksi = 0
        pipeline.detectorOverride = { _: Bitmap ->
            when (panggilanDeteksi++) {
                0 -> listOf(kotakBalon)
                else -> listOf(kotakBalon, kotakBentrok)
            }
        }
        // Teks lepas HANYA ada di halaman 1.
        var panggilanTeks = 0
        pipeline.textDetectorOverride = { _: Bitmap ->
            if (panggilanTeks++ == 0) listOf(kotakBentrok) else emptyList()
        }

        // Rekam sasaran inpaint tiap bagian halaman, urut pemanggilan.
        val sasaranPerHalaman = mutableListOf<List<IntArray>>()
        pipeline.inpaintOverride = { boxes ->
            sasaranPerHalaman.add(boxes.map { it.copyOf() })
            1
        }

        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )

        val outDir = File(ctx.cacheDir, "outleak").apply { mkdirs() }
        val result = pipeline.run(
            inputs = listOf(Uri.fromFile(hal1), Uri.fromFile(hal2)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        pipeline.close()

        val log = logLines.joinToString("\n")
        assertEquals("pipeline melaporkan galat:\n$log", null, result.error)
        assertEquals("dua halaman harus berhasil\n$log", 2, result.success)

        // Prasyarat: teks lepas memang terdeteksi di halaman 1, kalau tidak
        // tes ini hijau tanpa menguji apa pun.
        assertTrue(
            "fixture harus menghasilkan teks lepas di halaman 1\n$log",
            log.contains("teks di luar balon ikut diterjemahkan")
        )
        assertEquals(
            "inpaint harus dipanggil untuk halaman 1 saja\n$log" +
                "\nsasaran=${sasaranPerHalaman.map { s -> s.map { it.toList() } }}",
            1, sasaranPerHalaman.size
        )

        val hal1Sasaran = sasaranPerHalaman[0]
        assertEquals("halaman 1 menghapus tepat satu teks lepas", 1, hal1Sasaran.size)
        assertEquals(
            "yang dihapus harus kotak teks lepas itu sendiri",
            kotakBentrok.toList(), hal1Sasaran[0].toList()
        )
    }
}
