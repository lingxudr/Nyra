package com.nyra.comic

import org.json.JSONObject

/**
 * Penghitungan token dan biaya.
 *
 * MASALAH YANG DIPERBAIKI: sebelumnya tidak ada satu pun angka yang memberi
 * tahu pengguna berapa yang sedang dibelanjakan. Satu bab 40 halaman bisa
 * berarti puluhan request bergambar, dan satu-satunya umpan balik adalah
 * tagihan yang datang belakangan. Yang lebih halus: request yang GAGAL dan
 * request ULANG (nomor yang tidak dijawab model) tetap ditagih, jadi biaya
 * nyata bisa jauh di atas "jumlah halaman x tarif".
 *
 * SUMBER ANGKA. Semua provider yang didukung mengembalikan pemakaian token
 * yang sebenarnya di dalam badan responsnya - Gemini pada `usageMetadata`,
 * yang OpenAI-kompatibel pada `usage`. Angka itulah yang dipakai; tidak ada
 * penaksiran dari panjang prompt. Bila sebuah provider tidak mengirimkannya,
 * pemakaian dicatat nol dan ditandai [tanpaData] - lebih baik mengaku tidak
 * tahu daripada menampilkan tebakan yang terlihat pasti.
 *
 * TARIF. Harga bergerak dan tidak ada API resmi untuk membacanya, jadi tabel
 * di sini adalah tarif publik per 1 juta token (USD) per Agustus 2026 dan
 * ditandai jelas sebagai perkiraan di UI. Model yang tidak dikenali memakai
 * tarif nol, sehingga token tetap dihitung sementara kolom biaya jujur kosong
 * alih-alih memakai tarif model lain yang kebetulan mirip namanya.
 */
object Usage {

    /** Pemakaian satu panggilan API. */
    data class Pakai(
        val masuk: Long = 0,
        val keluar: Long = 0,
        val tanpaData: Boolean = false
    ) {
        val total: Long get() = masuk + keluar

        operator fun plus(lain: Pakai) = Pakai(
            masuk + lain.masuk,
            keluar + lain.keluar,
            tanpaData || lain.tanpaData
        )

        companion object {
            val KOSONG = Pakai(tanpaData = true)
        }
    }

    /** Tarif USD per 1 juta token. */
    data class Tarif(val masuk: Double, val keluar: Double) {
        val diketahui: Boolean get() = masuk > 0.0 || keluar > 0.0

        companion object {
            val TIDAK_DIKENAL = Tarif(0.0, 0.0)
        }
    }

    /**
     * Tarif publik per 1 juta token (USD), Agustus 2026.
     *
     * Kunci dicocokkan sebagai awalan setelah dinormalkan, sehingga
     * "gemini-2.5-flash-preview-09-2025" tetap terjaring oleh entri
     * "gemini-2.5-flash". Entri yang lebih panjang diuji lebih dulu supaya
     * "gemini-2.5-flash-lite" tidak keburu tertangkap "gemini-2.5-flash".
     */
    val TARIF: Map<String, Tarif> = mapOf(
        // --- Gemini ---
        "gemini-3.1-pro" to Tarif(2.00, 12.00),
        "gemini-3.6-flash" to Tarif(1.50, 7.50),
        "gemini-3.5-flash-lite" to Tarif(0.30, 2.50),
        "gemini-3.1-flash-lite" to Tarif(0.25, 1.50),
        "gemini-3-flash" to Tarif(0.50, 3.00),
        "gemini-2.5-pro" to Tarif(1.25, 10.00),
        "gemini-2.5-flash-lite" to Tarif(0.10, 0.40),
        "gemini-2.5-flash" to Tarif(0.30, 2.50),
        // Alias "-latest" mengikuti model yang saat ini ditunjuknya.
        "gemini-flash-lite-latest" to Tarif(0.25, 1.50),
        "gemini-flash-latest" to Tarif(1.50, 7.50),

        // --- OpenAI ---
        "gpt-4o-mini" to Tarif(0.15, 0.60),
        "gpt-4o" to Tarif(2.50, 10.00),
        "gpt-4.1-mini" to Tarif(0.40, 1.60),
        "gpt-4.1-nano" to Tarif(0.10, 0.40),
        "gpt-4.1" to Tarif(2.00, 8.00)
    )

    /** Cari tarif untuk [model]; kembalikan TIDAK_DIKENAL bila tak ada. */
    fun tarif(model: String): Tarif {
        val m = model.trim().lowercase().removePrefix("models/")
        if (m.isEmpty()) return Tarif.TIDAK_DIKENAL
        // Awalan terpanjang lebih dulu: "…flash-lite" harus menang atas "…flash".
        val kunci = TARIF.keys.sortedByDescending { it.length }
        for (k in kunci) if (m.startsWith(k)) return TARIF[k]!!
        // Nama dari OpenRouter berbentuk "google/gemini-flash-latest".
        val potong = m.substringAfterLast('/')
        if (potong != m) {
            for (k in kunci) if (potong.startsWith(k)) return TARIF[k]!!
        }
        return Tarif.TIDAK_DIKENAL
    }

