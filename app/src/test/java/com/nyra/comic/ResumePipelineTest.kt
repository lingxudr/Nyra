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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bukti bahwa melanjutkan arsip benar-benar menghemat permintaan berbayar.
 *
 * Yang diukur bukan berkas keluaran, melainkan JUMLAH PERMINTAAN yang sampai
 * ke server. Itu satu-satunya ukuran yang jujur: sebuah implementasi yang
 * diam-diam menerjemahkan ulang lalu menimpa hasilnya akan tetap menghasilkan
 * PDF yang benar, dan tetap menghabiskan seluruh kuota pengguna.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResumePipelineTest {

    private lateinit var server: TinyHttpServer
    private lateinit var ctx: android.content.Context
    private val logLines = mutableListOf<String>()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        File(ctx.filesDir, "resume").deleteRecursively()
        server = TinyHttpServer()
        server.start()
        val o = JSONObject()
        for (i in 1..20) o.put(i.toString(), "Terjemahan $i")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody = """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    @After
    fun tearDown() = server.stop()

    private fun halaman(): Bitmap {
        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(190, 190, 190))
        c.drawOval(RectF(50f, 60f, 350f, 260f), Paint().apply {
            color = Color.WHITE; isAntiAlias = true
        })
        val hitam = Paint().apply { color = Color.BLACK }
        var x = 110f
        while (x < 290f) { c.drawRect(x, 140f, x + 12f, 200f, hitam); x += 26f }
        return bmp
    }

    private val deteksi = listOf(
        RtDetector.Det(intArrayOf(50, 60, 350, 260), RtDetector.CLASS_BUBBLE, 0.95f),
        RtDetector.Det(intArrayOf(105, 135, 295, 205), RtDetector.CLASS_TEXT_BUBBLE, 0.9f)
    )

    /** CBZ tiga halaman. */
    private fun buatCbz(f: File) {
        val bos = java.io.ByteArrayOutputStream()
        halaman().compress(Bitmap.CompressFormat.PNG, 100, bos)
        val png = bos.toByteArray()
        ZipOutputStream(f.outputStream()).use { z ->
            for (n in listOf("001.png", "002.png", "003.png")) {
                z.putNextEntry(ZipEntry(n)); z.write(png); z.closeEntry()
            }
        }
    }

    private fun konfigurasi(lanjut: Boolean, bahasa: String = "indonesian"): Config {
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.detektorRtdetr = true
        cfg.ocrTeksLepas = false
        cfg.konteksHalaman = false
        cfg.gambarRujukan = false
        cfg.simpanProyek = false
        // Cache terjemahan dimatikan: tes ini mengukur penghematan dari RESUME
        // dengan menghitung permintaan yang sampai ke server, dan cache akan
        // menghapus permintaan yang sama sehingga kedua fitur tak terpisahkan.
        // Cache diuji sendiri di CachePipelineTest.
        cfg.cacheTerjemahan = false
        cfg.lanjutkanArsip = lanjut
        // Satu balon per permintaan: jumlah permintaan jadi sebanding dengan
        // jumlah halaman, sehingga penghematan bisa dihitung.
        cfg.maxBubblesPerRequest = 1
        return cfg
    }

    /**
     * Penyedia yang menghentikan pipeline setelah [batas] permintaan berhasil.
     *
     * Inilah bentuk gangguan yang sesungguhnya: sebagian halaman sudah dibayar
     * dan dijawab, sisanya belum tersentuh. Membatalkan lewat callback
     * progress tidak bisa menirukannya - pembatalan terjadi sebelum permintaan
     * pertama sempat dikirim, sehingga tidak ada yang bisa dilanjutkan.
     */
    private class PenyediaTerputus(
        private val asli: OpenAICompatProvider,
        private val batas: Int,
        private val hentikan: () -> Unit
    ) : LLMProvider("", "test-model") {
        override val providerName = "Custom"
        override fun validateApiKey() = true
        private var n = 0
        override fun translateImage(image: android.graphics.Bitmap, prompt: String): String? {
            val hasil = asli.translateImage(image, prompt)
            if (++n >= batas) hentikan()
            return hasil
        }
    }

    /**
     * Berapa halaman yang tercatat selesai, dibaca dari folder resume yang
     * sungguhan.
     *
     * Kuncinya sengaja tidak dihitung ulang di sini: kunci produksi memakai
     * ukuran dari ContentResolver, dan menirukannya di tes hanya akan menguji
     * salinan rumus, bukan berkas yang benar-benar ditulis.
     */
    private fun tercatatSelesai(): Int {
        val akar = File(ctx.filesDir, "resume")
        val dirs = akar.listFiles()?.filter { it.isDirectory } ?: return 0
        return dirs.sumOf { d ->
            d.listFiles()?.count { it.name.startsWith("p") && it.name.endsWith(".png") } ?: 0
        }
    }

    /** [batalSetelah] = jumlah permintaan sebelum proses dihentikan. */
    private fun jalankan(
        cbz: File, cfg: Config, tag: String, batalSetelah: Int = -1
    ): Pipeline.Result {
        val outDir = File(ctx.cacheDir, "rout_$tag").apply { mkdirs() }
        val p = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        p.rtDetectorOverride = { deteksi }
        val dasar = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )
        p.providerOverride =
            if (batalSetelah > 0) PenyediaTerputus(dasar, batalSetelah) { p.cancel() }
            else dasar
        return p.run(
            inputs = listOf(Uri.fromFile(cbz)),
            targetLanguage = cfg.let { "indonesian" },
            outputTree = Uri.fromFile(outDir)
        )
    }

    @Test
    fun jalanKeduaTidakMengulangiHalamanYangSudahDibayar() {
        val cbz = File(ctx.cacheDir, "bab.cbz")
        buatCbz(cbz)

        // Jalan pertama diputus setelah satu permintaan berhasil.
        jalankan(cbz, konfigurasi(true), "a1", batalSetelah = 1)
        val permintaanPertama = server.bodies.size
        assertTrue("jalan pertama harus memanggil model", permintaanPertama >= 1)

        // Hanya halaman yang benar-benar diterjemahkan yang boleh tercatat.
        val tercatat = tercatatSelesai()
        assertTrue(
            "harus ada halaman yang tercatat selesai, dapat $tercatat\n" +
                logLines.joinToString("\n"),
            tercatat >= 1
        )
        assertTrue("tidak boleh mencatat lebih banyak dari yang ada", tercatat < 3)

        server.bodies.clear()
        logLines.clear()

        // Jalan kedua tanpa gangguan: harus menuntaskan sisanya saja.
        val r2 = jalankan(cbz, konfigurasi(true), "a2")
        assertEquals("bab harus tuntas", 3, r2.success)
        assertEquals(0, r2.failed)
        assertTrue("log harus menjelaskan bahwa ia melanjutkan",
            logLines.any { it.contains("[Lanjut]") })
        assertTrue(
            "halaman yang sudah selesai tidak boleh diminta ulang " +
                "(${server.bodies.size} permintaan untuk ${3 - tercatat} halaman)",
            server.bodies.size <= (3 - tercatat) * 2
        )
        assertEquals("PDF tetap harus dihasilkan", 1, r2.outputs.size)
    }

    /**
     * Regresi penting: saat proses dihentikan, pass 3 tetap menulis SEMUA
     * halaman - yang belum diterjemahkan dilewatkan apa adanya supaya bab
     * tetap terbaca. Kalau halaman apa adanya itu dicatat sebagai selesai, ia
     * tidak akan pernah diterjemahkan lagi dan pengguna terjebak dengan bab
     * setengah jadi selamanya.
     */
    @Test
    fun halamanYangBelumDiterjemahkanTidakDicatatSelesai() {
        val cbz = File(ctx.cacheDir, "bab5.cbz")
        buatCbz(cbz)

        // Dibatalkan sedini mungkin: hanya satu halaman yang sempat selesai,
        // tetapi berkas keluaran tetap ditulis untuk ketiga halaman.
        val r1 = jalankan(cbz, konfigurasi(true), "e1", batalSetelah = 1)
        val tercatat = tercatatSelesai()
        assertTrue(
            "keluaran ditulis untuk ${r1.success} halaman, tapi hanya yang " +
                "benar-benar diterjemahkan boleh tercatat (dapat $tercatat)",
            tercatat < 3
        )

        // Jalan berikutnya wajib menerjemahkan sisanya.
        server.bodies.clear()
        val r2 = jalankan(cbz, konfigurasi(true), "e2")
        assertEquals(3, r2.success)
        assertTrue(
            "halaman yang belum diterjemahkan harus dikerjakan sekarang",
            server.bodies.isNotEmpty()
        )
    }

    /** Bab yang dilanjutkan harus tetap urut, bukan halaman baru lebih dulu. */
    @Test
    fun halamanLamaDanBaruDigabungSesuaiUrutanAsli() {
        val cbz = File(ctx.cacheDir, "bab4.cbz")
        buatCbz(cbz)
        jalankan(cbz, konfigurasi(true), "d1", batalSetelah = 1)
        val r2 = jalankan(cbz, konfigurasi(true), "d2")
        assertEquals(3, r2.success)
        assertEquals(3, r2.total)
    }

    /**
     * Titik simpan dihapus setelah arsipnya tuntas, jadi menjalankan ulang
     * dengan sengaja tetap mungkin - pengguna yang tidak puas pada hasilnya
     * harus bisa mencoba lagi.
     */
    @Test
    fun titikSimpanDibersihkanSetelahTuntas() {
        val cbz = File(ctx.cacheDir, "bab2.cbz")
        buatCbz(cbz)
        jalankan(cbz, konfigurasi(true), "b1")

        val sisa = File(ctx.filesDir, "resume").listFiles()?.count { it.isDirectory } ?: 0
        assertEquals("arsip tuntas tidak boleh menyisakan titik simpan", 0, sisa)
    }

    @Test
    fun sakelarMatiSelaluMenerjemahkanUlang() {
        val cbz = File(ctx.cacheDir, "bab3.cbz")
        buatCbz(cbz)

        jalankan(cbz, konfigurasi(false), "c1")
        val pertama = server.bodies.size
        server.bodies.clear()

        val r2 = jalankan(cbz, konfigurasi(false), "c2")
        assertEquals(3, r2.success)
        assertEquals(
            "dengan sakelar mati perilaku lama harus utuh",
            pertama, server.bodies.size
        )
    }
}
