package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tes ronde 41: akurasi & mutu terjemahan.
 *
 * A. normalisasiTerjemahan — nilai mentah LLM (entitas HTML, kutip luar,
 *    null literal) dibersihkan sebelum digambar, nilai normal dibiarkan.
 * B. siapkanNilai — nilai hampa/null dianggap TIDAK terjawab (diminta ulang),
 *    sedangkan SKIP tetap nilai sah.
 * C. salvageJson — pemulihan token telanjang SKIP/null pada JSON rusak.
 *
 * Kontrak prompt (FIDELITY AND REGISTER RULE) diuji di Ronde41PromptTest.
 */
class Ronde41Test {

    // ------------------------------------------------------ A. normalisasi

    @Test
    fun `nilai normal dibiarkan apa adanya`() {
        val masuk = "Kau sudah tahu?"
        assertEquals(masuk, BoxUtils.normalisasiTerjemahan(masuk))
    }

    @Test
    fun `entitas html diurai sebelum digambar`() {
        assertEquals("Itu & ini", BoxUtils.normalisasiTerjemahan("Itu &amp; ini"))
        assertEquals("<halo>", BoxUtils.normalisasiTerjemahan("&lt;halo&gt;"))
        assertEquals("Kata \"aja\"", BoxUtils.normalisasiTerjemahan("Kata &quot;aja&quot;"))
        assertEquals("A's", BoxUtils.normalisasiTerjemahan("A&#39;s"))
    }

    @Test
    fun `null dan undefined menjadi kosong`() {
        assertEquals("", BoxUtils.normalisasiTerjemahan("null"))
        assertEquals("", BoxUtils.normalisasiTerjemahan("NULL"))
        assertEquals("", BoxUtils.normalisasiTerjemahan("undefined"))
        assertEquals("", BoxUtils.normalisasiTerjemahan("  "))
    }

    @Test
    fun `penanda tak terbaca dinormalkan dan dipertahankan`() {
        // Bentuk yang diminta prompt tetap utuh.
        assertEquals("[?]", BoxUtils.normalisasiTerjemahan("[?]"))
        // Bentuk berjarak disamakan menjadi [?].
        assertEquals("[?]", BoxUtils.normalisasiTerjemahan("[ ? ]"))
        // Entitas HTML untuk kurung juga ikut diurai.
        assertEquals("[?]", BoxUtils.normalisasiTerjemahan("&#91;?&#93;"))
    }

    @Test
    fun `spasi berlebih diratakan`() {
        assertEquals("A B", BoxUtils.normalisasiTerjemahan("A   B"))
        assertEquals("Halo dunia", BoxUtils.normalisasiTerjemahan("Halo\u00A0\u00A0dunia"))
    }

    @Test
    fun `kutip pembungkus luar dilepas`() {
        assertEquals("Halo!", BoxUtils.normalisasiTerjemahan("\"Halo!\""))
        // Kutip keriting pembungkus luar juga dilepas — model sering membungkus
        // dialog dalam kutip yang tidak diminta.
        assertEquals("Halo", BoxUtils.normalisasiTerjemahan("\u201CHalo\u201D"))
        // Dialog berpetik di dalam (dua kutip) tidak boleh dipangkas — bukan
        // pembungkus luar, melainkan kutip ucapan yang sah.
        assertEquals(
            "Dia bilang \"jangan\" dua kali.",
            BoxUtils.normalisasiTerjemahan("Dia bilang \"jangan\" dua kali.")
        )
        // Kutip yang jumlahnya banyak berarti dialog berpola petik dalam:
        // pembungkus luar dibiarkan agar tidak ada petik yang hilang.
        assertEquals(
            "\"Dia bilang \\\"jangan\\\" dua kali.\"",
            BoxUtils.normalisasiTerjemahan("\"Dia bilang \\\"jangan\\\" dua kali.\"")
        )
        // Kutip bergaya Jepang yang sah dibiarkan.
        assertEquals("\u3003\u3003", BoxUtils.normalisasiTerjemahan("\u3003\u3003"))
    }

    // ------------------------------------------------------ B. siapkanNilai

    @Test
    fun `nilai kosong dianggap tidak terjawab`() {
        assertNull(BoxUtils.siapkanNilai(""))
        assertNull(BoxUtils.siapkanNilai("   "))
        assertNull(BoxUtils.siapkanNilai("null"))
        assertNull(BoxUtils.siapkanNilai("UNDEFINED"))
    }

    @Test
    fun `skip tetap nilai sah`() {
        // siapkanNilai menjaga asal huruf; drawText membandingkan dengan huruf
        // besar, jadi 'skip' dan 'SKIP' dua-duanya sah.
        assertEquals("SKIP", BoxUtils.siapkanNilai("SKIP"))
        assertEquals("skip", BoxUtils.siapkanNilai("  skip  "))
    }

    @Test
    fun `normalisasi kemudian siapkan mengembalikan null bagi null literal`() {
        assertNull(BoxUtils.siapkanNilai(BoxUtils.normalisasiTerjemahan("null")))
        assertNull(BoxUtils.siapkanNilai(BoxUtils.normalisasiTerjemahan("\"null\"")))
    }

    // ------------------------------------------------------ C. salvageJson

