package com.nyra.comic

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Paket font unduhan: pemilihan aksara dan status pemasangan.
 *
 * Cakupan cmap font bawaan memang tipis (kosugi.ttf: 0 glif Hangul, 0 Thai),
 * tapi itu TIDAK berarti aksara tersebut mustahil digambar: Android menambal
 * glif yang absen dari rantai font sistem. Karena itu kebutuhan unduhan
 * diputuskan lewat perluUnduh() yang bertanya ke perangkat, dan tes di sini
 * menguji logika keputusannya dengan penyedia glif palsu — bukan mengklaim
 * apa pun soal tofu di perangkat nyata.
 *
 * Bukti render TIDAK bisa dibuat di sini: Robolectric membawa set font yang
 * jauh lebih luas daripada ponsel mana pun (hasGlyph mengembalikan true bahkan
 * untuk Cuneiform dan Linear-B), jadi tes piksel apa pun di lingkungan ini
 * menyesatkan. Verifikasi tofu hanya sah di perangkat sungguhan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FontPackTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ---------- deteksi aksara ----------

    @Test
    fun kalimatKoreaTerbacaHangulMeskiAdaHanja() {
        // Kasus nyata: Hanja tunggal di kalimat Korea. Memilih font dari
        // karakter non-Latin PERTAMA akan melempar ini ke font Mandarin, yang
        // sama sekali tidak punya Hangul.
        assertEquals(FontPack.Aksara.HANGUL, FontPack.aksara("漢字 그는 조용히 문을 열었다"))
    }

    @Test
    fun kalimatJepangCampurKanjiTetapJepang() {
        assertEquals(FontPack.Aksara.JEPANG, FontPack.aksara("お前はもう死んでいる"))
        assertEquals(FontPack.Aksara.JEPANG, FontPack.aksara("東京に行きます"))
    }

    @Test
    fun mandarinTanpaKanaTerbacaHan() {
        assertEquals(FontPack.Aksara.HAN, FontPack.aksara("你到底在说什么"))
    }

    @Test
    fun thaiMenangMeskiBercampurLatin() {
        assertEquals(FontPack.Aksara.THAI, FontPack.aksara("เขาเปิดประตู OK 2026"))
    }

    @Test
    fun latinDanSirilikTidakButuhUnduhan() {
        assertEquals(FontPack.Aksara.LATIN, FontPack.aksara("Halo, apa kabar?"))
        assertEquals(FontPack.Aksara.SIRILIK, FontPack.aksara("Что происходит"))
        assertNull(FontPack.butuhUntuk("Halo, apa kabar?"))
        assertNull("kosugi menutup sirilik cukup untuk kalimat uji",
            FontPack.butuhUntuk("Что происходит"))
        assertNull("kosugi menutup kana", FontPack.butuhUntuk("お前はもう死んでいる"))
    }

    @Test
    fun teksKosongTidakMemicuUnduhan() {
        assertEquals(FontPack.Aksara.LATIN, FontPack.aksara(""))
        assertNull(FontPack.butuhUntuk(""))
    }

    // ---------- pemetaan ke paket ----------

    @Test
    fun setiapAksaraRusakDipetakanKePaketYangBenar() {
        assertSame(FontPack.KR, FontPack.butuhUntuk("그는 조용히 문을 열었다"))
        assertSame(FontPack.SC, FontPack.butuhUntuk("你到底在说什么"))
        assertSame(FontPack.TH, FontPack.butuhUntuk("เขาเปิดประตู"))
    }

    @Test
    fun bahasaTujuanDipetakanKePaketYangMencakupnya() {
        assertSame(FontPack.KR, FontPack.untukBahasa("Korean"))
        assertSame(FontPack.SC, FontPack.untukBahasa("Mandarin"))
        assertSame(FontPack.TH, FontPack.untukBahasa("Thai"))
        assertNull(FontPack.untukBahasa("Indonesian"))
        assertNull(FontPack.untukBahasa("Japanese"))
    }

    @Test
    fun namaBahasaTidakPekaHurufBesar() {
        assertSame(FontPack.KR, FontPack.untukBahasa("korean"))
        assertSame(FontPack.TH, FontPack.untukBahasa("THAI"))
    }

    @Test
    fun semuaBahasaTujuanTerdaftarPunyaJawaban() {
        // Tidak boleh ada bahasa di daftar UI yang jatuh ke perilaku tak
        // terdefinisi; masing-masing harus jelas butuh paket atau tidak.
        for (b in Langs.CHOICES) {
            val hasil = runCatching { FontPack.untukBahasa(b) }
            assertTrue("bahasa $b melempar galat", hasil.isSuccess)
        }
        assertNotNull("Korea harus punya paket yang mencakupnya", FontPack.untukBahasa(
            Langs.CHOICES.first { it.contains("Korea", true) }))
    }

    // ---------- keputusan perlu-unduh (deteksi runtime) ----------

    @Test
    fun perangkatYangSanggupMenggambarTidakDimintaMengunduh() {
        // Kasus mayoritas: ponsel biasa menambal Hangul/Thai/Han dari font
        // sistem. Meminta unduhan 13 MB di sini adalah alarm palsu — persis
        // bug yang ditemukan setelah ronde 22.
        for (item in FontPack.SEMUA) {
            assertFalse(
                "${item.id}: perangkat sanggup, jangan minta unduhan",
                FontPack.perluUnduh(item, terpasang = false) { true }
            )
        }
    }

    @Test
    fun romTanpaFontAksaraItuDimintaMengunduh() {
        // Kasus yang membenarkan keberadaan fitur ini: ROM dipangkas.
        for (item in FontPack.SEMUA) {
            assertTrue(
                "${item.id}: perangkat tak sanggup, harus ditawari unduhan",
                FontPack.perluUnduh(item, terpasang = false) { false }
            )
        }
    }

    @Test
    fun paketYangSudahTerpasangTidakDitawarkanLagi() {
        for (item in FontPack.SEMUA) {
            assertFalse(
                FontPack.perluUnduh(item, terpasang = true) { false }
            )
        }
    }

    @Test
    fun satuGlifHilangSudahCukupMemicuTawaran() {
        // Cakupan sebagian tetap merusak halaman, jadi jangan menunggu
        // seluruh contoh gagal.
        val item = FontPack.KR
        val contoh = FontPack.contoh(item)
        assertTrue("contoh tidak boleh kosong", contoh.isNotEmpty())
        val rusak = contoh.first()
        assertTrue(FontPack.perluUnduh(item, terpasang = false) { it != rusak })
    }

    @Test
    fun setiapPaketPunyaKarakterContoh() {
        for (item in FontPack.SEMUA) {
            val c = FontPack.contoh(item)
            assertTrue("${item.id} tanpa contoh selalu dianggap aman", c.isNotEmpty())
            // Contoh harus benar-benar beraksara paket itu, bukan Latin.
            for (s in c) {
                assertFalse("${item.id}: contoh '$s' jangan Latin",
                    FontPack.aksara(s) == FontPack.Aksara.LATIN)
            }
        }
    }

    // ---------- status pemasangan ----------

    @Test
    fun berkasSetengahJadiTidakDianggapTerpasang() {
        // Inti pemeriksaan ukuran: unduhan terpotong yang lolos akan membuat
        // Typeface.createFromFile gagal di tengah penggambaran bab.
        val f = FontPack.berkas(ctx, FontPack.TH)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(1024))
        assertFalse(FontPack.terpasang(ctx, FontPack.TH))
        assertNull("font rusak tidak boleh dimuat", FontPack.muat(ctx, FontPack.TH))
        f.delete()
    }

    @Test
    fun berkasBerukuranTepatDianggapTerpasang() {
        val f = FontPack.berkas(ctx, FontPack.TH)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(FontPack.TH.ukuran.toInt()))
        assertTrue(FontPack.terpasang(ctx, FontPack.TH))
        assertEquals(FontPack.TH.ukuran, FontPack.terpakai(ctx))
        f.delete()
        assertFalse(FontPack.terpasang(ctx, FontPack.TH))
    }

    @Test
    fun hapusMembersihkanSisaUnduhan() {
        val dir = FontPack.dir(ctx).apply { mkdirs() }
        val utuh = FontPack.berkas(ctx, FontPack.KR).apply { writeBytes(ByteArray(100)) }
        val part = File(dir, "${FontPack.KR.namaBerkas}.part").apply { writeBytes(ByteArray(50)) }
        val bebas = FontPack.hapus(ctx, FontPack.KR)
        assertEquals(150L, bebas)
        assertFalse(utuh.exists())
        assertFalse("sisa .part harus ikut dibuang", part.exists())
    }

    @Test
    fun metadataPaketMasukAkal() {
        // Ukuran dan hash adalah gerbang keamanan unduhan; nilai kosong atau
        // nol berarti gerbangnya mati.
        for (item in FontPack.SEMUA) {
            assertTrue("${item.id} ukuran", item.ukuran > 10_000L)
            assertEquals("${item.id} panjang sha256", 64, item.sha256.length)
            assertTrue("${item.id} https", item.alamat.startsWith("https://"))
            assertTrue("${item.id} judul", item.judul.isNotBlank())
        }
        assertEquals("tiga paket", 3, FontPack.SEMUA.size)
        assertEquals(FontPack.SEMUA.sumOf { it.ukuran }, FontPack.UKURAN_TOTAL)
        // Nama berkas harus unik; bertabrakan berarti satu menimpa yang lain.
        assertEquals(3, FontPack.SEMUA.map { it.namaBerkas }.toSet().size)
    }
}
