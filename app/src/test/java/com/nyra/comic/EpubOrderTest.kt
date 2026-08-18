package com.nyra.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Urutan baca EPUB.
 *
 * Bahaya utamanya sunyi: EPUB adalah ZIP, jadi mengurutkan isinya menurut nama
 * berkas selalu "berhasil" - hanya saja babnya teracak, dan pengguna baru
 * sadar setelah membayar terjemahan seluruh halaman.
 */
class EpubOrderTest {

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    /** Nama berkasnya sengaja bertentangan dengan urutan bacanya. */
    private val opf = """
        <?xml version="1.0"?>
        <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
          <manifest>
            <item id="page9" href="text/p9.xhtml" media-type="application/xhtml+xml"/>
            <item id="page1" href="text/p1.xhtml" media-type="application/xhtml+xml"/>
            <item id="page5" href="text/p5.xhtml" media-type="application/xhtml+xml"/>
            <item id="img9" href="images/z_first.jpg" media-type="image/jpeg"/>
            <item id="img1" href="images/m_second.jpg" media-type="image/jpeg"/>
            <item id="img5" href="images/a_third.jpg" media-type="image/jpeg"/>
            <item id="css" href="style.css" media-type="text/css"/>
          </manifest>
          <spine>
            <itemref idref="page9"/>
            <itemref idref="page1"/>
            <itemref idref="page5"/>
          </spine>
        </package>
    """.trimIndent()

    private fun xhtml(img: String) =
        """<html><body><div><img src="../images/$img" alt=""/></div></body></html>"""

    private val berkas = mapOf(
        "OEBPS/text/p9.xhtml" to xhtml("z_first.jpg"),
        "OEBPS/text/p1.xhtml" to xhtml("m_second.jpg"),
        "OEBPS/text/p5.xhtml" to xhtml("a_third.jpg")
    )

    @Test
    fun containerMenunjukOpf() {
        assertEquals("OEBPS/content.opf", EpubOrder.opfPath(container))
    }

    @Test
    fun containerRusakTidakMeledak() {
        assertEquals(null, EpubOrder.opfPath("<container></container>"))
    }

    /** Inti fitur: urutan spine menang atas urutan nama berkas. */
    @Test
    fun urutanMengikutiSpineBukanNamaBerkas() {
        val urut = EpubOrder.gambarUrut("OEBPS/content.opf", opf) { berkas[it] }
        assertEquals(
            listOf(
                "OEBPS/images/z_first.jpg",
                "OEBPS/images/m_second.jpg",
                "OEBPS/images/a_third.jpg"
            ),
            urut
        )
        // Bukti bahwa pengurutan nama memang akan salah.
        assertTrue(
            "fixture harus memuat konflik urutan supaya tes ini bermakna",
            urut != urut.sorted()
        )
    }

    @Test
    fun berkasBukanGambarDiabaikan() {
        val urut = EpubOrder.gambarUrut("OEBPS/content.opf", opf) { berkas[it] }
        assertTrue(urut.none { it.endsWith(".css") || it.endsWith(".xhtml") })
    }

    /** Sebagian EPUB manga menaruh gambar langsung di spine. */
    @Test
    fun gambarLangsungDiSpineJugaTerbaca() {
        val opf2 = """
            <package><manifest>
              <item id="i1" href="images/a.jpg" media-type="image/jpeg"/>
              <item id="i2" href="images/b.jpg" media-type="image/jpeg"/>
            </manifest><spine>
              <itemref idref="i2"/><itemref idref="i1"/>
            </spine></package>
        """.trimIndent()
        val urut = EpubOrder.gambarUrut("content.opf", opf2) { null }
        assertEquals(listOf("images/b.jpg", "images/a.jpg"), urut)
    }

    @Test
    fun sampulDiluarSpineTetapJadiHalamanPertama() {
        val opf2 = """
            <package><manifest>
              <item id="cov" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              <item id="p1" href="text/p1.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine><itemref idref="p1"/></spine></package>
        """.trimIndent()
        val urut = EpubOrder.gambarUrut("content.opf", opf2) {
            if (it == "text/p1.xhtml") """<img src="../images/satu.png"/>""" else null
        }
        assertEquals(listOf("images/cover.jpg", "images/satu.png"), urut)
    }

    @Test
    fun gambarYangSamaTidakDihitungDuaKali() {
        val opf2 = """
            <package><manifest>
              <item id="p1" href="p1.xhtml" media-type="application/xhtml+xml"/>
              <item id="p2" href="p2.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine><itemref idref="p1"/><itemref idref="p2"/></spine></package>
        """.trimIndent()
        val urut = EpubOrder.gambarUrut("c.opf", opf2) { """<img src="img/x.jpg"/>""" }
        assertEquals(listOf("img/x.jpg"), urut)
    }

    @Test
    fun spineKosongMenyerahKeUrutanNama() {
        val opf2 = """
            <package><manifest>
              <item id="i1" href="a.jpg" media-type="image/jpeg"/>
            </manifest><spine></spine></package>
        """.trimIndent()
        assertTrue(EpubOrder.gambarUrut("c.opf", opf2) { null }.isEmpty())
    }

    // ---- resolusi jalur ----

    @Test
    fun jalurRelatifDigabungTerhadapBerkasnya() {
        assertEquals("OEBPS/images/p1.jpg", EpubOrder.resolve("OEBPS/content.opf", "images/p1.jpg"))
    }

    @Test
    fun titikDuaNaikSatuFolder() {
        assertEquals(
            "OEBPS/images/p1.jpg",
            EpubOrder.resolve("OEBPS/text/p1.xhtml", "../images/p1.jpg")
        )
    }

    @Test
    fun jalurMutlakDanPersenTerbaca() {
        assertEquals("a/b c.jpg", EpubOrder.resolve("OEBPS/x.opf", "/a/b%20c.jpg"))
    }

    @Test
    fun fragmenDanKueriDibuang() {
        assertEquals("OEBPS/a.jpg", EpubOrder.resolve("OEBPS/x.opf", "a.jpg#bagian"))
    }
}
