package com.cypy.manga

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real OkHttp request/response path of the provider layer against
 * a live loopback HTTP server. Verifies the exact wire format the reference
 * Python client sends, plus error mapping (401 -> ApiKeyException).
 */
class ProviderHttpTest {

    private lateinit var server: TinyHttpServer

    private val lastBody: String get() = server.lastBody
    private val lastHeaders: Map<String, String> get() = server.lastHeaders
    private val lastQuery: String? get() = server.lastTarget().substringAfter('?', "").ifEmpty { null }

    private var responseCode: Int
        get() = server.responseCode
        set(v) { server.responseCode = v }
    private var responseBody: String
        get() = server.responseBody
        set(v) { server.responseBody = v }

    private val port: Int get() = server.port

    @Before
    fun setUp() {
        server = TinyHttpServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun url() = "http://127.0.0.1:$port/v1/chat/completions"

    private fun openAiOk(content: String) = JSONObject()
        .put("choices", listOf(
            JSONObject().put("message", JSONObject().put("content", content))
        ).let { org.json.JSONArray(it) })
        .toString()

    @Test
    fun openAiCompatSendsCorrectPayloadAndParsesContent() {
        responseCode = 200
        responseBody = openAiOk("{\"1\": \"Cepat bangun!\", \"2\": \"SKIP\"}")

        val p = OpenAICompatProvider(
            apiKey = "sk-test-123", modelName = "gpt-4o-mini",
            baseUrl = url(), providerName = "OpenAI",
            jsonMode = true, requiresKey = true
        )
        val out = p.translateBase64("QUJD", "translate this")
        assertNotNull(out)

        val parsed = JSONObject(BoxUtils.cleanJson(out!!))
        assertEquals("Cepat bangun!", parsed.getString("1"))
        assertEquals("SKIP", parsed.getString("2"))

        // ---- verify the request wire format ----
        val body = JSONObject(lastBody)
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals(0, body.getInt("temperature"))
        assertEquals(0.1, body.getDouble("top_p"), 1e-9)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))

        val msg = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        val content = msg.getJSONArray("content")
        assertEquals(2, content.length())

        val imagePart = content.getJSONObject(0)
        assertEquals("image_url", imagePart.getString("type"))
        val imageUrl = imagePart.getJSONObject("image_url")
        assertTrue(imageUrl.getString("url").startsWith("data:image/jpeg;base64,"))
        assertTrue(imageUrl.getString("url").endsWith("QUJD"))
        assertEquals("high", imageUrl.getString("detail"))

        val textPart = content.getJSONObject(1)
        assertEquals("text", textPart.getString("type"))
        assertEquals("translate this", textPart.getString("text"))

        // ---- verify headers ----
        assertEquals("Bearer sk-test-123", lastHeaders["authorization"])
        assertEquals("https://github.com/indravoyager/cypy", lastHeaders["http-referer"])
        assertEquals("cypy Manga Translator", lastHeaders["x-title"])
        assertTrue(lastHeaders["content-type"]!!.startsWith("application/json"))
    }

    @Test
    fun keylessProviderOmitsAuthorizationAndJsonMode() {
        responseCode = 200
        responseBody = openAiOk("{\"1\": \"Halo\"}")

        val p = OpenAICompatProvider(
            apiKey = "", modelName = "minimax-m3-free",
            baseUrl = url(), providerName = "Zen (opencode.ai)",
            jsonMode = false, requiresKey = false
        )
        assertTrue(p.validateApiKey())
        val out = p.translateBase64("QUJD", "prompt")
        assertEquals("{\"1\": \"Halo\"}", out)

        assertTrue("no auth header expected", lastHeaders["authorization"] == null)
        val body = JSONObject(lastBody)
        assertTrue("json mode must be off", !body.has("response_format"))
        // Zen must not send OpenAI's detail field
        val imageUrl = body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getJSONObject("image_url")
        assertTrue(!imageUrl.has("detail"))
    }

    @Test
    fun unauthorizedMapsToApiKeyException() {
        responseCode = 401
        responseBody = "{\"error\":{\"message\":\"Invalid API key\"}}"

        val p = OpenAICompatProvider(
            "bad-key", "gpt-4o-mini", url(), "OpenAI",
            jsonMode = true, requiresKey = true
        )
        try {
            p.translateBase64("QUJD", "x")
            throw AssertionError("expected ApiKeyException")
        } catch (e: ApiKeyException) {
            // expected
        }
    }

    @Test
    fun serverErrorSurfacesProviderMessage() {
        responseCode = 500
        responseBody = "{\"error\":{\"message\":\"upstream exploded\"}}"

        val p = OpenAICompatProvider(
            "k", "m", url(), "OpenAI", jsonMode = false, requiresKey = true
        )
        try {
            p.translateBase64("QUJD", "x")
            throw AssertionError("expected RuntimeException")
        } catch (e: ApiKeyException) {
            throw AssertionError("500 must not be an ApiKeyException")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("upstream exploded"))
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun geminiSendsInlineDataAndParsesCandidate() {
        responseCode = 200
        responseBody = """
            {"candidates":[{"content":{"parts":[{"text":"{\"1\": \"早く起きて！\"}"}]}}]}
        """.trimIndent()

        val p = GeminiProvider("gkey", "gemini-2.0-flash")
        // Point the provider at the local server by overriding the endpoint.
        val out = p.translateBase64ForTest("QUJD", "prompt", "http://127.0.0.1:$port/v1beta/models/x:generateContent?key=gkey")
        assertNotNull(out)
        assertEquals("{\"1\": \"早く起きて！\"}", out)

        val body = JSONObject(lastBody)
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val inline = parts.getJSONObject(0).getJSONObject("inline_data")
        assertEquals("image/jpeg", inline.getString("mime_type"))
        assertEquals("QUJD", inline.getString("data"))
        assertEquals("prompt", parts.getJSONObject(1).getString("text"))
        assertTrue(lastQuery!!.contains("key=gkey"))
    }
}
