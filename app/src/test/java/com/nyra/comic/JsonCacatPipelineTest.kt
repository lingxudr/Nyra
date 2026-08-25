package com.nyra.comic

import android.graphics.Bitmap
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
 * Cacat lapangan ronde 40 A, diuji lewat pipeline sungguhan.
 *
 * Pada uji 19 halaman, permintaan mosaik ke-4 mengembalikan JSON cacat
 * ("Expected a ':' after a key at 578"). Ke-30 balonnya dibuang tanpa
 * penyelamatan dan tanpa percobaan ulang, sehingga empat halaman keluar tanpa
 * terjemahan sama sekali - namun laporan akhir tetap "Success: 19, Failed: 0".
 *
 * Tes ini memakai server loopback yang sengaja mengirim JSON rusak pada
 * balasan PERTAMA, lalu balasan sehat sesudahnya, meniru urutan itu.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class JsonCacatPipelineTest {

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

    private fun bungkus(isi: String): String {
        val c = isi.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"content":"$c"}}]}"""
    }

    /**
     * JSON yang rusak persis seperti di lapangan: sebagian besar entri sehat,
     * satu entri kehilangan titik dua sehingga JSONObject menyerah.
     */
    private fun jsonCacat(n: Int): String {
        val sb = StringBuilder("{")
        for (i in 1..n) {
            if (i > 1) sb.append(", ")
            // Entri ke-3 dirusak; sisanya sehat dan harus bisa diselamatkan.
            if (i == 3) sb.append("\"$i\" \"Bubble $i translated\"")
            else sb.append("\"$i\": \"Bubble $i translated\"")
        }
        return sb.append("}").toString()
    }

    private fun jsonSehat(n: Int): String {
        val o = JSONObject()
        for (i in 1..n) o.put(i.toString(), "Bubble $i translated")
        return o.toString()
    }

    @Test
    fun jsonCacatTidakMenjatuhkanSeluruhPermintaan() {
        val work = File(ctx.cacheDir, "cacat").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        val detJson = JSONObject(String(resource("e2e/detections.json")))
        val byName = HashMap<String, List<IntArray>>()
        for (k in detJson.keys()) {
            val arr = detJson.getJSONArray(k)
            byName[k] = (0 until arr.length()).map { i ->
                val b = arr.getJSONArray(i)
                intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
            }
        }
        val totalBubbles = byName.values.sumOf { boxes ->
            BoxUtils.sanitize(boxes, 100000, 100000).size
        }
        assertTrue(totalBubbles > 3)

        // Balasan pertama cacat, sisanya sehat.
        var panggilan = 0
        server.responseCode = 200
        server.responseBody = bungkus(jsonCacat(totalBubbles))
        server.onRequest = {
            panggilan++
            server.responseBody = bungkus(jsonSehat(totalBubbles))
        }

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.cacheTerjemahan = false
        cfg.maxBubblesPerRequest = 100 // semua balon dalam SATU permintaan

        val outDir = File(ctx.cacheDir, "outtree-cacat").apply { mkdirs() }
        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )

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

        assertEquals("pipeline melaporkan galat:\n$log", null, result.error)

        // Yang paling penting: balon sehat di dalam JSON cacat tetap terpakai.
        assertTrue(
            "penyelamatan JSON harus tercatat di log\n$log",
            log.contains("balon diselamatkan")
        )

        // Dan tidak ada balon yang hilang diam-diam: yang tak tertolong oleh
        // penyelamatan harus dilengkapi lewat percobaan ulang.
        assertEquals(
            "tidak boleh ada balon yang hilang setelah salvage + ulang\n$log",
            0, result.balonTakTerjemah
        )
    }

    /**
     * Kalau balon memang benar-benar hilang, laporan TIDAK boleh hijau.
     * Server di sini selalu mengirim JSON tak tertolong, jadi tidak ada yang
     * bisa diselamatkan maupun diulang.
     */
    @Test
    fun balonYangHilangDilaporkanBukanDisembunyikan() {
        val work = File(ctx.cacheDir, "hilang").apply { mkdirs() }
        val cbz = File(work, "sample.cbz")
        cbz.writeBytes(resource("e2e/sample.cbz"))

        val detJson = JSONObject(String(resource("e2e/detections.json")))
        val byName = HashMap<String, List<IntArray>>()
        for (k in detJson.keys()) {
            val arr = detJson.getJSONArray(k)
            byName[k] = (0 until arr.length()).map { i ->
                val b = arr.getJSONArray(i)
                intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
            }
        }

        server.responseCode = 200
        server.responseBody = bungkus("maaf, saya tidak dapat membantu permintaan itu")

        val cfg = Config(ctx)
        cfg.provider = "custom"
        cfg.customBaseUrl = "http://127.0.0.1:${server.port}"
        cfg.minRequestDelay = 0.0f
        cfg.filterSfxAktif = false
        cfg.cacheTerjemahan = false

        val outDir = File(ctx.cacheDir, "outtree-hilang").apply { mkdirs() }
        val pipeline = Pipeline(
            ctx, cfg,
            log = { synchronized(logLines) { logLines.add(it) } },
            progress = { _, _ -> }
        )

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

        assertTrue(
            "balon yang hilang harus dihitung, bukan dilaporkan hijau\n$log",
            result.balonTakTerjemah > 0
        )
        assertTrue(
            "pengguna harus diberi tahu di log\n$log",
            log.contains("TIDAK diterjemahkan")
        )
    }
}
