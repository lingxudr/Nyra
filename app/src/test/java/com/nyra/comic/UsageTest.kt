package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Penghitungan token dan biaya. */
class UsageTest {

    // ------------------------------------------------------------------
    // Membaca pemakaian dari respons
    // ------------------------------------------------------------------

    @Test
    fun `membaca usageMetadata gaya Gemini`() {
        val json = """
            {"candidates":[{"content":{"parts":[{"text":"{}"}]}}],
             "usageMetadata":{"promptTokenCount":1500,"candidatesTokenCount":320,
                              "totalTokenCount":1820}}
        """.trimIndent()
        val p = Usage.dariJson(json)
        assertFalse(p.tanpaData)
        assertEquals(1500L, p.masuk)
        assertEquals(320L, p.keluar)
        assertEquals(1820L, p.total)
    }

    /**
     * Token "thinking" ditagih sebagai keluaran. Kalau tidak dijumlahkan,
     * model yang banyak berpikir tampak jauh lebih murah daripada tagihannya.
     */
    @Test
    fun `token thinking dihitung sebagai keluaran`() {
        val json = """
            {"usageMetadata":{"promptTokenCount":1000,"candidatesTokenCount":200,
                              "thoughtsTokenCount":800}}
        """.trimIndent()
        val p = Usage.dariJson(json)
        assertEquals(1000L, p.masuk)
        assertEquals(1000L, p.keluar)
    }

    @Test
    fun `membaca usage gaya OpenAI`() {
        val json = """
            {"choices":[{"message":{"content":"{}"}}],
             "usage":{"prompt_tokens":900,"completion_tokens":150,"total_tokens":1050}}
        """.trimIndent()
        val p = Usage.dariJson(json)
        assertFalse(p.tanpaData)
        assertEquals(900L, p.masuk)
        assertEquals(150L, p.keluar)
    }

    @Test
    fun `gateway yang hanya melaporkan total tetap terhitung`() {
        val p = Usage.dariJson("""{"usage":{"total_tokens":700}}""")
        assertFalse(p.tanpaData)
        assertEquals(700L, p.total)
    }

    /**
     * Respons tanpa data pemakaian harus MENGAKU tidak tahu, bukan melapor nol.
     * Nol yang tampak pasti akan membuat total biaya terlihat gratis.
     */
    @Test
    fun `respons tanpa data pemakaian ditandai tanpaData`() {
        assertTrue(Usage.dariJson("""{"candidates":[]}""").tanpaData)
        assertTrue(Usage.dariJson("bukan json").tanpaData)
        assertTrue(Usage.dariJson(null).tanpaData)
        assertTrue(Usage.dariJson("").tanpaData)
    }

    // ------------------------------------------------------------------
    // Tarif
    // ------------------------------------------------------------------

    /**
     * Jebakan pencocokan awalan: "gemini-2.5-flash-lite" juga berawalan
     * "gemini-2.5-flash". Yang lebih panjang harus menang, kalau tidak model
     * termurah ditagih dengan tarif 6x lipat.
     */
    @Test
    fun `flash lite tidak tertangkap tarif flash`() {
        assertEquals(0.10, Usage.tarif("gemini-2.5-flash-lite").masuk, 1e-9)
        assertEquals(0.30, Usage.tarif("gemini-2.5-flash").masuk, 1e-9)
        assertEquals(0.10, Usage.tarif("gpt-4.1-nano").masuk, 1e-9)
        assertEquals(0.40, Usage.tarif("gpt-4.1-mini").masuk, 1e-9)
        assertEquals(2.00, Usage.tarif("gpt-4.1").masuk, 1e-9)
    }

    @Test
    fun `varian bertanggal tetap cocok lewat awalan`() {
        assertEquals(0.30, Usage.tarif("gemini-2.5-flash-preview-09-2025").masuk, 1e-9)
    }

