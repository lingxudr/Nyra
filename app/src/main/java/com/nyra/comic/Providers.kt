package com.nyra.comic

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ApiKeyException(msg: String = "API_KEY_ERROR") : Exception(msg)

/** Port of the clients in cypy/core/providers. */
abstract class LLMProvider(val apiKey: String, modelName: String) {

    /**
     * Nama model yang sudah dibersihkan. Input dari UI sering membawa spasi,
     * newline, tanda kutip, atau prefix "models/" yang membuat Gemini menolak
     * request dengan "unexpected model name format".
     */
    val modelName: String = normalizeModel(modelName)

    abstract val providerName: String

    /**
     * Pemakaian token panggilan terakhir yang berhasil, DIPISAH PER THREAD.
     *
     * Diisi dari badan respons, bukan ditaksir. Nilai awal KOSONG
     * (tanpaData = true) supaya panggilan yang gagal tidak ikut dihitung
     * sebagai nol token yang seolah-olah pasti.
     *
     * Ronde 26 — pass 2 sekarang mengirim beberapa request bersamaan. Kalau
     * nilai ini satu variabel bersama, request B bisa menimpanya di antara
     * saat request A kembali dan saat pipeline membacanya, sehingga token
     * milik A hilang dan token milik B dihitung dua kali. Menyimpannya per
     * thread membuat tiap request membaca angkanya sendiri; penjumlahan
     * totalnya tetap dilakukan Usage.Penghitung yang @Synchronized.
     */
    private val pemakaianThread = ThreadLocal.withInitial { Usage.Pakai.KOSONG }

    var pemakaianTerakhir: Usage.Pakai
        get() = pemakaianThread.get() ?: Usage.Pakai.KOSONG
        protected set(v) { pemakaianThread.set(v) }

    /** Catat pemakaian dari badan respons mentah. */
    protected fun catatPemakaian(badan: String?) {
        pemakaianTerakhir = Usage.dariJson(badan)
    }

    /**
     * Model yang benar-benar melayani panggilan terakhir — per thread,
     * dengan alasan yang sama seperti [pemakaianTerakhir]: tarif model cadangan
     * berbeda, jadi biaya harus dihitung dengan nama model yang melayani
     * request INI, bukan request tetangga yang kebetulan selesai belakangan.
     */
    private val modelThread = ThreadLocal.withInitial { modelName }

    var modelTerakhir: String
        get() = modelThread.get() ?: modelName
        protected set(v) { modelThread.set(v) }

    open fun validateApiKey(): Boolean = apiKey.isNotBlank()

    /** Returns raw model text (expected to be JSON). */
    abstract fun translateImage(image: Bitmap, prompt: String): String?

    /**
     * Kirim mosaik bernomor DITAMBAH satu gambar rujukan (halaman utuh).
     *
     * Gambar rujukan membantu model melihat ekspresi wajah, siapa yang bicara,
     * dan tata letak panel - konteks yang hilang kalau ia hanya menerima
     * potongan balon. Urutannya penting: mosaik selalu gambar PERTAMA, karena
     * hanya di situ ada nomor ID merah yang harus dijawab.
     *
     * Bila [reference] null, perilakunya sama persis dengan [translateImage].
     */
    open fun translateWithReference(image: Bitmap, reference: Bitmap?, prompt: String): String? =
        if (reference == null) translateImage(image, prompt)
        else translateImages(listOf(image, reference), prompt)

    /** Implementasi multi-gambar; hanya dipanggil bila ada gambar rujukan. */
    protected open fun translateImages(images: List<Bitmap>, prompt: String): String? =
        translateImage(images.first(), prompt)

