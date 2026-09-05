package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes untuk tiga cacat lapangan ronde 40.
 *
 * A. Satu balasan JSON cacat membuang seluruh permintaan (30 balon, 4
 *    halaman) tanpa penyelamatan sebagian.
 * B. Teks hasil render jauh lebih kecil daripada tulisan Jepang yang
 *    digantikannya.
 * C. Teks meluap keluar garis balon dan bertindih dengan balon tetangga.
 *
 * Semuanya JVM murni: salvageJson dan Typography sengaja tidak menyentuh
 * android.graphics.
 */
class Ronde40Test {

    // ----------------------------------------------------------------
    // A. Penyelamatan JSON
    // ----------------------------------------------------------------

    /**
     * Kegagalan lapangan yang sebenarnya: "Expected a ':' after a key at 578".
     * Satu entri rusak di tengah, sisanya sehat. Sebelum ronde 40 seluruh
     * 30 balon hilang; sekarang yang sehat harus selamat.
     */
    @Test
    fun `entri rusak di tengah tidak menjatuhkan entri sehat`() {
        val raw = """
            {
              "1": "Kau sudah tahu?",
              "2": "Aku tidak pernah bilang begitu.",
              "3" "Ini yang rusak",
              "4": "Kita pergi sekarang.",
              "5": "Tunggu!"
            }
        """.trimIndent()

        val hasil = BoxUtils.salvageJson(raw)

        assertEquals(4, hasil.size)
        assertEquals("Kau sudah tahu?", hasil["1"])
        assertEquals("Aku tidak pernah bilang begitu.", hasil["2"])
        assertEquals("Kita pergi sekarang.", hasil["4"])
        assertEquals("Tunggu!", hasil["5"])
    }

    /** JSON terpotong di tengah kalimat - balasan LLM kena batas token. */
    @Test
    fun `jawaban terpotong tetap menyerahkan yang sudah lengkap`() {
        val raw = """{"1": "Halo", "2": "Selamat datang", "3": "Belum sele"""

        val hasil = BoxUtils.salvageJson(raw)

        assertEquals(2, hasil.size)
        assertEquals("Halo", hasil["1"])
        assertEquals("Selamat datang", hasil["2"])
    }

    /** Tanda kutip di dalam dialog tidak boleh memotong nilai lebih awal. */
    @Test
    fun `kutip yang di-escape ikut terbaca utuh`() {
        val raw = """{"7": "Dia bilang \"jangan\" dua kali."}"""

        val hasil = BoxUtils.salvageJson(raw)

        assertEquals("""Dia bilang "jangan" dua kali.""", hasil["7"])
    }

    /** Escape unicode dan garis-miring diurai; baris baru diratakan jadi satu
     *  spasi karena layout membungkus ulang teks di dalam balon. */
    @Test
    fun `escape baris baru dan unicode diurai`() {
        val raw = """{"1": "baris\nkedua", "2": "garis miring \\ dan \u00e9"}"""

        val hasil = BoxUtils.salvageJson(raw)

        assertEquals("baris kedua", hasil["1"])
        assertEquals("garis miring \\ dan é", hasil["2"])
    }

    /** Kunci ganda: yang pertama menang, sesuai urutan permintaan. */
    @Test
    fun `kunci ganda memakai kemunculan pertama`() {
        val raw = """{"3": "asli", "3": "ulangan"}"""

        assertEquals("asli", BoxUtils.salvageJson(raw)["3"])
    }

    /** Sampah total tidak boleh melempar, cukup kosong. */
    @Test
    fun `teks tanpa pasangan menghasilkan peta kosong`() {
        assertTrue(BoxUtils.salvageJson("maaf, saya tidak bisa membantu").isEmpty())
        assertTrue(BoxUtils.salvageJson("").isEmpty())
    }

    /** Pagar kode dan basa-basi di sekeliling JSON tidak mengganggu. */
    @Test
    fun `pagar kode markdown tidak mengganggu`() {
        val raw = """
            Tentu, ini hasilnya:
            ```json
            {"1": "Pagi", "2": "Malam"}
            ```
        """.trimIndent()

        val hasil = BoxUtils.salvageJson(raw)

        assertEquals(2, hasil.size)
        assertEquals("Pagi", hasil["1"])
    }

    // ----------------------------------------------------------------
    // B. Teks terlalu kecil
    // ----------------------------------------------------------------

    private fun gaya(
        arah: Typography.Arah,
        fw: Float,
        fh: Float,
        rasio: Float
    ) = Typography.Gaya(
        arah = arah,
        rasioIsi = rasio,
        kepadatan = 0.08f,
        tebalGoresan = 2f,
        berat = Typography.Berat.NORMAL,
        terukur = true,
        fraksiLebar = fw,
        fraksiTinggi = fh
    )

