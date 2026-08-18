package com.nyra.comic

import android.content.Context
import android.content.SharedPreferences

/**
 * Port of cypy/core/config.py — single source of truth for providers,
 * languages and tweakable pipeline parameters.
 */

data class ProviderMeta(
    val key: String,
    val displayName: String,
    val defaultModel: String,
    val url: String = "",
    val desc: String = "",
    val requiresKey: Boolean = true
)

object Providers {

    /**
     * Saran model vision per provider (ditawarkan di dropdown, pengguna tetap
     * boleh mengetik model lain). Untuk Gemini dipakai alias "-latest" agar
     * tidak ikut mati saat Google memensiunkan versi tertentu.
     */
    val MODEL_SUGGESTIONS: Map<String, List<String>> = mapOf(
        "gemini" to listOf(
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-pro-latest",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        ),
        "openai" to listOf("gpt-4o-mini", "gpt-4o"),
        "zen" to listOf("minimax-m3-free"),
        "opencodego" to listOf("mimo-v2.5"),
        "openrouter" to listOf(
            "qwen/qwen2.5-vl-72b-instruct:free",
            "google/gemini-flash-1.5"
        ),
        "custom" to listOf("gpt-4o-mini")
    )

    val REGISTRY: LinkedHashMap<String, ProviderMeta> = linkedMapOf(
        "gemini" to ProviderMeta(
            // Alias "-latest" tidak ikut mati saat Google pensiunkan versi lama.
            "gemini", "Google Gemini", "gemini-flash-latest",
            "https://aistudio.google.com/", "Free tier available"
        ),
        "openai" to ProviderMeta(
            "openai", "OpenAI", "gpt-4o-mini",
            "https://platform.openai.com/api-keys", "GPT-4o, GPT-4o-mini"
        ),
        "zen" to ProviderMeta(
            "zen", "Zen (opencode.ai)", "minimax-m3-free",
            "https://opencode.ai/auth", "Free models, optional API key", requiresKey = false
        ),
        "opencodego" to ProviderMeta(
            "opencodego", "OpenCode Go", "mimo-v2.5",
            "https://opencode.ai/auth", "API key required"
        ),
        "openrouter" to ProviderMeta(
            "openrouter", "OpenRouter", "qwen/qwen2.5-vl-72b-instruct:free",
            "https://openrouter.ai/keys", "Access 100+ vision models"
        ),
        "custom" to ProviderMeta(
            "custom", "Custom", "gpt-4o-mini",
            "", "OpenAI-compatible API, custom base URL", requiresKey = false
        )
    )

    val displayNames: List<String> get() = REGISTRY.values.map { it.displayName }
    fun byDisplayName(name: String): ProviderMeta =
        REGISTRY.values.firstOrNull { it.displayName == name } ?: REGISTRY["gemini"]!!
}

object Langs {
    val LANG_CODES = mapOf(
        "english" to "en", "indonesian" to "id", "spanish" to "es",
        "portuguese" to "pt", "javanese" to "jv", "japanese" to "jp",
        "korean" to "kr", "mandarin" to "cn", "chinese" to "cn",
        "thai" to "th", "vietnamese" to "vi", "russian" to "ru",
        "arabic" to "ar", "hindi" to "hi", "malay" to "ms", "tagalog" to "tl"
    )

    val CHOICES = listOf(
        "Indonesian", "English", "Japanese", "Mandarin",
        "Spanish", "Portuguese", "Javanese", "Korean", "Russian", "Thai"
    )

