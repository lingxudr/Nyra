package com.nyra.comic

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gerbang laju permintaan.
 *
 * Kelas ini menentukan dua hal yang saling bertentangan sekaligus: JARAK antar
 * keberangkatan (supaya kuota provider tidak jebol) dan BERAPA BANYAK yang
 * boleh berjalan bersamaan. Versi lama menggabungkan keduanya karena memegang
 * monitor selama tidur, sehingga request kedua baru boleh berangkat setelah
 * request pertama SELESAI - bukan setelah jaraknya terpenuhi. Akibatnya
 * seluruh pipeline terpaksa serial, dan itulah yang membuat 49 halaman makan
 * puluhan menit. Tes di bawah mengunci kedua sifat itu secara terpisah.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class RateLimiterTest {

    private fun cfg(delayDetik: Float): Config {
        val c = Config(ApplicationProvider.getApplicationContext())
        c.minRequestDelay = delayDetik
        return c
    }

    private fun limiter(delayDetik: Float) = RateLimiter(cfg(delayDetik)) { }

    // ------------------------------------------------------------------
    // Jarak antar keberangkatan
    // ------------------------------------------------------------------

    /**
     * Tiga panggilan berurutan harus berjarak minimal minRequestDelay.
     * Inilah janji yang menjaga kuota; kalau hilang, provider membalas 429.
     */
    @Test
    fun `keberangkatan berurutan tetap berjarak minRequestDelay`() {
        val rl = limiter(0.30f)
        val berangkat = ArrayList<Long>()
        repeat(3) { rl.executeWithRetry("uji") { berangkat.add(System.currentTimeMillis()) } }

        assertEquals(3, berangkat.size)
        val jarak1 = berangkat[1] - berangkat[0]
        val jarak2 = berangkat[2] - berangkat[1]
        // Toleransi 60 ms untuk ketidaktepatan penjadwal thread.
        assertTrue("jarak 1-2 terlalu rapat: $jarak1 ms", jarak1 >= 240)
        assertTrue("jarak 2-3 terlalu rapat: $jarak2 ms", jarak2 >= 240)
    }

    /** minRequestDelay = 0 berarti tanpa gerbang: tidak boleh ada tidur. */
    @Test
    fun `delay nol tidak menahan siapa pun`() {
        val rl = limiter(0f)
        val mulai = System.currentTimeMillis()
        repeat(5) { rl.executeWithRetry("uji") { 1 } }
        val lama = System.currentTimeMillis() - mulai
        assertTrue("tanpa delay seharusnya nyaris seketika, dapat $lama ms", lama < 300)
    }

    // ------------------------------------------------------------------
    // Tumpang tindih
    // ------------------------------------------------------------------

    /**
     * INTI PERBAIKAN. Empat request yang masing-masing menghabiskan 600 ms di
     * jaringan harus berjalan TUMPANG TINDIH. Dengan gerbang lama yang tidur
     * sambil memegang kunci, total waktunya jadi ~4x600 ms; sekarang yang
     * menentukan hanyalah jarak keberangkatan.
     *
     * Yang diperiksa bukan stopwatch semata melainkan jumlah panggilan yang
     * benar-benar berada di dalam blok pada saat bersamaan - itu bukti
     * langsung, bukan kesimpulan dari durasi.
     */
    @Test
    fun `request berjalan tumpang tindih bukan antre satu per satu`() {
        val rl = limiter(0.05f)
        val sedangJalan = AtomicInteger(0)
        val puncak = AtomicInteger(0)
        val siap = CountDownLatch(4)

        val threads = (0 until 4).map {
            Thread {
                rl.executeWithRetry("uji") {
                    val n = sedangJalan.incrementAndGet()
                    puncak.updateAndGet { p -> maxOf(p, n) }
                    Thread.sleep(600)
                    sedangJalan.decrementAndGet()
                    siap.countDown()
                }
            }.apply { start() }
        }
        threads.forEach { it.join(10_000) }

        assertTrue("semua request harus selesai", siap.await(1, TimeUnit.SECONDS))
        assertTrue(
            "tidak ada tumpang tindih sama sekali (puncak=${puncak.get()}): " +
                "gerbang laju kembali menyerialkan pipeline",
            puncak.get() >= 2
        )
    }

    /**
     * Tumpang tindih tidak boleh mengorbankan jarak. Empat thread yang
     * berebut sekaligus tetap harus berangkat satu per satu dengan jeda -
     * dipesan secara atomik, sehingga tidak ada dua yang mengambil slot sama.
     */
    @Test
    fun `slot dipesan atomik sehingga tidak ada keberangkatan kembar`() {
        val rl = limiter(0.20f)
        val cap = Collections.synchronizedList(mutableListOf<Long>())
        val pintu = CountDownLatch(1)

        val threads = (0 until 4).map {
            Thread {
                pintu.await()
                rl.executeWithRetry("uji") { cap.add(System.currentTimeMillis()) }
            }.apply { start() }
        }
        pintu.countDown()
        threads.forEach { it.join(15_000) }

        assertEquals(4, cap.size)
        val urut = cap.sorted()
        for (i in 1 until urut.size) {
            val jarak = urut[i] - urut[i - 1]
            assertTrue(
                "keberangkatan ke-${i + 1} hanya berjarak $jarak ms dari sebelumnya",
                jarak >= 150
            )
        }
    }

    // ------------------------------------------------------------------
    // Pembatalan
    // ------------------------------------------------------------------

    /**
     * Stop harus memotong tidur yang sedang berjalan. Kalau tidak, pengguna
     * menekan Stop lalu menunggu sisa jeda kuota yang bisa mencapai satu menit
     * dan menyangka aplikasinya beku.
     */
    @Test
    fun `pembatalan memotong tidur gerbang`() {
        val rl = limiter(5.0f)
        // Panggilan pertama lewat tanpa menunggu dan memesan slot berikutnya
        // 5 detik ke depan.
        rl.executeWithRetry("uji") { 1 }

        val dipanggil = AtomicInteger(0)
        val t = Thread { rl.executeWithRetry("uji") { dipanggil.incrementAndGet() } }
        t.start()
        Thread.sleep(300)
        rl.cancelled = true
        t.join(3_000)

        assertFalse("thread harus berhenti jauh sebelum 5 detik", t.isAlive)
        assertEquals("panggilan tidak boleh dikirim setelah Stop", 0, dipanggil.get())
    }

    /** Setelah dibatalkan, blok kerja tidak pernah dijalankan sama sekali. */
    @Test
    fun `dibatalkan sebelum mulai tidak memanggil apa pun`() {
        val rl = limiter(0f)
        rl.cancelled = true
        val n = AtomicInteger(0)
        val hasil = rl.executeWithRetry("uji") { n.incrementAndGet() }
        assertEquals(0, n.get())
        assertEquals(null, hasil)
    }

    // ------------------------------------------------------------------
    // Percobaan ulang
    // ------------------------------------------------------------------

    /** Kunci API salah bukan gangguan sementara: harus langsung dilempar. */
    @Test(expected = ApiKeyException::class)
    fun `ApiKeyException tidak pernah dicoba ulang`() {
        val rl = limiter(0f)
        val n = AtomicInteger(0)
        try {
            rl.executeWithRetry("uji") {
                n.incrementAndGet(); throw ApiKeyException("kunci salah")
            }
        } finally {
            assertEquals("hanya boleh satu percobaan", 1, n.get())
        }
    }

    /** Gangguan jaringan sementara harus dicoba lagi lalu berhasil. */
    @Test
    fun `gangguan sementara dicoba ulang lalu berhasil`() {
        val rl = limiter(0f)
        val n = AtomicInteger(0)
        val hasil = rl.executeWithRetry("uji", maxRetries = 3) {
            if (n.incrementAndGet() < 2) throw RuntimeException("connection reset")
            "oke"
        }
        assertEquals("oke", hasil)
        assertEquals(2, n.get())
    }
}