    companion object {

        /**
         * Rapikan nama model: buang kutip/zero-width/prefix "models/".
         *
         * Whitespace internal diubah menjadi "-" (BUKAN dihapus): ID model
         * memakai tanda hubung, sehingga "gemini 3.7 flash" harus menjadi
         * "gemini-3.7-flash". Menghapus spasi menghasilkan "gemini3.7flash"
         * yang tidak pernah ada dan berujung HTTP 404.
         */
        fun normalizeModel(raw: String): String {
            var m = raw.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .trim()
            m = m.replace(Regex("\\s+"), "-")
            m = m.replace(Regex("-{2,}"), "-").trim('-')
            if (m.startsWith("models/")) m = m.removePrefix("models/")
            return m
        }

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val JSON = "application/json; charset=utf-8".toMediaType()

        fun toBase64(bmp: Bitmap): String {
            val bos = ByteArrayOutputStream()
            // JPEG q92 keeps mosaics small enough for mobile uploads while staying legible.
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }

        fun create(cfg: Config): LLMProvider {
            val p = cfg.provider
            val key = cfg.apiKey(p)
            val model = cfg.model(p)
            return when (p) {
                "gemini" -> GeminiProvider(key, model)
                "openai" -> OpenAICompatProvider(
                    key, model, "https://api.openai.com/v1/chat/completions", "OpenAI",
                    jsonMode = true, requiresKey = true
                )
                "zen" -> OpenAICompatProvider(
                    key, model, "https://opencode.ai/zen/v1/chat/completions", "Zen (opencode.ai)",
                    jsonMode = false, requiresKey = false
                )
                "opencodego" -> OpenAICompatProvider(
                    key, model, "https://opencode.ai/zen/go/v1/chat/completions", "OpenCode Go",
                    jsonMode = false, requiresKey = true
                )
                "openrouter" -> OpenAICompatProvider(
                    key, model, "https://openrouter.ai/api/v1/chat/completions", "OpenRouter",
                    jsonMode = false, requiresKey = true
                )
                else -> {
                    var base = cfg.customBaseUrl.trim().trimEnd('/')
                    if (base.isEmpty()) throw RuntimeException("Custom provider base URL is not configured.")
                    if (!base.endsWith("/chat/completions")) base = "$base/v1/chat/completions"
                    OpenAICompatProvider(key, model, base, "Custom", jsonMode = false, requiresKey = false)
                }
            }
        }
    }
}

/** Google Gemini via generativelanguage REST API. */
class GeminiProvider(apiKey: String, modelName: String) : LLMProvider(apiKey, modelName) {

    override val providerName = "Google Gemini"

    /**
     * Model cadangan bila model utama menjawab 503 "high demand".
     * Beban tiap model berbeda, jadi pindah model biasanya langsung berhasil.
     */
    private fun fallbacksFor(primary: String): List<String> =
        listOf("gemini-flash-latest", "gemini-3.6-flash", "gemini-flash-lite-latest")
            .filter { it != primary }

    override fun translateImage(image: Bitmap, prompt: String): String? {
        val b64 = toBase64(image)
        return try {
            translateBase64(b64, prompt)
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            val overloaded = msg.contains("503") || msg.contains("high demand") ||
                    msg.contains("unavailable")
            // 429 pada Gemini biasanya kuota per-model, bukan per-akun, jadi
            // model lain sering masih bisa dipakai. Mencoba cadangan lebih dulu
            // jauh lebih cepat daripada menunggu backoff satu menit.
            val quota = msg.contains("429") || msg.contains("too many requests") ||
                    msg.contains("rate limit") || msg.contains("quota")
            if (!overloaded && !quota) throw e
            for (alt in fallbacksFor(modelName)) {
                try {
                    val cadangan = GeminiProvider(apiKey, alt)
                    val hasil = cadangan.translateBase64(b64, prompt)
                    // Pemakaian tercatat pada instance cadangan; salin ke sini
                    // supaya Pipeline tetap melihatnya lewat provider yang dia
                    // pegang, dan token model cadangan tidak hilang dari total.
                    pemakaianTerakhir = cadangan.pemakaianTerakhir
                    modelTerakhir = alt
                    return hasil
                } catch (_: Exception) {
                    // model ini juga penuh/kehabisan kuota, lanjut ke berikutnya
                }
            }
            throw e
        }
    }

    /** Testable core: same request/response path, without Android bitmap encoding. */
    fun translateBase64(imageB64: String, prompt: String): String? =
        translateBase64ForTest(imageB64, prompt, null)

    fun translateBase64(imagesB64: List<String>, prompt: String): String? =
        translateBase64ForTest(imagesB64, prompt, null)