    @Test
    fun `nama bergaya openrouter dikenali`() {
        assertEquals(1.50, Usage.tarif("google/gemini-flash-latest").masuk, 1e-9)
    }

    @Test
    fun `prefix models dibuang`() {
        assertEquals(1.50, Usage.tarif("models/gemini-3.6-flash").masuk, 1e-9)
    }

    /**
     * Model asing TIDAK boleh meminjam tarif model lain. Lebih baik kolom biaya
     * kosong daripada angka yang salah tapi terlihat pasti.
     */
    @Test
    fun `model tidak dikenal bertarif nol`() {
        val t = Usage.tarif("llama-3-70b-vision")
        assertFalse(t.diketahui)
        assertEquals(0.0, Usage.biaya(Usage.Pakai(1_000_000, 1_000_000), t), 1e-9)
    }

    @Test
    fun `model kosong aman`() {
        assertFalse(Usage.tarif("").diketahui)
        assertFalse(Usage.tarif("   ").diketahui)
    }

    // ------------------------------------------------------------------
    // Hitungan biaya
    // ------------------------------------------------------------------

    @Test
    fun `biaya dihitung per juta token`() {
        // 2.5-flash: $0.30 masuk, $2.50 keluar.
        val p = Usage.Pakai(1_000_000, 100_000)
        val usd = Usage.biaya(p, "gemini-2.5-flash")
        assertEquals(0.30 + 0.25, usd, 1e-9)
    }

    @Test
    fun `biaya nol untuk pemakaian nol`() {
        assertEquals(0.0, Usage.biaya(Usage.Pakai(0, 0), "gemini-2.5-flash"), 1e-9)
    }

    // ------------------------------------------------------------------
    // Akumulator
    // ------------------------------------------------------------------

    @Test
    fun `penghitung menjumlahkan panggilan dan memisahkan yang tanpa data`() {
        val h = Usage.Penghitung()
        h.tambah(Usage.Pakai(100, 20))
        h.tambah(Usage.Pakai(300, 40))
        h.tambah(Usage.Pakai.KOSONG)

        assertEquals(3, h.panggilan)
        assertEquals(1, h.tanpaData)
        assertEquals(400L, h.pakai.masuk)
        assertEquals(60L, h.pakai.keluar)
        // Panggilan tanpa data tidak boleh mencemari total dengan nol palsu.
        assertFalse(h.pakai.tanpaData)
    }

    @Test
    fun `penghitung kosong tetap nol`() {
        val h = Usage.Penghitung()
        assertEquals(0, h.panggilan)
        assertEquals(0L, h.pakai.total)
        assertEquals(0.0, h.biaya("gemini-2.5-flash"), 1e-9)
    }

    // ------------------------------------------------------------------
    // Format
    // ------------------------------------------------------------------

    /**
     * Satu request murah bisa berharga $0.0003. Membulatkan ke dua angka
     * menampilkan "$0.00" dan membuat fiturnya tampak rusak.
     */
    @Test
    fun `biaya sangat kecil tidak dibulatkan menjadi nol`() {
        assertEquals("<$0.0001", Usage.rupiahkanUsd(0.00002))
        assertEquals("$0.0003", Usage.rupiahkanUsd(0.0003))
        assertEquals("$1.23", Usage.rupiahkanUsd(1.2345))
        assertEquals("$0", Usage.rupiahkanUsd(0.0))
    }

    @Test
    fun `token diringkas terbaca`() {
        assertEquals("999", Usage.ringkasToken(999))
        assertEquals("1.5k", Usage.ringkasToken(1500))
        assertEquals("2.0jt", Usage.ringkasToken(2_000_000))
    }

    @Test
    fun `baris ringkasan menyebut tarif tak dikenal secara jujur`() {
        val p = Usage.Pakai(1000, 200)
        assertTrue(Usage.baris(p, "model-asing").contains("tidak diketahui"))
        assertFalse(Usage.baris(p, "gemini-2.5-flash").contains("tidak diketahui"))
    }
}
