package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import java.io.File

/**
 * Full pipeline run on a real CBZ archive.
 *
 * Real: zip extraction, bitmap decode, crop/mask geometry, mosaic stitching,
 * OkHttp request to a live loopback server, JSON parsing, Canvas text
 * rendering, PNG + PDF writing.
 *
 * Substituted: the ONNX forward pass (native libs are Android-only) is replayed
 * from detections captured by running the REAL eyecypy.onnx model on these exact
 * pages, and the LLM endpoint is a local server returning fixed translations.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class EndToEndPipelineTest {

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

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name).readBytes()

    /** Translations keyed by the mosaic's red bubble IDs. */
    private fun replyWith(n: Int) {
        val o = JSONObject()
        for (i in 1..n) o.put(i.toString(), "Bubble $i translated")
        val content = o.toString().replace("\"", "\\\"")
        server.responseCode = 200
        server.responseBody =
            """{"choices":[{"message":{"content":"$content"}}]}"""
    }

    @Test
    fun translatesRealCbzEndToEnd() {
        // ---- stage the real archive on disk ----
        val work = File(ctx.cacheDir, "e2e").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))
        assertTrue("fixture archive must exist", cbz.length() > 1000)

        // ---- real detections captured from the real ONNX model ----
        val detJson = JSONObject(String(resource("e2e/detections.json")))
        val byName = HashMap<String, List<IntArray>>()
        for (k in detJson.keys()) {
            val arr = detJson.getJSONArray(k)
            byName[k] = (0 until arr.length()).map { i ->
                val b = arr.getJSONArray(i)
                intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
            }
        }
        // detections.json ini ditangkap dari model ONNX sungguhan, dan justru
        // memuat contoh bug ronde 11: page_2.png berisi dua kotak [0,616,0,1100]
        // yang lebarnya NOL. Pipeline membuangnya di BoxUtils.sanitize, jadi
        // yang diharapkan adalah jumlah kotak yang SAH, bukan jumlah mentah.
        val totalMentah = byName.values.sumOf { it.size }
        val totalBubbles = byName.values.sumOf { boxes ->
            BoxUtils.sanitize(boxes, 100000, 100000).size
        }
        assertTrue("fixtures must contain detections", totalBubbles > 0)
        assertTrue(
            "fixture harus memuat kotak cacat supaya regresi ini teruji",
            totalMentah > totalBubbles
        )
        replyWith(totalBubbles)

        // ---- configure ----
        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false           // SFX filter needs pixel stats, not part of this check
        cfg.maxBubblesPerRequest = 20

        val outDir = File(ctx.cacheDir, "outtree").apply { mkdirs() }

        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )

        // Replay real model output, matched by page dimensions -> order of extraction.
        val order = byName.keys.sorted()
        var call = 0
        pipeline.detectorOverride = { _: Bitmap ->
            val key = order[minOf(call, order.size - 1)]
            call++
            byName[key]!!
        }
        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )

        val result = pipeline.run(
            inputs = listOf(Uri.fromFile(cbz)),
            targetLanguage = "indonesian",
            outputTree = Uri.fromFile(outDir)
        )
        pipeline.close()

        val log = logLines.joinToString("\n")

        // ---- assertions on the real outcome ----
        assertEquals("pipeline reported an error:\n$log", null, result.error)
        assertEquals("all 3 pages should succeed\n$log", 3, result.success)
        assertEquals(0, result.failed)
        assertEquals(3, result.total)

        assertTrue("mosaic batching must run\n$log",
            log.contains("[Multi-Page Batch] Extracted $totalBubbles speech bubbles across 3 pages."))
        assertTrue("a translation request must be issued\n$log",
            Regex("\\[Request 1/\\d+] Translating \\d+ bubbles with Custom").containsMatchIn(log))
        assertTrue("archive summary must be logged\n$log",
            log.contains("[Archive] Completed! Success: 3, Failed: 0, Total: 3"))

        // ---- a real PDF was produced ----
        assertEquals(1, result.outputs.size)
        val item = result.outputs[0]
        assertEquals("sample.pdf", item.name)
        val pdf = File(item.localPath!!)
        assertTrue("output pdf must exist", pdf.exists())
        assertTrue("PDF combine step must run\n$log", log.contains("Combining translated images into PDF"))
        // NOTE: android.graphics.pdf.PdfDocument is a no-op stub under Robolectric, so the
        // file has no bytes here. Real PDF bytes are covered on-device; page rendering
        // itself is asserted pixel-wise in rendersTranslatedTextOntoLooseImages().

        // ---- the request actually carried a stitched mosaic image ----
        val sentBody = JSONObject(server.lastBody)
        val imgUrl = sentBody.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)
            .getJSONObject("image_url").getString("url")
        assertTrue(imgUrl.startsWith("data:image/jpeg;base64,"))
        val b64 = imgUrl.removePrefix("data:image/jpeg;base64,")
        val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val mosaic = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        assertNotNull("mosaic must decode as an image", mosaic)
        assertTrue("mosaic should be tall (stacked bubbles): ${mosaic.width}x${mosaic.height}",
            mosaic.height > mosaic.width)

        println("E2E OK: $totalBubbles bubbles across 3 pages, mosaic=${mosaic.width}x${mosaic.height}")
    }

    @Test
    fun apiKeyFailureIsReportedNotSilentlySwallowed() {
        val work = File(ctx.cacheDir, "e2e2").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        server.responseCode = 401
        server.responseBody = """{"error":{"message":"Invalid API key"}}"""

        val cfg = Config(ctx)
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false

        val pipeline = Pipeline(ctx, cfg, log = { logLines.add(it) }, progress = { _, _ -> })
        pipeline.detectorOverride = { bmp -> listOf(intArrayOf(40, 40, 260, 150)) }
        pipeline.providerOverride = OpenAICompatProvider(
            "bad", "m", "http://127.0.0.1:${server.port}/v1/chat/completions",
            "OpenAI", jsonMode = false, requiresKey = true
        )

        val outDir = File(ctx.cacheDir, "outtree2").apply { mkdirs() }
        val result = pipeline.run(listOf(Uri.fromFile(cbz)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()

        assertNotNull("an invalid key must surface as an error", result.error)
        assertTrue("error should mention the key: ${result.error}",
            result.error!!.contains("key", ignoreCase = true))
    }

    /**
     * Teks di luar balon ikut diterjemahkan lewat detektor teks tambahan.
     *
     * Model balon hanya melaporkan satu kotak; detektor teks menemukan dua
     * wilayah lagi di luar balon itu. Ketiganya harus sampai ke penerjemah.
     */
    @Test
    fun looseTextOutsideBubblesIsAlsoTranslated() {
        val work = File(ctx.cacheDir, "ocrloose").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.ocrTeksLepas = true

        replyWith(40)

        val pipeline = Pipeline(ctx, cfg, log = { logLines.add(it) }, progress = { _, _ -> })
        pipeline.detectorOverride = { _: Bitmap -> listOf(intArrayOf(40, 40, 200, 120)) }
        // Satu di dalam balon (harus dibuang), dua di luar (harus dipakai).
        pipeline.textDetectorOverride = { _: Bitmap ->
            listOf(
                intArrayOf(60, 60, 180, 100),
                intArrayOf(40, 200, 240, 260),
                intArrayOf(40, 300, 240, 360)
            )
        }
        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )

        val outDir = File(ctx.cacheDir, "ocrtree").apply { mkdirs() }
        val result = pipeline.run(listOf(Uri.fromFile(cbz)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()

        assertNull("jalannya harus mulus: ${result.error}", result.error)
        val catatan = logLines.joinToString("\n")
        assertTrue(
            "log harus menyebut teks di luar balon:\n$catatan",
            catatan.contains("teks di luar balon")
        )
    }

    /** Sakelar mati = perilaku lama persis, tidak ada kotak tambahan. */
    @Test
    fun looseTextDetectionCanBeTurnedOff() {
        val work = File(ctx.cacheDir, "ocroff").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.ocrTeksLepas = false

        replyWith(40)

        var ocrDipanggil = false
        val pipeline = Pipeline(ctx, cfg, log = { logLines.add(it) }, progress = { _, _ -> })
        pipeline.detectorOverride = { _: Bitmap -> listOf(intArrayOf(40, 40, 200, 120)) }
        pipeline.textDetectorOverride = { _: Bitmap ->
            ocrDipanggil = true
            listOf(intArrayOf(40, 300, 240, 360))
        }
        pipeline.providerOverride = OpenAICompatProvider(
            apiKey = "", modelName = "test-model",
            baseUrl = "http://127.0.0.1:${server.port}/v1/chat/completions",
            providerName = "Custom", jsonMode = false, requiresKey = false
        )

        val outDir = File(ctx.cacheDir, "ocrofftree").apply { mkdirs() }
        pipeline.run(listOf(Uri.fromFile(cbz)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()

        assertTrue("detektor teks tidak boleh dipanggil saat sakelar mati", !ocrDipanggil)
    }

    /**
     * Regression for the 59-page report: rate-limited, user presses Stop.
     *
     * Cancelling mid-run must NOT throw the chapter away. Every page has to be
     * accounted for as a success (translated pages carry their text, the rest
     * pass through in the original language) and the summary must say so
     * instead of reporting "Success: 0, Failed: 59".
     */
    @Test
    fun stoppingMidRunStillSavesEveryPage() {
        val work = File(ctx.cacheDir, "cancel").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false

        replyWith(40)

        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        pipeline.providerOverride = OpenAICompatProvider(
            "", "m", "http://127.0.0.1:${server.port}/v1/chat/completions",
            "Custom", jsonMode = false, requiresKey = false
        )
        // Stop is pressed while the first page is being detected.
        var seen = 0
        pipeline.detectorOverride = { _: Bitmap ->
            seen++
            if (seen >= 1) pipeline.cancel()
            listOf(intArrayOf(40, 40, 260, 150))
        }

        val outDir = File(ctx.cacheDir, "outtree_cancel").apply { mkdirs() }
        val result = pipeline.run(listOf(Uri.fromFile(cbz)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()
        val log = logLines.joinToString("\n")

        assertEquals("cancelling is not an error\n$log", null, result.error)
        assertEquals("every page must still be accounted for\n$log", 3, result.total)
        assertEquals("no page may be reported as failed after Stop\n$log", 0, result.failed)
        assertEquals("all pages must be saved\n$log", 3, result.success)
        assertTrue("user must be told the run was stopped, not that it failed\n$log",
            log.contains("[Stopped]"))
        assertEquals("the chapter PDF must still be written", 1, result.outputs.size)
    }

    /**
     * A page where the model finds no speech bubbles is a valid, successful
     * page — it is simply copied through. It must never inflate the failure
     * count.
     */
    @Test
    fun pagesWithoutBubblesCountAsSuccess() {
        val work = File(ctx.cacheDir, "nobubble").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false

        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )
        pipeline.providerOverride = OpenAICompatProvider(
            "", "m", "http://127.0.0.1:${server.port}/v1/chat/completions",
            "Custom", jsonMode = false, requiresKey = false
        )
        pipeline.detectorOverride = { _: Bitmap -> emptyList() }

        val outDir = File(ctx.cacheDir, "outtree_nb").apply { mkdirs() }
        val result = pipeline.run(listOf(Uri.fromFile(cbz)), "indonesian", Uri.fromFile(outDir))
        pipeline.close()
        val log = logLines.joinToString("\n")

        assertEquals(null, result.error)
        assertEquals(3, result.total)
        assertEquals("art-only pages are not failures\n$log", 0, result.failed)
        assertEquals(3, result.success)
    }

    /**
     * Loose-image path: asserts the pipeline writes real PNGs whose pixels were
     * actually modified by the text renderer inside the detected bubble.
     */
    @Test
    fun rendersTranslatedTextOntoLooseImages() {
        val work = File(ctx.cacheDir, "loose").apply { mkdirs() }

        // A plain white page with one dark-outlined bubble region.
        val src = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }
        c.drawRect(80f, 100f, 480f, 320f, p)
        val srcFile = File(work, "page.png")
        srcFile.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val box = intArrayOf(90, 110, 470, 310)

        server.responseCode = 200
        server.responseBody =
            """{"choices":[{"message":{"content":"{\"1\": \"HALO DUNIA\"}"}}]}"""

        val cfg = Config(ctx)
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false

        val pipeline = Pipeline(ctx, cfg, log = { logLines.add(it) }, progress = { _, _ -> })
        pipeline.detectorOverride = { listOf(box) }
        pipeline.providerOverride = OpenAICompatProvider(
            "", "m", "http://127.0.0.1:${server.port}/v1/chat/completions",
            "Custom", jsonMode = false, requiresKey = false
        )

        val outDir = File(ctx.cacheDir, "outtree3").apply { mkdirs() }
        val result = pipeline.run(
            listOf(Uri.fromFile(srcFile)), "indonesian", Uri.fromFile(outDir)
        )
        pipeline.close()
        val log = logLines.joinToString("\n")

        assertEquals("error:\n$log", null, result.error)
        assertEquals(1, result.success)
        assertEquals(1, result.outputs.size)

        // The PNG must have landed in <tree>/ID/ per the reference layout.
        val written = File(File(outDir, "ID"), "page.png")
        assertTrue("expected output at ID/page.png\n$log", written.exists())
        assertTrue("output png must have bytes", written.length() > 500)

        val outBmp = BitmapFactory.decodeFile(written.absolutePath)
        assertNotNull(outBmp)
        assertEquals(600, outBmp.width)
        assertEquals(800, outBmp.height)

        // Count non-white pixels inside the bubble: the renderer paints a white
        // rounded patch then black text, so glyph pixels must be present.
        var dark = 0
        for (y in box[1] + 20 until box[3] - 20) {
            for (x in box[0] + 20 until box[2] - 20) {
                val px = outBmp.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (r < 100 && g < 100 && b < 100) dark++
            }
        }
        assertTrue("expected rendered glyph pixels inside the bubble, found $dark", dark > 200)
        println("RENDER OK: $dark glyph pixels drawn inside bubble")
    }
}