    override fun translateImages(images: List<Bitmap>, prompt: String): String? {
        val b64 = images.map { toBase64(it) }
        return try {
            translateBase64(b64, prompt)
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            val overloaded = msg.contains("503") || msg.contains("high demand") ||
                    msg.contains("unavailable")
            val quota = msg.contains("429") || msg.contains("too many requests") ||
                    msg.contains("rate limit") || msg.contains("quota")
            if (!overloaded && !quota) throw e
            for (alt in fallbacksFor(modelName)) {
                try {
                    val cadangan = GeminiProvider(apiKey, alt)
                    val hasil = cadangan.translateBase64(b64, prompt)
                    pemakaianTerakhir = cadangan.pemakaianTerakhir
                    modelTerakhir = alt
                    return hasil
                } catch (_: Exception) {
                }
            }
            throw e
        }
    }

    /**
     * Verifikasi satu model dengan request generateContent super kecil.
     *
     * ListModels tidak bisa dipercaya: model seperti gemini-2.5-flash tetap
     * terdaftar padahal menjawab 404 "no longer available to new users".
     * Hanya panggilan sungguhan yang membuktikan model bisa dipakai.
     *
     * @return null bila model bisa dipakai, atau alasan singkat bila tidak.
     *   503/429 dianggap BISA dipakai (model ada, server sedang sibuk).
     */
    fun probeModel(model: String, endpointOverride: String? = null): String? {
        val m = normalizeModel(model)
        if (m.isBlank()) return "nama model kosong"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", "hi")))
            }))
            put("generationConfig", JSONObject().put("maxOutputTokens", 1))
        }
        val url = endpointOverride
            ?: "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
        val req = Request.Builder()
            .url(url)
            .addHeader("X-goog-api-key", apiKey.trim())
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> null
                    // model ada, cuma sedang penuh / kena limit
                    resp.code == 503 || resp.code == 429 -> null
                    else -> {
                        val t = resp.body?.string().orEmpty()
                        val msg = runCatching {
                            JSONObject(t).getJSONObject("error").optString("message")
                        }.getOrDefault("HTTP ${resp.code}")
                        "HTTP ${resp.code}: ${msg.take(120)}"
                    }
                }
            }
        } catch (e: Exception) {
            "gagal menghubungi: ${e.message?.take(80)}"
        }
    }

    /**
     * Daftar model yang mendukung generateContent untuk key ini.
     * Dipakai hanya untuk memperkaya pesan error 404.
     */
    fun listVisionModels(endpointOverride: String? = null): List<String> {
        val url = endpointOverride
            ?: "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
        val req = Request.Builder()
            .url(url)
            .addHeader("X-goog-api-key", apiKey.trim())
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string().orEmpty()
            val out = ArrayList<String>()
            runCatching {
                val arr = JSONObject(text).getJSONArray("models")
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var ok = false
                    if (methods != null) {
                        for (j in 0 until methods.length())
                            if (methods.optString(j) == "generateContent") ok = true
                    }
                    if (ok) out.add(m.optString("name").removePrefix("models/"))
                }
            }
            return out
        }
    }

    /** @param endpointOverride non-null only in tests, to target a loopback server. */
    fun translateBase64ForTest(imageB64: String, prompt: String, endpointOverride: String?): String? =
        translateBase64ForTest(listOf(imageB64), prompt, endpointOverride)

    fun translateBase64ForTest(
        imagesB64: List<String>, prompt: String, endpointOverride: String?
    ): String? {
        if (!validateApiKey()) throw ApiKeyException()

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    // Semua gambar mendahului teks; mosaik bernomor selalu yang
                    // pertama supaya rujukan tidak pernah disangka sumber ID.
                    for (b64 in imagesB64) {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            })
                        })
                    }
                    put(JSONObject().put("text", prompt))
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("topP", 0.1)
                put("topK", 1)
                put("responseMimeType", "application/json")
                put("maxOutputTokens", 8192)
            })
            put("safetySettings", JSONArray().apply {
                for (cat in listOf(
                    "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
                )) {
                    put(JSONObject().apply {
                        put("category", cat)
                        put("threshold", "BLOCK_NONE")
                    })
                }
            })
        }

        if (modelName.isBlank()) {
            throw RuntimeException(
                "Model name for Gemini is empty. Set one, e.g. gemini-flash-latest."
            )
        }

        // Key dikirim lewat header: lebih aman (tidak bocor ke log URL) dan
        // mendukung format key baru (AQ....) maupun lama (AIza...).
        val url = endpointOverride
            ?: "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"

        val req = Request.Builder()
            .url(url)
            .addHeader("X-goog-api-key", apiKey.trim())
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 400 || resp.code == 401 || resp.code == 403) {
                if (text.contains("API key", true) || text.contains("API_KEY", true))
                    throw ApiKeyException()
            }
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).getJSONObject("error").optString("message")
                }.getOrDefault(text.take(200))
                val hint = when {
                    resp.code == 404 && detail.contains("no longer available", true) ->
                        " -> Model '$modelName' sudah dihentikan Google. Ganti ke gemini-flash-latest di kolom Model."
                    resp.code == 404 && detail.contains("is not found", true) -> {
                        // Model tidak ada: tanyakan daftar asli ke Google supaya
                        // pengguna langsung tahu ejaan yang benar.
                        val avail = runCatching { listVisionModels() }.getOrDefault(emptyList())
                        val near = avail.filter { it.replace("-", "") == modelName.replace("-", "").lowercase() }
                        if (near.isNotEmpty())
                            " -> Maksud Anda '${near.first()}'? Perbaiki ejaan di kolom Model."
                        else if (avail.isNotEmpty())
                            " -> Model '$modelName' tidak ada. Yang tersedia untuk key Anda: " +
                                avail.take(6).joinToString(", ") + "."
                        else
                            " -> Model '$modelName' tidak ada. Coba gemini-flash-latest."
                    }
                    resp.code == 400 && detail.contains("unexpected model name format", true) ->
                        " -> Nama model '$modelName' tidak valid. Contoh yang benar: gemini-flash-latest."
                    else -> ""
                }
                throw RuntimeException("Gemini API error ${resp.code}: $detail$hint")
            }
            catatPemakaian(text)
            return runCatching {
                val cand = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                val parts = cand.getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                sb.toString()
            }.getOrElse {
                throw RuntimeException("Unexpected Gemini response: ${text.take(200)}")
            }
        }
    }
}

