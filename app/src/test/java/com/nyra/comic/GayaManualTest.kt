package com.nyra.comic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menguji penimpaan gaya huruf oleh pengguna.
 *
 * Inti yang dijaga: pengukuran otomatis kadang salah, dan ketika pengguna
 * membetulkannya, pilihan itu harus benar-benar mengubah hasil render, tidak
 * boleh hilang saat proyek disimpan, dan tidak boleh ditimpa oleh pengukuran
 * ulang.
 */
class GayaManualTest {

    private val terukurTipis = Typography.Gaya(
        arah = Typography.Arah.DATAR,
        rasioIsi = 0.60f,
        kepadatan = 0.02f,
        tebalGoresan = 1f,
        berat = Typography.Berat.TIPIS,
        terukur = true
    )

    @Test
    fun gayaTerkunciMengalahkanPengukuran() {
        // Diukur tipis, tetapi pengguna memaksa tebal: yang dipakai harus tebal.
        val manual = terukurTipis.copy(berat = Typography.Berat.TEBAL, dikunci = true)
        val r = Typography.putuskan(manual, ukuranMuat = 30, panjangTeks = 20, bahasaTegak = false)
        assertEquals(Typography.Berat.TEBAL, r.berat)
    }

    @Test
    fun gayaManualBerlakuWalauBalonGagalDiukur() {
        // Balon yang tidak terukur justru yang paling sering perlu dibetulkan.
        // Kalau `terukur` diperiksa lebih dulu, pilihan manual akan diabaikan.
        val manual = Typography.Gaya.BAWAAN.copy(
            berat = Typography.Berat.TEBAL,
            rasioIsi = 0.90f,
            dikunci = true
        )
        val r = Typography.putuskan(manual, ukuranMuat = 30, panjangTeks = 20, bahasaTegak = false)
        assertEquals(Typography.Berat.TEBAL, r.berat)
        assertEquals(0.90f, r.skalaLebar, 0.001f)
    }

    @Test
    fun spasiPaksaMengalahkanKepadatan() {
        // Kepadatan tinggi biasanya memaksa SPASI_RAPAT; pengguna minta longgar.
        val padat = terukurTipis.copy(kepadatan = 0.20f)
        val auto = Typography.putuskan(padat, 30, 20, false)
        assertEquals(Typography.SPASI_RAPAT, auto.spasiBaris, 0.0001f)

        val manual = padat.copy(spasiPaksa = Typography.SPASI_LONGGAR, dikunci = true)
        val r = Typography.putuskan(manual, 30, 20, false)
        assertEquals(Typography.SPASI_LONGGAR, r.spasiBaris, 0.0001f)
        assertNotEquals(auto.spasiBaris, r.spasiBaris)
    }

    @Test
    fun tanpaKunciPerilakuLamaTidakBerubah() {
        // Paritas: gaya tanpa penimpaan harus menghasilkan rencana yang sama
        // persis seperti sebelum fitur ini ada.
        val r = Typography.putuskan(terukurTipis, 30, 20, false)
        assertEquals(Typography.Berat.TIPIS, r.berat)
        assertEquals(0.60f, r.skalaLebar, 0.001f)
        assertEquals(Typography.SPASI_LONGGAR, r.spasiBaris, 0.0001f)
    }

    @Test
    fun gayaBawaanTetapMemakaiNilaiBawaan() {
        val r = Typography.putuskan(Typography.Gaya.BAWAAN, 30, 20, false)
        assertEquals(Typography.Berat.NORMAL, r.berat)
        assertEquals(Typography.ISI_BAWAAN, r.skalaLebar, 0.001f)
        assertEquals(Typography.SPASI_NORMAL, r.spasiBaris, 0.0001f)
    }

    @Test
    fun kunciBertahanSetelahSimpanDanMuat() {
        val asli = terukurTipis.copy(
            berat = Typography.Berat.TEBAL,
            rasioIsi = 0.88f,
            spasiPaksa = Typography.SPASI_RAPAT,
            dikunci = true
        )
        val ulang = Project.gayaFromJson(Project.gayaToJson(asli))
        assertEquals(asli, ulang)
    }

    @Test
    fun proyekLamaTanpaMedanBaruTetapTerbaca() {
        // Berkas yang ditulis versi sebelumnya tidak punya "dikunci" maupun
        // "spasiPaksa". Memuatnya tidak boleh gagal dan tidak boleh
        // mengunci gaya secara tak sengaja.
        val lama = JSONObject()
            .put("arah", "DATAR")
            .put("rasioIsi", 0.7)
            .put("kepadatan", 0.05)
            .put("goresan", 2.0)
            .put("berat", "NORMAL")
            .put("terukur", true)
        val g = Project.gayaFromJson(lama)
        assertFalse("proyek lama tidak boleh terkunci", g.dikunci)
        assertEquals(0f, g.spasiPaksa, 0.0001f)
    }

    @Test
    fun gayaOtomatisTidakMenulisMedanBaru() {
        // Menjaga berkas proyek tetap kecil dan setara dengan versi lama.
        val j = Project.gayaToJson(terukurTipis)
        assertFalse(j.has("dikunci"))
        assertFalse(j.has("spasiPaksa"))
    }

    @Test
    fun isiDijepitPadaRentangYangMasukAkal() {
        // Nilai di luar ISI_MIN..ISI_MAKS akan membuat teks meluber atau
        // tenggelam; slider tidak menawarkannya, tapi berkas bisa saja rusak.
        assertTrue(Typography.ISI_MIN < Typography.ISI_MAKS)
        assertTrue(Typography.ISI_BAWAAN in Typography.ISI_MIN..Typography.ISI_MAKS)
    }
}