    /** Biaya USD untuk [pakai] pada [model]; 0.0 bila tarifnya tidak dikenal. */
    fun biaya(pakai: Pakai, model: String): Double = biaya(pakai, tarif(model))

    fun biaya(pakai: Pakai, t: Tarif): Double =
        (pakai.masuk / 1_000_000.0) * t.masuk + (pakai.keluar / 1_000_000.0) * t.keluar

    /**
     * Baca pemakaian dari badan respons JSON mentah.
     *
     * Menerima kedua bentuk sekaligus karena pemanggil tidak selalu tahu
     * provider mana yang menjawab (mis. endpoint kustom yang meniru OpenAI):
     *
     *  - Gemini: `usageMetadata.promptTokenCount` / `candidatesTokenCount`
     *  - OpenAI: `usage.prompt_tokens` / `completion_tokens`
     *
     * Token "thinking" pada Gemini ditagih sebagai keluaran, jadi
     * `thoughtsTokenCount` dijumlahkan ke [Pakai.keluar]. Kalau tidak, model
     * yang banyak berpikir akan terlihat jauh lebih murah daripada kenyataan.
     */
    fun dariJson(badan: String?): Pakai {
        if (badan.isNullOrBlank()) return Pakai.KOSONG
        val obj = runCatching { JSONObject(badan) }.getOrNull() ?: return Pakai.KOSONG

        obj.optJSONObject("usageMetadata")?.let { u ->
            val masuk = u.optLong("promptTokenCount", -1L)
            val keluar = u.optLong("candidatesTokenCount", -1L)
            val pikir = u.optLong("thoughtsTokenCount", 0L).coerceAtLeast(0L)
            if (masuk >= 0 || keluar >= 0) {
                return Pakai(
                    masuk.coerceAtLeast(0L),
                    keluar.coerceAtLeast(0L) + pikir,
                    false
                )
            }
        }

        obj.optJSONObject("usage")?.let { u ->
            val masuk = u.optLong("prompt_tokens", -1L)
            val keluar = u.optLong("completion_tokens", -1L)
            if (masuk >= 0 || keluar >= 0) {
                // Sebagian gateway hanya melaporkan total; bagi tidak akan
                // jujur, jadi total ditaruh di kolom masuk dan tidak ditandai
                // tanpa-data - jumlah tokennya tetap benar.
                return Pakai(masuk.coerceAtLeast(0L), keluar.coerceAtLeast(0L), false)
            }
            val total = u.optLong("total_tokens", -1L)
            if (total >= 0) return Pakai(total, 0, false)
        }

        return Pakai.KOSONG
    }

    /** Akumulator seumur satu proses. */
    class Penghitung {
        var pakai = Pakai(0, 0, false)
            private set
        var panggilan = 0
            private set
        var tanpaData = 0
            private set

        @Synchronized
        fun tambah(p: Pakai) {
            panggilan++
            if (p.tanpaData) {
                tanpaData++
                return
            }
            pakai = Pakai(pakai.masuk + p.masuk, pakai.keluar + p.keluar, false)
        }

        fun biaya(model: String): Double = Usage.biaya(pakai, model)
    }

    /**
     * Format biaya dalam USD.
     *
     * Empat angka di belakang koma: satu request mosaik pada model murah bisa
     * berharga $0.0003, dan membulatkannya ke dua angka menampilkan "$0.00"
     * yang membuat fitur ini tampak rusak.
     */
    fun rupiahkanUsd(usd: Double): String = when {
        usd <= 0.0 -> "$0"
        usd < 0.0001 -> "<$0.0001"
        usd < 1.0 -> "$" + String.format("%.4f", usd)
        else -> "$" + String.format("%.2f", usd)
    }

    /** Ringkas jumlah token: 1234 -> "1,2k", 1234567 -> "1,2jt". */
    fun ringkasToken(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> String.format("%.1fk", n / 1_000.0)
        else -> String.format("%.1fjt", n / 1_000_000.0)
    }

    /** Satu baris ringkasan untuk konsol. */
    fun baris(pakai: Pakai, model: String): String {
        val t = tarif(model)
        val tok = "${ringkasToken(pakai.masuk)} masuk + ${ringkasToken(pakai.keluar)} keluar"
        return if (t.diketahui) "$tok = ${rupiahkanUsd(biaya(pakai, t))}"
        else "$tok (tarif model tidak diketahui)"
    }
}