    @Test
    fun `salvage memulihkan token telanjang pada json rusak`() {
        // Kutip penutup hilang pada entri lain; entri SKIP telanjang utuh.
        val raw = "{\"1\": \"Halo\", \"2\": SKIP, \"3\": Belum sele"
        val hasil = BoxUtils.salvageJson(raw)
        assertEquals("Halo", hasil["1"])
        assertEquals("SKIP", hasil["2"])
    }

    @Test
    fun `null telanjang tidak dianggap jawaban sah`() {
        val raw = "{\"1\": \"Halo\", \"2\": null, \"3\": \"Tunggu\"}"
        val hasil = BoxUtils.salvageJson(raw)
        assertEquals("Halo", hasil["1"])
        assertEquals("Tunggu", hasil["3"])
        assertFalse("null bukan jawaban", hasil.containsKey("2"))
    }

    @Test
    fun `kunci berdigit panjang ikut terbaca`() {
        val raw = "{\"12345\": \"Banyak\", \"6\": \"Enam\"}"
        val hasil = BoxUtils.salvageJson(raw)
        assertEquals("Banyak", hasil["12345"])
        assertEquals("Enam", hasil["6"])
    }

    // ------------------------------------------------------ D. cleanJson

    @Test
    fun `blok kode berlabel bukan json tetap dibersihkan`() {
        val hasil = BoxUtils.cleanJson("```text\n{\"1\": \"Halo!\", \"2\": \"SKIP\"}\n```")
        assertEquals("{\"1\": \"Halo!\", \"2\": \"SKIP\"}", hasil)
    }

    @Test
    fun `label huruf besar ikut dibersihkan`() {
        val hasil = BoxUtils.cleanJson("```JSON\n{\"1\": \"Ayo\"}\n```")
        assertEquals("{\"1\": \"Ayo\"}", hasil)
    }

    @Test
    fun `kurung di dalam nilai tidak memotong objek`() {
        // Nilai terjemahan yang kebetulan memuat kurung kurawal harus utuh.
        val hasil = BoxUtils.cleanJson("{\"1\": \"Halo {kawan}!!\"}")
        assertEquals("{\"1\": \"Halo {kawan}!!\"}", hasil)
    }

    @Test
    fun `koma gantung dibuang`() {
        val hasil = BoxUtils.cleanJson("{\"1\": \"Halo!\", \"2\": \"SKIP\",}")
        assertEquals("{\"1\": \"Halo!\", \"2\": \"SKIP\"}", hasil)
    }

    @Test
    fun `koma gantung di dalam string tidak dibuang`() {
        val hasil = BoxUtils.cleanJson("{\"1\": \"Apa,katamu,\"}")
        assertEquals("{\"1\": \"Apa,katamu,\"}", hasil)
    }

    @Test
    fun `koma gantung array ikut dibersihkan`() {
        val hasil = BoxUtils.cleanJson("{\"1\": [\"a\", \"b\",]}")
        assertEquals("{\"1\": [\"a\", \"b\"]}", hasil)
    }

    @Test
    fun `prosa ber-kurung di depan objek tidak merampas objek`() {
        // Kalimat pengantar memuat {lihat} yang bukan JSON; objek betulan harus
        // tetap terpilih, bukan kotak prosa itu.
        val hasil = BoxUtils.cleanJson("catatan: {lihat} lalu {\"1\": \"Halo!\"} sekian.")
        assertEquals("{\"1\": \"Halo!\"}", hasil)
    }

    @Test
    fun `objek prosa di depan tidak menyandera objek valid berikutnya`() {
        // Calon pertama seimbang tapi bukan JSON (`{lihat}` tanpa nilai), jadi
        // pemindai harus melewatinya memakai objek valid berikutnya.
        val hasil = BoxUtils.cleanJson("{lihat} dan {\"2\": \"Ayo\"}")
        assertEquals("{\"2\": \"Ayo\"}", hasil)
    }

    // ------------------------------------- D. parseHasilTerjemahan (rintel
    //            alasan tiap nomor yang belum terjawab)

    @Test
    fun `nomor dibalas null atau hampa dicatat di kosong bukannya jawaban`() {
        val h = BoxUtils.parseHasilTerjemahan("{\"1\": \"A\", \"3\": null, \"4\": \"\"}")
        assertEquals(mapOf("1" to "A"), h.jawaban)
        assertEquals(setOf("3", "4"), h.kosong)
    }

    @Test
    fun `nomor yang diabaikan model tidak tercatat di kedua golongan`() {
        val h = BoxUtils.parseHasilTerjemahan("{\"1\": \"A\"}")
        assertEquals(setOf("1"), h.jawaban.keys)
        assertTrue(h.kosong.isEmpty())
        // Nomor 2 dan 3 tidak ada di balasan sama sekali: bukan jawaban, bukan
        // kosong — itu golongan ketiga ("diabaikan") yang dihitung pemanggil.
        assertTrue("2" !in h.jawaban && "2" !in h.kosong)
        assertTrue("3" !in h.jawaban && "3" !in h.kosong)
    }

    @Test
    fun `nomor yang dibalas SKIP tetap dianggap jawaban sah`() {
        val h = BoxUtils.parseHasilTerjemahan("{\"1\": \"Halo\", \"2\": \"SKIP\"}")
        assertEquals(mapOf("1" to "Halo", "2" to "SKIP"), h.jawaban)
        assertTrue(h.kosong.isEmpty())
    }

}
