package com.nyra.comic

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

/**
 * `skalaPotongan` menjepit pembesaran potongan sesuai jatah tinggi mosaik.
 *
 * MASALAH YANG DIPERBAIKI (ronde 26): potongan dulu selalu diperbesar
 * `skalaPotonganMosaik` (bawaan 2,0x), ditulis ke mosaik, lalu mosaik yang
 * kelewat tinggi dikecilkan lagi oleh `shrinkIfTooTall` sekitar 0,510x.
 * Hasil akhirnya cuma 1,02x tetapi menempuh DUA kali resample bilinear -
 * lebih lembek daripada tidak diperbesar sama sekali - sambil mengirim
 * berkas yang jauh lebih besar ke server. Pembesaran sekarang dibatasi di
 * muka supaya cuma ada satu kali resample.
 *
 * Yang dikunci: batas jatah dihormati, tidak pernah MENGECILKAN (itu tugas
 * tahap lain), dan permintaan yang memang muat diteruskan apa adanya.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class SkalaPotonganTest {

    private lateinit var pipeline: Pipeline
    private lateinit var cfg: Config

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        cfg = Config(ctx)
        pipeline = Pipeline(ctx, cfg, log = {}, progress = { _, _ -> })
    }

    /** Jatah per potongan dengan setelan bawaan, dihitung ulang di sini. */
    private fun jatah(): Float {
        val n = cfg.maxBubblesPerRequest
        return (cfg.maxTinggiMosaik - n * cfg.jarakAntarPotongan - 20).toFloat() / n
    }

    @Test
    fun `potongan kecil boleh diperbesar penuh`() {
        cfg.skalaPotonganMosaik = 2.0f
        // 40 px x 2,0 = 80 px, jauh di bawah jatah (~275 px) => tidak dijepit.
        assertEquals(2.0f, pipeline.skalaPotongan(40), 0.001f)
    }

    @Test
    fun `potongan tinggi dijepit supaya tidak melebihi jatah`() {
        cfg.skalaPotonganMosaik = 2.0f
        val tinggi = 200
        val s = pipeline.skalaPotongan(tinggi)
        assertTrue("harus dijepit di bawah 2,0 tapi dapat $s", s < 2.0f)
        assertTrue(
            "hasil kali skala tidak boleh melewati jatah",
            tinggi * s <= jatah() + 1f
        )
    }

    /**
     * Ini inti perbaikannya: setelah dijepit, mosaik tidak boleh lagi
     * ketinggian, sehingga `shrinkIfTooTall` tidak punya alasan mengecilkan
     * dan resample kedua tidak pernah terjadi.
     */
    @Test
    fun `pembesaran tidak pernah bikin mosaik kelewat tinggi`() {
        cfg.skalaPotonganMosaik = 2.0f
        val n = cfg.maxBubblesPerRequest
        // 200 px x 20 potongan masih muat sebelum diperbesar, jadi di sinilah
        // penjepitan benar-benar menentukan: tanpa itu 2,0x akan membengkak
        // jadi ~8 000 px dan memicu resample kedua.
        val tinggi = 200
        val totalTinggi = n * (tinggi * pipeline.skalaPotongan(tinggi)) +
            n * cfg.jarakAntarPotongan + 20
        assertTrue(
            "mosaik hasil ($totalTinggi) harus muat dalam maxTinggiMosaik " +
                "(${cfg.maxTinggiMosaik})",
            totalTinggi <= cfg.maxTinggiMosaik.toFloat() + 1f
        )
        assertTrue(
            "tanpa penjepitan 2,0x pasti meluap - pastikan tesnya bermakna",
            n * (tinggi * 2.0f) + n * cfg.jarakAntarPotongan + 20 >
                cfg.maxTinggiMosaik.toFloat()
        )
    }

    /**
     * Batas jujur dari lantai 1,0: kalau potongan pada ukuran ASLI saja sudah
     * tidak muat, tahap ini menyerah dan membiarkan `shrinkIfTooTall`
     * mengecilkan mosaiknya. Itu memang disengaja - lebih baik satu
     * pengecilan yang perlu daripada menurunkan resolusi tiap potongan
     * secara diam-diam di sini.
     */
    @Test
    fun `potongan yang aslinya sudah kebesaran diserahkan ke tahap pengecil`() {
        cfg.skalaPotonganMosaik = 2.0f
        val tinggi = 300
        assertEquals(
            "tidak boleh diperbesar sama sekali",
            1.0f, pipeline.skalaPotongan(tinggi), 0.001f
        )
    }

    /**
     * Lantai 1,0: tahap ini hanya boleh MEMBATALKAN pembesaran, bukan
     * menurunkan resolusi di bawah aslinya. Menurunkan resolusi di sini akan
     * membuat teks kecil hilang sebelum sempat dibaca model.
     */
    @Test
    fun `potongan sangat tinggi tetap tidak dikecilkan`() {
        cfg.skalaPotonganMosaik = 2.0f
        assertTrue(
            "skala tidak boleh di bawah 1,0",
            pipeline.skalaPotongan(100_000) >= 1.0f
        )
    }

    @Test
    fun `permintaan tanpa pembesaran diteruskan apa adanya`() {
        cfg.skalaPotonganMosaik = 1.0f
        assertEquals(1.0f, pipeline.skalaPotongan(500), 0.001f)
    }

    @Test
    fun `tinggi tidak masuk akal tidak bikin skala aneh`() {
        cfg.skalaPotonganMosaik = 2.0f
        // Tinggi 0/negatif pernah muncul dari kotak deteksi yang rusak.
        assertEquals(2.0f, pipeline.skalaPotongan(0), 0.001f)
        assertEquals(2.0f, pipeline.skalaPotongan(-5), 0.001f)
    }
}