    /**
     * Inti cacat B. Blok tategaki ramping-jangkung: 35 % lebar balon, 88 %
     * tingginya. Terjemahan ditata mendatar, jadi jatah lebarnya harus
     * mengikuti fraksi TINGGI aslinya - bukan 0,35 yang memaksa font mungil.
     */
    @Test
    fun `balon tategaki menukar sumbu supaya teks mendatar dapat lebar penuh`() {
        val r = Typography.putuskan(
            gaya = gaya(Typography.Arah.TEGAK, fw = 0.35f, fh = 0.88f, rasio = 0.62f),
            ukuranMuat = 60,
            panjangTeks = 40,
            bahasaTegak = false
        )

        assertTrue(
            "skalaLebar harus mengikuti fraksi tinggi asli, dapat ${r.skalaLebar}",
            r.skalaLebar >= 0.85f
        )
        assertTrue(
            "skalaTinggi tidak perlu lebih dari yang dibutuhkan, dapat ${r.skalaTinggi}",
            r.skalaTinggi <= r.skalaLebar
        )
    }

    /** Balon yang aslinya mendatar tidak boleh ikut ditukar. */
    @Test
    fun `balon mendatar tidak menukar sumbu`() {
        val r = Typography.putuskan(
            gaya = gaya(Typography.Arah.DATAR, fw = 0.88f, fh = 0.35f, rasio = 0.70f),
            ukuranMuat = 60,
            panjangTeks = 40,
            bahasaTegak = false
        )

        assertEquals(r.skalaLebar, r.skalaTinggi, 1e-6f)
        assertEquals(0.70f, r.skalaLebar, 1e-6f)
    }

    /** Kalau sasarannya memang tegak, pengukuran sudah sesumbu - jangan tukar. */
    @Test
    fun `sasaran tegak tidak menukar sumbu`() {
        val r = Typography.putuskan(
            gaya = gaya(Typography.Arah.TEGAK, fw = 0.35f, fh = 0.88f, rasio = 0.62f),
            ukuranMuat = 60,
            panjangTeks = 40,
            bahasaTegak = true
        )

        assertEquals(r.skalaLebar, r.skalaTinggi, 1e-6f)
    }

    /**
     * Batas bawah rasio isi dinaikkan jadi 0,62. Pada 0,55 yang lama, teks
     * hanya boleh memakai 30 % LUAS balon - itulah kenapa terjemahan tampak
     * jauh lebih kecil daripada tulisan aslinya.
     */
    @Test
    fun `rasio isi tidak pernah turun di bawah batas baru`() {
        val r = Typography.putuskan(
            gaya = gaya(Typography.Arah.TIDAK_JELAS, fw = 0.10f, fh = 0.10f, rasio = 0.62f),
            ukuranMuat = 40,
            panjangTeks = 20,
            bahasaTegak = false
        )

        assertTrue(r.skalaLebar >= Typography.ISI_MIN)
        assertTrue(r.skalaTinggi >= Typography.ISI_MIN)
        assertTrue(Typography.ISI_MIN >= 0.62f)
    }

    /** Sumbu tertukar pun tetap dijepit; fraksi 0,99 tidak boleh lolos utuh. */
    @Test
    fun `sumbu tertukar tetap dijepit ke rentang sah`() {
        val r = Typography.putuskan(
            gaya = gaya(Typography.Arah.TEGAK, fw = 0.02f, fh = 0.99f, rasio = 0.62f),
            ukuranMuat = 60,
            panjangTeks = 40,
            bahasaTegak = false
        )

        assertTrue(r.skalaLebar <= Typography.ISI_MAKS)
        assertTrue(r.skalaTinggi >= Typography.ISI_MIN)
    }

    /** Gaya tak terukur tetap memakai bawaan simetris seperti sebelumnya. */
    @Test
    fun `gaya tak terukur memakai bawaan`() {
        val r = Typography.putuskan(
            gaya = Typography.Gaya.BAWAAN,
            ukuranMuat = 50,
            panjangTeks = 30,
            bahasaTegak = false
        )

        assertEquals(Typography.ISI_BAWAAN, r.skalaLebar, 1e-6f)
        assertEquals(Typography.ISI_BAWAAN, r.skalaTinggi, 1e-6f)
    }

    /**
     * ukur() harus mengisi kedua fraksi mentah, bukan hanya rasio simetris.
     * Blok 40x150 di dalam balon 200x200 = 0,20 lebar dan 0,75 tinggi.
     */
    @Test
    fun `ukur mengisi fraksi tiap sumbu`() {
        val putih = 0xFFFFFFFF.toInt()
        val hitam = 0xFF000000.toInt()
        val p = IntArray(200 * 200) { putih }
        for (y in 25 until 175) for (x in 80 until 120) p[y * 200 + x] = hitam

        val g = Typography.ukur(
            p, 200, 200,
            intArrayOf(0, 0, 200, 200),
            intArrayOf(80, 25, 120, 175)
        )

        assertEquals(0.20f, g.fraksiLebar, 0.01f)
        assertEquals(0.75f, g.fraksiTinggi, 0.01f)
    }
}
