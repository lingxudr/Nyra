package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Titik simpan arsip.
 *
 * Yang paling berbahaya di sini bukan gagal melanjutkan, melainkan melanjutkan
 * dari titik simpan yang SALAH: bab lain yang kebetulan bernama sama, atau
 * jalan sebelumnya dengan bahasa sasaran berbeda. Keduanya menghasilkan bab
 * yang tampak berhasil padahal isinya campur aduk.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
class ResumeTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        File(ctx.filesDir, "resume").deleteRecursively()
    }

    // ---- kunci ----

    @Test
    fun arsipSamaMenghasilkanKunciSama() {
        assertEquals(Resume.kunci("bab01.cbz", 1234, "indonesian"),
            Resume.kunci("bab01.cbz", 1234, "indonesian"))
    }

    @Test
    fun bahasaBerbedaTidakBolehBerbagiTitikSimpan() {
        assertNotEquals(
            "mengganti bahasa harus memulai titik simpan baru",
            Resume.kunci("bab01.cbz", 1234, "indonesian"),
            Resume.kunci("bab01.cbz", 1234, "english")
        )
    }

    @Test
    fun namaSamaTapiIsiBerbedaTidakBolehTertukar() {
        // "01.cbz" adalah nama paling lazim di dunia manga.
        assertNotEquals(
            Resume.kunci("01.cbz", 1_000_000, "indonesian"),
            Resume.kunci("01.cbz", 2_500_000, "indonesian")
        )
    }

    @Test
    fun kunciAmanDipakaiSebagaiNamaFolder() {
        val k = Resume.kunci("な ま/え\\..cbz", 10, "indonesian")
        assertFalse("tidak boleh ada pemisah jalur", k.contains('/') || k.contains('\\'))
        assertTrue(k.isNotEmpty())
        assertTrue("panjang harus terkendali", k.length < 80)
    }

    // ---- simpan / muat ----

    private fun berkasPng(nama: String): File {
        val f = File(ctx.cacheDir, nama)
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    @Test
    fun halamanSelesaiBertahanSetelahFolderKerjaDihapus() {
        val kunci = Resume.kunci("a.cbz", 1, "indonesian")
        val titik = Resume.muat(ctx, kunci)

        val kerja = File(ctx.cacheDir, "kerja").apply { mkdirs() }
        val hasil = File(kerja, "p0.png")
        berkasPng("tmp0.png").copyTo(hasil, overwrite = true)

        assertTrue(Resume.catat(titik, "001.jpg", hasil))
        Resume.simpan(titik)

        // Inilah yang terjadi sungguhan: workRoot dihapus di blok finally.
        kerja.deleteRecursively()

        val lagi = Resume.muat(ctx, kunci)
        assertEquals(1, lagi.jumlah)
        assertTrue("salinan permanen harus tetap ada", lagi.selesai["001.jpg"]!!.isFile)
    }

    @Test
    fun berkasHilangTidakDilaporkanSebagaiSelesai() {
        val kunci = Resume.kunci("b.cbz", 1, "indonesian")
        val titik = Resume.muat(ctx, kunci)
        Resume.catat(titik, "001.jpg", berkasPng("tmp1.png"))
        Resume.simpan(titik)

        // Sistem membersihkan penyimpanan aplikasi.
        titik.selesai["001.jpg"]!!.delete()

        assertEquals("entri tanpa berkas harus dibuang", 0, Resume.muat(ctx, kunci).jumlah)
    }

    @Test
    fun indeksRusakDiperlakukanSebagaiBelumAda() {
        val kunci = Resume.kunci("c.cbz", 1, "indonesian")
        val dir = Resume.dirUntuk(ctx, kunci).apply { mkdirs() }
        File(dir, "index.json").writeText("{ bukan json")
        assertEquals(0, Resume.muat(ctx, kunci).jumlah)
    }

    @Test
    fun bersihkanMenghapusTitikSimpan() {
        val kunci = Resume.kunci("d.cbz", 1, "indonesian")
        val titik = Resume.muat(ctx, kunci)
        Resume.catat(titik, "001.jpg", berkasPng("tmp2.png"))
        Resume.simpan(titik)
        Resume.bersihkan(titik)
        assertEquals(0, Resume.muat(ctx, kunci).jumlah)
    }

    @Test
    fun pruneMenyisakanYangTerbaruSaja() {
        for (i in 1..8) {
            val t = Resume.muat(ctx, Resume.kunci("x$i.cbz", i.toLong(), "indonesian"))
            Resume.catat(t, "001.jpg", berkasPng("tp$i.png"))
            Resume.simpan(t)
        }
        Resume.prune(ctx, 5)
        val sisa = File(ctx.filesDir, "resume").listFiles()?.count { it.isDirectory } ?: 0
        assertEquals(5, sisa)
    }

    // ---- ekstraksi EPUB sungguhan ----

    /** EPUB nyata: ZIP dengan container.xml, OPF, XHTML, dan gambar. */
    private fun buatEpub(f: File) {
        fun png(): ByteArray {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            return bos.toByteArray()
        }
        ZipOutputStream(f.outputStream()).use { z ->
            fun tulis(nama: String, isi: ByteArray) {
                z.putNextEntry(ZipEntry(nama)); z.write(isi); z.closeEntry()
            }
            tulis("mimetype", "application/epub+zip".toByteArray())
            tulis("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf"
                    media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray())
            tulis("OEBPS/content.opf", """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <manifest>
                    <item id="t1" href="text/t1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="t2" href="text/t2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="i1" href="images/zzz.png" media-type="image/png"/>
                    <item id="i2" href="images/aaa.png" media-type="image/png"/>
                  </manifest>
                  <spine><itemref idref="t1"/><itemref idref="t2"/></spine>
                </package>
            """.trimIndent().toByteArray())
            tulis("OEBPS/text/t1.xhtml", """<html><body><img src="../images/zzz.png"/></body></html>""".toByteArray())
            tulis("OEBPS/text/t2.xhtml", """<html><body><img src="../images/aaa.png"/></body></html>""".toByteArray())
            tulis("OEBPS/images/zzz.png", png())
            tulis("OEBPS/images/aaa.png", png())
        }
    }

    @Test
    fun epubDiekstrakDalamUrutanBaca() {
        val epub = File(ctx.cacheDir, "buku.epub")
        buatEpub(epub)
        val dir = File(ctx.cacheDir, "ex_epub").apply { deleteRecursively() }

        val hasil = Storage.extractEpub(epub, dir)
        assertEquals(2, hasil.size)
        assertTrue(
            "halaman pertama harus zzz.png meski namanya paling akhir secara alfabet",
            hasil[0].name.endsWith("zzz.png")
        )
        assertTrue(hasil[1].name.endsWith("aaa.png"))
        assertTrue("awalan indeks harus menjaga urutan", hasil[0].name < hasil[1].name)
        for (f in hasil) assertTrue("berkas harus benar-benar terisi", f.length() > 0)
    }

    @Test
    fun zipBiasaBukanEpubMenghasilkanDaftarKosong() {
        val zip = File(ctx.cacheDir, "biasa.cbz")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("001.png")); z.write(ByteArray(16)); z.closeEntry()
        }
        assertTrue(
            "tanpa metadata EPUB harus menyerah supaya pemanggil memakai urutan nama",
            Storage.extractEpub(zip, File(ctx.cacheDir, "ex_bukan")).isEmpty()
        )
    }
}
