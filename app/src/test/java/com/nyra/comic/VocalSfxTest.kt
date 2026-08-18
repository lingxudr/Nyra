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
 * Regresi untuk bug nyata yang dilaporkan pengguna pada halaman SPY x FAMILY
 * Mission 139.
 *
 * Gejalanya: dari sepuluh balon di halaman itu, SEMBILAN diterjemahkan dan
 * tepat SATU sama sekali tidak tersentuh - balon kecil berisi erangan
 * "うぐっ…" di koordinat [494,940,560,1062].
 *
 * Yang membuat bug ini mahal untuk didiagnosis: penyebabnya BUKAN deteksi.
 * Menjalankan rtdetr.onnx sungguhan atas halaman itu memberi balon tersebut
 * skor 0,893 - jauh di atas ambang 0,45 - dan kotaknya lolos sanitize,
 * mergeOverlapping, maupun dropAbsurd. Kotaknya ada, nomornya tercetak, dan
 * gambarnya terkirim ke model.
 *
 * Penyebab sebenarnya ada di dalam prompt. Aturan lama berbunyi "kalau balon
 * cuma berisi SFX, balas 'SKIP'", dan contoh keluarannya sendiri memasang
 * "SKIP" pada ID 2. Erangan tokoh secara harfiah memang bunyi, jadi model
 * menuruti instruksi kita dan mengembalikan SKIP. Pipeline lalu bekerja persis
 * seperti seharusnya: drawText menolak "SKIP", sasaranInpaint juga menolaknya,
 * sehingga tidak ada yang dihapus dan tidak ada yang digambar. Balonnya utuh
 * dalam bahasa Jepang.
 *
 * Karena itu tes ini menguji dua lapis:
 *  1. Kontrak prompt - suara vokal harus dinyatakan sebagai dialog.
 *  2. Perilaku ujung-ke-ujung - kalau model MENGEMBALIKAN terjemahan untuk
 *     balon erangan, piksel balon itu benar-benar berubah.
 *
 * Lapis kedua penting supaya tes tidak berubah menjadi sekadar pencocokan
 * kalimat di dalam prompt.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VocalSfxTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    /** Dua balon: satu dialog panjang, satu balon erangan kecil. */
    private val balonDialog = intArrayOf(60, 80, 420, 320)
    private val balonErangan = intArrayOf(520, 560, 700, 700)

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        server = TinyHttpServer()
        server.start()
        server.responseCode = 200
    }

    @After
    fun tearDown() = server.stop()

    /** Model membalas dengan terjemahan untuk kedua ID. */
    private fun balasKeduanya() {
        val o = JSONObject()
        for (i in 1..8) o.put(i.toString(), if (i == 2) "Ugh..." else "Terjemahan $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    /** Model membalas SKIP untuk balon erangan - perilaku lama yang buggy. */
    private fun balasSkipUntukErangan() {
        val o = JSONObject()
        for (i in 1..8) o.put(i.toString(), if (i == 2) "SKIP" else "Terjemahan $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    private fun halaman(): Bitmap {
        val bmp = Bitmap.createBitmap(764, 1000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(170, 170, 170))
        val putih = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        c.drawOval(RectF(60f, 80f, 420f, 320f), putih)
        c.drawOval(RectF(520f, 560f, 700f, 700f), putih)
        val hitam = Paint().apply { color = Color.BLACK }
        var x = 140f
        while (x < 340f) { c.drawRect(x, 170f, x + 14f, 240f, hitam); x += 30f }
        // "Teks" Jepang di dalam balon erangan.
        var y = 600f
        while (y < 670f) { c.drawRect(580f, y, 640f, y + 10f, hitam); y += 22f }
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
        cfg.cacheTerjemahan = false
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
        RtDetector.Det(balonDialog, RtDetector.CLASS_BUBBLE, 0.96f),
        RtDetector.Det(intArrayOf(135, 165, 345, 245), RtDetector.CLASS_TEXT_BUBBLE, 0.91f),
        // Skor asli balon うぐっ pada halaman pengguna.
        RtDetector.Det(balonErangan, RtDetector.CLASS_BUBBLE, 0.893f),
        RtDetector.Det(intArrayOf(575, 595, 645, 675), RtDetector.CLASS_TEXT_BUBBLE, 0.79f)
    )

    /** Jalankan pipeline; kembalikan bitmap keluaran dan body permintaan. */
    private fun jalankan(tag: String): Pair<Bitmap, String> {
        val work = File(ctx.cacheDir, "vs_$tag").apply { mkdirs() }
        val f = File(work, "page.png")
        f.outputStream().use { halaman().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outDir = File(ctx.cacheDir, "vsout_$tag").apply { mkdirs() }

        val p = pipa(konfigurasi())
        p.rtDetectorOverride = { deteksi }
        p.providerOverride = provider()
        val r = p.run(
            inputs = listOf(Uri.fromFile(f)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        p.close()
        assertEquals("halaman tidak boleh gagal", 0, r.failed)

        val keluaran = outDir.walkTopDown().filter {
            it.isFile && it.extension.equals("png", true)
        }.toList()
        assertTrue("harus ada berkas keluaran", keluaran.isNotEmpty())
        val bmp = android.graphics.BitmapFactory.decodeFile(keluaran.first().absolutePath)
        return bmp to server.lastBody
    }

    /** Berapa persen piksel di dalam [box] yang berbeda dari halaman sumber. */
    private fun persenBerubah(hasil: Bitmap, box: IntArray): Float {
        val asli = halaman()
        var beda = 0
        var total = 0
        for (y in box[1] until minOf(box[3], hasil.height)) {
            for (x in box[0] until minOf(box[2], hasil.width)) {
                val a = asli.getPixel(x, y)
                val b = hasil.getPixel(x, y)
                // Ekstraksi kanal lewat geseran bit: Color.red/green/blue
                // mengembalikan 0 di unit test JVM biasa.
                val ga = ((a shr 16 and 0xFF) * 299 + (a shr 8 and 0xFF) * 587 +
                    (a and 0xFF) * 114) / 1000
                val gb = ((b shr 16 and 0xFF) * 299 + (b shr 8 and 0xFF) * 587 +
                    (b and 0xFF) * 114) / 1000
                if (kotlin.math.abs(ga - gb) > 28) beda++
                total++
            }
        }
        asli.recycle()
        return if (total == 0) 0f else beda * 100f / total
    }

    // ---------------------------------------------------------------- prompt

    @Test
    fun promptMenyatakanSuaraTokohAdalahDialog() {
        val p = pipa(konfigurasi())
        val prompt = p.buildPrompt("indonesian")
        p.close()

        assertTrue(
            "prompt harus punya aturan SFX dan suara",
            prompt.contains("SFX AND VOICE RULE")
        )
        assertTrue(
            "suara dari mulut tokoh harus dinyatakan sebagai dialog",
            prompt.contains("dialogue, not SFX")
        )
        assertTrue(
            "erangan yang gagal di halaman pengguna harus dicontohkan eksplisit",
            prompt.contains("うぐっ")
        )
    }

    /**
     * Inti bug-nya. Contoh keluaran lama memasang "SKIP" pada ID 2, dan model
     * meniru contoh. Contoh sekarang harus memperlihatkan erangan yang
     * DITERJEMAHKAN.
     */
    @Test
    fun contohKeluaranTidakMengajarkanSkipUntukErangan() {
        val p = pipa(konfigurasi())
        val prompt = p.buildPrompt("indonesian")
        p.close()

        val contoh = prompt.substringAfter("Example output: ")
        assertTrue("contoh harus memuat terjemahan erangan", contoh.contains("Ugh..."))
        assertFalse(
            "ID 2 pada contoh tidak boleh lagi berupa SKIP",
            contoh.contains("\"2\": \"SKIP\"")
        )
    }

    @Test
    fun aturanSkipHanyaUntukBalonBenarBenarKosong() {
        val p = pipa(konfigurasi())
        val prompt = p.buildPrompt("indonesian")
        p.close()

        assertTrue(
            "balon berisi teks apa pun wajib diterjemahkan",
            prompt.contains("contains ANY readable text must be translated")
        )
        // Aturan lama yang menyebabkan bug tidak boleh hidup lagi.
        assertFalse(
            "aturan lama 'hanya SFX -> SKIP' harus sudah hilang",
            prompt.contains("If a bubble contains ONLY sound effects (SFX) with no dialogue")
        )
    }

    @Test
    fun aturanPromptIkutTerkirimKePenyedia() {
        balasKeduanya()
        val (bmp, body) = jalankan("kirim")
        bmp.recycle()
        assertTrue("aturan suara harus sampai ke server", body.contains("SFX AND VOICE RULE"))
        assertTrue(body.contains("HONORIFICS RULE"))
        assertTrue(body.contains("OUTPUT FORMAT"))
    }

    // ------------------------------------------------------------ ujung-ujung

    /**
     * Bukti perilaku: ketika model mengembalikan terjemahan untuk balon
     * erangan, piksel balon itu HARUS berubah. Inilah yang tidak terjadi pada
     * halaman pengguna.
     */
    @Test
    fun balonEranganIkutTergambarUlang() {
        balasKeduanya()
        val (bmp, _) = jalankan("gambar")

        val dialog = persenBerubah(bmp, balonDialog)
        val erangan = persenBerubah(bmp, balonErangan)
        bmp.recycle()

        assertTrue("balon dialog harus tergambar ulang, dapat $dialog%", dialog > 3f)
        assertTrue(
            "balon erangan juga harus tergambar ulang, dapat $erangan%",
            erangan > 3f
        )
    }

    /**
     * Sisi sebaliknya, supaya kontrak lama tidak ikut rusak: kalau model
     * memang membalas SKIP, pipeline tidak boleh menghapus apa pun. Menghapus
     * tanpa menggambar pengganti akan meninggalkan bercak kosong.
     */
    @Test
    fun skipTetapTidakMenghapusApaPun() {
        balasSkipUntukErangan()
        val (bmp, _) = jalankan("skip")

        val erangan = persenBerubah(bmp, balonErangan)
        bmp.recycle()

        assertTrue(
            "SKIP harus meninggalkan balon apa adanya, dapat $erangan%",
            erangan < 1f
        )
    }
}
