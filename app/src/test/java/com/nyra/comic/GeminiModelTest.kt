package com.nyra.comic

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

/**
 * Menutup bug lapangan: Gemini membalas
 *   400 "GenerateContentRequest.model: unexpected model name format"
 * dan
 *   404 "models/gemini-2.0-flash is no longer available".
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class GeminiModelTest {

    private lateinit var server: TinyHttpServer

    @Before
    fun setUp() {
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    // ---------- normalisasi nama model ----------

    @Test
    fun modelNameIsNormalized() {
        // Persis bentuk-bentuk yang memicu "unexpected model name format".
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel(" gemini-flash-latest "))
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel("gemini-flash-latest\n"))
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel("\"gemini-flash-latest\""))
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel("models/gemini-flash-latest"))
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel("gemini-flash -latest"))
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel("\u200Bgemini-flash-latest"))
    }

    /**
     * Regresi: nama model yang diketik dengan spasi harus menjadi
     * tanda hubung, bukan disatukan. "gemini 3.7 flash" pernah berubah
     * jadi "gemini3.7flash" dan memicu HTTP 404 dari Google.
     */
    @Test
    fun spacesBecomeHyphensNotRemoved() {
        assertEquals("gemini-3.7-flash", LLMProvider.normalizeModel("gemini 3.7 flash"))
        assertEquals("gemini-3.7-flash", LLMProvider.normalizeModel("  gemini   3.7   flash  "))
        assertEquals("gemini-3.7-flash", LLMProvider.normalizeModel("gemini\t3.7\tflash"))
        assertEquals("gemini-flash-latest", LLMProvider.normalizeModel("gemini flash latest"))
        // sudah benar -> tidak berubah
        assertEquals("gemini-3.7-flash", LLMProvider.normalizeModel("gemini-3.7-flash"))
        // tanda hubung berlebih dirapikan
        assertEquals("gemini-3.7-flash", LLMProvider.normalizeModel("gemini--3.7---flash"))
        assertEquals("gemini-3.7-flash", LLMProvider.normalizeModel("-gemini-3.7-flash-"))
    }

    /**
     * Model yang diketik dengan spasi harus sampai ke endpoint dalam bentuk
     * bertanda hubung. URL dibangun dari modelName provider, sama seperti
     * jalur produksi.
     */
    @Test
    fun typedModelWithSpacesReachesEndpointAsHyphenated() {
        server.responseCode = 200
        server.responseBody =
            """{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}"""

        val p = GeminiProvider("AQ.testkey", "gemini 3.7 flash")
        assertEquals("gemini-3.7-flash", p.modelName)

        p.translateBase64ForTest(
            "QUJD", "prompt",
            "http://127.0.0.1:${server.port}/v1beta/models/${p.modelName}:generateContent"
        )
        val target = server.lastTarget()
        assertTrue("target: $target", target.contains("gemini-3.7-flash:generateContent"))
        assertFalse("target: $target", target.contains("gemini3.7flash"))
    }

    @Test
    fun providerAppliesNormalizationToItsModelName() {
        val p = GeminiProvider("k", "  models/gemini-flash-latest \n")
        assertEquals("gemini-flash-latest", p.modelName)
    }

    @Test
    fun blankModelFailsWithActionableMessage() {
        val p = GeminiProvider("k", "   ")
        try {
            p.translateBase64("QUJD", "prompt")
            throw AssertionError("harus melempar exception")
        } catch (e: RuntimeException) {
            assertTrue("pesan harus menyebut model kosong: ${e.message}",
                e.message!!.contains("empty", true))
        }
    }

    // ---------- key dikirim sebagai header ----------

    @Test
    fun apiKeyIsSentAsHeaderNotQueryString() {
        server.responseCode = 200
        server.responseBody =
            """{"candidates":[{"content":{"parts":[{"text":"{\"1\":\"Halo\"}"}]}}]}"""

        val p = GeminiProvider("AQ.Ab8RN6Iexample", "gemini-flash-latest")
        val out = p.translateBase64ForTest(
            "QUJD", "prompt", "http://127.0.0.1:${server.port}/v1beta/models/x:generateContent"
        )
        assertNotNull(out)

        assertEquals("AQ.Ab8RN6Iexample", server.lastHeaders["x-goog-api-key"])
        assertTrue("key tidak boleh bocor ke URL: ${server.lastTarget()}",
            !server.lastTarget().contains("AQ.Ab8RN6Iexample"))
    }

    // ---------- pesan error yang menuntun ----------

    @Test
    fun retiredModelErrorExplainsTheFix() {
        server.responseCode = 404
        server.responseBody = """
            {"error":{"code":404,"message":"This model models/gemini-2.0-flash is no longer available. Please update your code.","status":"NOT_FOUND"}}
        """.trimIndent()

        val p = GeminiProvider("k", "gemini-2.0-flash")
        try {
            p.translateBase64ForTest("QUJD", "x",
                "http://127.0.0.1:${server.port}/v1beta/models/y:generateContent")
            throw AssertionError("harus melempar exception")
        } catch (e: RuntimeException) {
            val m = e.message!!
            assertTrue("harus menyarankan model pengganti: $m", m.contains("gemini-flash-latest"))
        }
    }

    @Test
    fun badModelFormatErrorExplainsTheFix() {
        server.responseCode = 400
        server.responseBody = """
            {"error":{"code":400,"message":"* GenerateContentRequest.model: unexpected model name format","status":"INVALID_ARGUMENT"}}
        """.trimIndent()

        val p = GeminiProvider("k", "Google Gemini")
        try {
            p.translateBase64ForTest("QUJD", "x",
                "http://127.0.0.1:${server.port}/v1beta/models/y:generateContent")
            throw AssertionError("harus melempar exception")
        } catch (e: RuntimeException) {
            assertTrue("harus menjelaskan format model: ${e.message}",
                e.message!!.contains("gemini-flash-latest"))
        }
    }

    // ---------- migrasi setelan tersimpan ----------

    @Test
    fun savedRetiredModelIsMigratedAutomatically() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = Config(ctx)

        // Simulasi setelan lama pengguna yang sudah terlanjur tersimpan.
        cfg.setModel("gemini", "gemini-2.0-flash")
        assertEquals("gemini-flash-latest", cfg.model("gemini"))

        // Migrasi harus persisten, bukan cuma di memori.
        assertEquals("gemini-flash-latest", Config(ctx).model("gemini"))

        // Model yang dipilih sendiri oleh pengguna tidak boleh diubah.
        cfg.setModel("gemini", "gemini-2.5-pro")
        assertEquals("gemini-2.5-pro", cfg.model("gemini"))
    }

    @Test
    fun defaultGeminiModelIsNotARetiredOne() {
        val default = Providers.REGISTRY["gemini"]!!.defaultModel
        assertTrue("default '$default' tidak boleh model yang sudah mati",
            default !in Config.RETIRED_GEMINI_MODELS)
    }

    // ---------- payload tetap benar ----------

    @Test
    fun requestBodyStillCarriesImageAndPrompt() {
        server.responseCode = 200
        server.responseBody =
            """{"candidates":[{"content":{"parts":[{"text":"{\"1\":\"Halo\"}"}]}}]}"""

        val p = GeminiProvider("k", "gemini-flash-latest")
        p.translateBase64ForTest("QUJD", "terjemahkan",
            "http://127.0.0.1:${server.port}/v1beta/models/y:generateContent")

        val parts = JSONObject(server.lastBody)
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inline_data").getString("mime_type"))
        assertEquals("QUJD", parts.getJSONObject(0).getJSONObject("inline_data").getString("data"))
        assertEquals("terjemahkan", parts.getJSONObject(1).getString("text"))
    }
}