/** OpenAI-compatible chat/completions provider (OpenAI, Zen, OpenCode Go, OpenRouter, Custom). */
class OpenAICompatProvider(
    apiKey: String,
    modelName: String,
    private val baseUrl: String,
    override val providerName: String,
    private val jsonMode: Boolean,
    private val requiresKey: Boolean
) : LLMProvider(apiKey, modelName) {

    override fun validateApiKey(): Boolean = if (!requiresKey) true else apiKey.isNotBlank()

    override fun translateImage(image: Bitmap, prompt: String): String? =
        translateBase64(toBase64(image), prompt)

    override fun translateImages(images: List<Bitmap>, prompt: String): String? =
        translateBase64(images.map { toBase64(it) }, prompt)

    /** Testable core: same request/response path, without Android bitmap encoding. */
    fun translateBase64(imageB64: String, prompt: String): String? =
        translateBase64(listOf(imageB64), prompt)

    fun translateBase64(imagesB64: List<String>, prompt: String): String? {
        if (requiresKey && apiKey.isBlank()) throw ApiKeyException()

        val content = JSONArray().apply {
            // Mosaik bernomor lebih dulu, lalu gambar rujukan bila ada.
            for (b64 in imagesB64) {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64," + b64)
                        if (providerName == "OpenAI") put("detail", "high")
                    })
                })
            }
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }

        val payload = JSONObject().apply {
            put("model", modelName)
            put("temperature", 0)
            put("top_p", 0.1)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }

        val builder = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/indravoyager/cypy")
            .addHeader("X-Title", "NYRA AI Comic Translation")
            .post(payload.toString().toRequestBody(JSON))

        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${apiKey.trim()}")

        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw ApiKeyException()
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).getJSONObject("error").optString("message")
                }.getOrDefault(text.take(200))
                throw RuntimeException("$providerName API error ${resp.code}: $detail")
            }
            catatPemakaian(text)
            return runCatching {
                JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            }.getOrElse {
                throw RuntimeException("Unexpected $providerName response format")
            }
        }
    }
}
