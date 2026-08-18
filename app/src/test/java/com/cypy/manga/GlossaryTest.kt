package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser glosarium dan bentuk suntikan prompt-nya.
 *
 * Tidak butuh Robolectric: seluruh modulnya murni Kotlin + org.json, dan
 * org.json sudah tersedia di classpath unit test.
 */
class GlossaryTest {

    // ---------- format TSV / TXT ----------

    @Test
    fun tsvTerbacaLengkapDenganCatatan() {
        val g = Glossary.parse(
            """
            # glosarium bab 1
            Pellin	Pellin	nama tokoh utama
            青魔法塔	Menara Sihir Biru	nama tempat
            Tower Master	Master Menara
            """.trimIndent()
        )
        assertEquals(3, g.entries.size)
        assertEquals("Pellin", g.entries[0].source)
        assertEquals("nama tokoh utama", g.entries[0].note)
        assertEquals("Menara Sihir Biru", g.entries[1].target)
        assertEquals("", g.entries[2].note)
        assertTrue(g.conflicts.isEmpty())
    }

    @Test
    fun tandaSamaDenganJugaDiterima() {
        val g = Glossary.parse("Pellin=Pellin\nGuild=Serikat")
        assertEquals(2, g.entries.size)
        assertEquals("Serikat", g.entries[1].target)
    }

    @Test
    fun barisKomentarDanKosongDiabaikan() {
        val g = Glossary.parse("# judul\n\n// catatan\nA\tB\n\n")
        assertEquals(1, g.entries.size)
        assertTrue(g.rejected.isEmpty())
    }

    @Test
    fun barisTanpaPemisahDilaporkanBukanDitelan() {
        val g = Glossary.parse("Pellin\tPellin\nbaris rusak tanpa pemisah")
        assertEquals(1, g.entries.size)
        assertEquals(1, g.rejected.size)
        assertTrue(g.rejected[0].contains("baris rusak"))
    }

    /** Istilah boleh mengandung "=", jadi tab harus diperiksa lebih dulu. */
    @Test
    fun tabMenangAtasSamaDengan() {
        val g = Glossary.parse("A=B\tC=D")
        assertEquals(1, g.entries.size)
        assertEquals("A=B", g.entries[0].source)
        assertEquals("C=D", g.entries[0].target)
    }

    // ---------- format JSON ----------

    @Test
    fun jsonObjekTerbaca() {
        val g = Glossary.parse("""{"Pellin": "Pellin", "青魔法塔": "Menara Sihir Biru"}""")
        assertEquals(2, g.entries.size)
        assertEquals("Menara Sihir Biru", g.entries.first { it.source == "青魔法塔" }.target)
    }

    @Test
    fun jsonArrayDenganNamaKolomAlternatif() {
        val g = Glossary.parse(
            """[{"source":"Pellin","target":"Pellin","note":"tokoh"},
                {"src":"Guild","dst":"Serikat"},
                {"from":"Mana","to":"Mana"}]"""
        )
        assertEquals(3, g.entries.size)
        assertEquals("tokoh", g.entries[0].note)
        assertEquals("Serikat", g.entries[1].target)
        assertEquals("Mana", g.entries[2].target)
    }

    @Test
    fun jsonRusakDilaporkanBukanMelemparPengecualian() {
        val g = Glossary.parse("""{"Pellin": """)
        assertTrue(g.entries.isEmpty())
        assertTrue(g.rejected.isNotEmpty())
    }

    // ---------- pembersihan ----------

    @Test
    fun istilahBentrokDilaporkanDanYangPertamaMenang() {
        val g = Glossary.parse("Pellin\tPellin\nPellin\tPerin")
        assertEquals(1, g.entries.size)
        assertEquals("Pellin", g.entries[0].target)
        assertEquals(1, g.conflicts.size)
        assertTrue(g.conflicts[0].contains("Pellin"))
    }

    @Test
    fun duplikatIdentikBukanKonflik() {
        val g = Glossary.parse("Pellin\tPellin\npellin\tPellin")
        assertEquals(1, g.entries.size)
        assertTrue(g.conflicts.isEmpty())
    }

    @Test
    fun sisiKosongDitolak() {
        val g = Glossary.parse("Pellin\t\n\tKosong\nBagus\tOke")
        assertEquals(1, g.entries.size)
        assertEquals("Bagus", g.entries[0].source)
        assertEquals(2, g.rejected.size)
    }

    @Test
    fun istilahTerlaluPanjangDitolak() {
        val panjang = "x".repeat(500)
        val g = Glossary.parse("$panjang\tpendek\nA\tB")
        assertEquals(1, g.entries.size)
        assertEquals("A", g.entries[0].source)
    }

    @Test
    fun berkasKosongMenghasilkanGlosariumKosong() {
        assertTrue(Glossary.parse("").isEmpty)
        assertTrue(Glossary.parse("   \n  \n").isEmpty)
    }

    // ---------- anggaran ----------

    @Test
    fun jumlahIstilahDibatasi() {
        val besar = (1..500).joinToString("\n") { "istilah$it\tterjemah$it" }
        val g = Glossary.parse(besar)
        assertEquals(500, g.entries.size)
        val dipakai = Glossary.budgetEntries(g.entries)
        assertEquals(Glossary.MAX_ENTRIES, dipakai.size)
        // Urutan berkas dipertahankan: entri pertama harus yang pertama.
        assertEquals("istilah1", dipakai[0].source)
    }

    // ---------- suntikan prompt ----------

    @Test
    fun promptKosongSaatTidakAdaIstilah() {
        assertEquals("", Glossary.promptSection(emptyList()))
    }

    @Test
    fun promptMemuatIstilahDanCatatan() {
        val p = Glossary.promptSection(
            listOf(
                Glossary.Entry("青魔法塔", "Menara Sihir Biru", "nama tempat"),
                Glossary.Entry("Pellin", "Pellin")
            )
        )
        assertTrue(p.contains("GLOSSARY RULE"))
        assertTrue(p.contains("青魔法塔 => Menara Sihir Biru"))
        assertTrue(p.contains("(nama tempat)"))
        assertTrue(p.contains("Pellin => Pellin"))
        // Harus tegas mengalahkan aturan honorifik, kalau tidak model bisa
        // menganggap keduanya setara lalu memilih sendiri.
        assertTrue(p.contains("overrides"))
    }

    @Test
    fun promptMenghormatiBatasJumlah() {
        val banyak = (1..500).map { Glossary.Entry("a$it", "b$it") }
        val p = Glossary.promptSection(banyak)
        assertTrue(p.contains("a1 => b1"))
        assertFalse("istilah di luar batas tidak boleh ikut", p.contains("a300 => b300"))
    }
}