    fun code(lang: String): String =
        LANG_CODES[lang.lowercase()] ?: lang.take(2).lowercase()
}

class Config internal constructor(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("nyra_settings", Context.MODE_PRIVATE)

    // ---- Provider / language selection ----
    var provider: String
        get() = prefs.getString("llm_provider", "gemini")!!
        set(v) = prefs.edit().putString("llm_provider", v).apply()

    var targetLanguage: String
        get() = prefs.getString("target_language", "Indonesian")!!
        set(v) = prefs.edit().putString("target_language", v).apply()

    /**
     * Kunci API provider [p], sudah didekripsi.
     *
     * Yang tersimpan di prefs adalah ciphertext AES-GCM dari SecretStore.
     * Nilai warisan versi lama (plaintext tanpa penanda) tetap terbaca, dan
     * ditulis ulang dalam bentuk terenkripsi begitu terbaca sekali - jadi
     * migrasinya terjadi sendiri tanpa pengguna mengetik ulang.
     */
    fun apiKey(p: String): String {
        val mentah = prefs.getString("key_$p", "")!!
        if (mentah.isEmpty()) return ""
        val nilai = SecretStore.bukaSandi(mentah)
        if (nilai.isNotEmpty() && !SecretStore.terenkripsi(mentah)) {
            prefs.edit().putString("key_$p", SecretStore.sandi(nilai)).apply()
        }
        return nilai
    }

    fun setApiKey(p: String, v: String) =
        prefs.edit().putString("key_$p", SecretStore.sandi(v)).apply()

    fun model(p: String): String {
        val saved = prefs.getString("model_$p", null)?.trim()
        val fallback = Providers.REGISTRY[p]?.defaultModel ?: ""
        if (saved.isNullOrEmpty()) return fallback
        // Model Gemini yang sudah dipensiunkan Google akan selalu gagal 404,
        // jadi setelan lama yang tersimpan dinaikkan otomatis ke alias -latest.
        if (p == "gemini" && saved in RETIRED_GEMINI_MODELS) {
            setModel(p, fallback)
            return fallback
        }
        return saved
    }

    fun setModel(p: String, v: String) = prefs.edit().putString("model_$p", v).apply()

    var customBaseUrl: String
        get() = prefs.getString("custom_base_url", "")!!
        set(v) = prefs.edit().putString("custom_base_url", v).apply()

    var outputTreeUri: String
        get() = prefs.getString("output_tree_uri", "")!!
        set(v) = prefs.edit().putString("output_tree_uri", v).apply()

    // ---- Batching & rate limiting ----
    var maxBubblesPerRequest: Int
        get() = prefs.getInt("max_bubbles", 20)
        set(v) = prefs.edit().putInt("max_bubbles", v).apply()

    var minRequestDelay: Float
        get() = prefs.getFloat("request_delay", 2.0f)
        set(v) = prefs.edit().putFloat("request_delay", v).apply()

    // ---- Tweakables (mirrors TWEAKABLE_PARAMS) ----
    var padXRatio: Float
        get() = prefs.getFloat("pad_x", 0.40f)
        set(v) = prefs.edit().putFloat("pad_x", v).apply()

    var padYRatio: Float
        get() = prefs.getFloat("pad_y", 0.25f)
        set(v) = prefs.edit().putFloat("pad_y", v).apply()

    var minPad: Int
        get() = prefs.getInt("min_pad", 35)
        set(v) = prefs.edit().putInt("min_pad", v).apply()

    var skalaPotonganMosaik: Float
        get() = prefs.getFloat("skala_potongan", 2.0f)
        set(v) = prefs.edit().putFloat("skala_potongan", v).apply()

    var maskMarginRatio: Float
        get() = prefs.getFloat("mask_margin_ratio", 0.12f)
        set(v) = prefs.edit().putFloat("mask_margin_ratio", v).apply()

    var sfxMode: String
        get() = prefs.getString("sfx_mode", "balanced")!!
        set(v) = prefs.edit().putString("sfx_mode", v).apply()

    var patchGepeng: Boolean
        get() = prefs.getBoolean("patch_gepeng", true)
        set(v) = prefs.edit().putBoolean("patch_gepeng", v).apply()

    /** Longest-side cap for decoding pages, keeps memory sane on phones. */
    var maxImageSide: Int
        get() = prefs.getInt("max_image_side", 2200)
        set(v) = prefs.edit().putInt("max_image_side", v).apply()

    // ---- Constants ported verbatim ----
    val overlapBatasCrop = 0.35f
    val maskAreaLuarBox = true
    val maskMargin = 18
    /** Rasio margin masker terhadap sisi TERPANJANG kotak. */
    val maskMarginBoxRatio = 0.12f
    val maskMarginMax = 90
    val marginKiriNomor = 55
    val marginKanan = 10
    val jarakAntarPotongan = 10
    val lebarMosaikMin = 360
    val maxTinggiMosaik = 6000
    /** FILTER_SFX_AKTIF in the reference config. */
    var filterSfxAktif: Boolean
        get() = prefs.getBoolean("filter_sfx_aktif", true)
        set(v) = prefs.edit().putBoolean("filter_sfx_aktif", v).apply()

    /**
     * Cari juga teks yang TIDAK berada di dalam balon (narasi kotak, teks
     * latar, papan nama) memakai detektor teks PP-OCRv5. Model balon tidak
     * pernah melihat teks semacam ini.
     */
    var ocrTeksLepas: Boolean
        get() = prefs.getBoolean("ocr_teks_lepas", true)
        set(v) = prefs.edit().putBoolean("ocr_teks_lepas", v).apply()

    /**
     * Pakai detektor RT-DETR tiga-kelas (bubble / text_bubble / text_free)
     * sebagai pengganti YOLOv8 satu-kelas. Model ini dilatih atas ~11k gambar
     * komik dan mengenali teks di luar balon secara langsung, sehingga tahap
     * OCR terpisah tidak lagi diperlukan untuk kebanyakan halaman.
     */
    var detektorRtdetr: Boolean
        get() = prefs.getBoolean("detektor_rtdetr", true)
        set(v) = prefs.edit().putBoolean("detektor_rtdetr", v).apply()

    /**
     * Ambil warna latar balon dan warna teks asli dari gambar, alih-alih
     * selalu mengecat putih dengan huruf hitam. Membuat balon hitam/berwarna
     * tetap utuh setelah diterjemahkan.
     */
    var warnaOtomatis: Boolean
        get() = prefs.getBoolean("warna_otomatis", true)
        set(v) = prefs.edit().putBoolean("warna_otomatis", v).apply()


    /**
     * Hapus teks di luar balon dengan LaMa, bukan mengecat putih.
     *
     * Bawaannya mati: modelnya menambah beberapa detik per halaman, sementara
     * di dalam balon putih polos isi-putih sudah memberi hasil sempurna.
     */
    var inpaintLama: Boolean
        get() = prefs.getBoolean("inpaint_lama", false)
        set(v) = prefs.edit().putBoolean("inpaint_lama", v).apply()

    /**
     * Simpan hasil tiap proses sebagai proyek yang bisa disunting ulang.
     *
     * Menyala secara bawaan: yang mahal dari terjemahan adalah deteksi dan
     * panggilan LLM berbayar, dan tanpa proyek satu salah ketik memaksa
     * pengguna membayar semuanya lagi. Biayanya salinan halaman sumber di
     * penyimpanan internal, yang dibatasi Project.MAX_PROJECTS.
     */
    var simpanProyek: Boolean
        get() = prefs.getBoolean("simpan_proyek", true)
        set(v) = prefs.edit().putBoolean("simpan_proyek", v).apply()

    /**
     * Urutan baca kanan-ke-kiri (manga Jepang). Nonaktifkan untuk komik Barat,
     * manhua, atau webtoon yang dibaca kiri-ke-kanan.
     *
     * Urutan ini menentukan penomoran ID merah pada mosaik, jadi ia menentukan
     * urutan percakapan yang dibaca model - bukan sekadar kosmetik.
     *
     * Hanya dipakai bila [arahBacaOtomatis] mati; kalau menyala nilainya
     * diputuskan per halaman oleh ReadingDirection.
     */
    var bacaKananKeKiri: Boolean
        get() = prefs.getBoolean("baca_kanan_ke_kiri", true)
        set(v) = prefs.edit().putBoolean("baca_kanan_ke_kiri", v).apply()

    /**
     * Tebak arah baca dari tata letak halaman, bukan dari sakelar manual.
     *
     * Sakelar manual salah untuk manhwa/webtoon: bawaannya kanan-ke-kiri
     * (manga Jepang), sementara webtoon Korea dibaca kiri-ke-kanan. Pengguna
     * yang tidak menyadarinya mendapat urutan percakapan terbalik pada setiap
     * halaman berbalon-jamak - dan itu diam-diam, karena hasilnya tetap
     * terlihat "diterjemahkan". Lihat ReadingDirection untuk metodenya.
     */
    var arahBacaOtomatis: Boolean
        get() = prefs.getBoolean("arah_baca_otomatis", true)
        set(v) = prefs.edit().putBoolean("arah_baca_otomatis", v).apply()

    /**
     * Simpan hasil terjemahan tiap balon dan pakai ulang bila balon yang sama
     * muncul lagi.
     *
     * Halaman yang diproses ulang (setelah menyunting kotak, mengganti font,
     * atau mengulang bab yang sama dengan bahasa sama) sebelumnya membayar
     * penuh ke API lagi. Kuncinya adalah sidik gambar potongan + bahasa +
     * model, jadi cache tidak pernah dipakai lintas bahasa atau lintas model.
     */
    var cacheTerjemahan: Boolean
        get() = prefs.getBoolean("cache_terjemahan", true)
        set(v) = prefs.edit().putBoolean("cache_terjemahan", v).apply()

    /**
     * Tampilkan perkiraan token dan biaya tiap request di konsol, dan totalnya
     * di akhir proses.
     *
     * Tanpa ini pengguna tidak punya cara tahu satu bab menghabiskan berapa
     * sampai tagihan datang.
     */
    var hitungBiaya: Boolean
        get() = prefs.getBoolean("hitung_biaya", true)
        set(v) = prefs.edit().putBoolean("hitung_biaya", v).apply()

    /** Total biaya kumulatif (USD) sejak terakhir direset, untuk ditampilkan di UI. */
    var biayaKumulatif: Float
        get() = prefs.getFloat("biaya_kumulatif", 0f)
        set(v) = prefs.edit().putFloat("biaya_kumulatif", v).apply()

    /** Total token kumulatif (masuk + keluar) sejak terakhir direset. */
    var tokenKumulatif: Long
        get() = prefs.getLong("token_kumulatif", 0L)
        set(v) = prefs.edit().putLong("token_kumulatif", v).apply()

    /**
     * Sertakan gambar halaman utuh sebagai rujukan di samping mosaik.
     *
     * Mosaik hanya berisi potongan balon; model tidak bisa melihat ekspresi
     * wajah, siapa yang sedang bicara, atau tata letak panel. Melampirkan
     * halaman utuh memulihkan konteks itu. Biayanya satu gambar tambahan per
     * request, jadi sakelarnya terpisah dari konteks teks.
     */
    var gambarRujukan: Boolean
        get() = prefs.getBoolean("gambar_rujukan", true)
        set(v) = prefs.edit().putBoolean("gambar_rujukan", v).apply()

    /** Sisi terpanjang gambar rujukan; dikecilkan agar hemat kuota unggah. */
    val sisiRujukanMaks = 1024

    /**
     * Lanjutkan arsip yang terhenti alih-alih mengulang dari halaman pertama.
     *
     * Satu bab besar berarti ratusan permintaan berbayar; kalau prosesnya
     * putus di tengah, tanpa ini seluruh biaya itu hangus.
     */
    var lanjutkanArsip: Boolean
        get() = prefs.getBoolean("lanjutkan_arsip", true)
        set(v) = prefs.edit().putBoolean("lanjutkan_arsip", v).apply()

    /** Kirim terjemahan halaman sebelumnya sebagai konteks lintas halaman. */
    var konteksHalaman: Boolean
        get() = prefs.getBoolean("konteks_halaman", true)
        set(v) = prefs.edit().putBoolean("konteks_halaman", v).apply()

    /**
     * URI berkas glosarium (TSV/TXT/JSON) yang dipilih pengguna, kosong bila
     * tidak dipakai. Isinya dibaca ulang tiap kali proses dimulai supaya
     * suntingan di luar aplikasi langsung terpakai tanpa perlu memilih ulang.
     */
    var glossaryUri: String
        get() = prefs.getString("glossary_uri", "")!!
        set(v) = prefs.edit().putString("glossary_uri", v).apply()

    /** Nama berkas glosarium, hanya untuk ditampilkan di UI. */
    var glossaryName: String
        get() = prefs.getString("glossary_name", "")!!
        set(v) = prefs.edit().putString("glossary_name", v).apply()

    val rasioBoxGepeng = 2.4f
    val lebarBoxGepengRatio = 0.45f
    val tinggiBoxGepengRatio = 0.22f
    val requestTimeoutSec = 120L

    fun currentModel(): String = model(provider)
    fun currentKey(): String = apiKey(provider)
    fun currentMeta(): ProviderMeta = Providers.REGISTRY[provider] ?: Providers.REGISTRY["gemini"]!!

    companion object {
        /** Dihapus dari API Gemini; request ke sini selalu balas 404. */
        val RETIRED_GEMINI_MODELS = setOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp",
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest",
            "gemini-1.5-pro",
            "gemini-1.5-pro-latest",
            "gemini-pro",
            "gemini-pro-vision"
        )

        @Volatile private var inst: Config? = null
        fun get(ctx: Context): Config = inst ?: synchronized(this) {
            inst ?: Config(ctx).also { inst = it }
        }
    }
}
