package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Ekspor CBZ ujung-ke-ujung dengan PNG sungguhan.
 *
 * CbzWriterTest membuktikan ZIP-nya sah untuk byte sembarang; yang ini
 * membuktikan hal yang sebenarnya dipedulikan pengguna: gambar yang keluar
 * dari arsip masih gambar yang sama, dan berkasnya benar-benar mendarat di
 * folder keluaran lewat jalur Storage.writeToTree yang dipakai aplikasi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzExportTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun halamanPng(w: Int, h: Int, warna: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(warna)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    @Test
    fun arsipDitulisKeFolderKeluaranDanGambarnyaUtuh() {
        val out = File(ctx.cacheDir, "out_cbz").apply { deleteRecursively(); mkdirs() }
        val tree = android.net.Uri.fromFile(out)

        val warna = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val halaman = warna.mapIndexed { i, c ->
            CbzWriter.Halaman(CbzWriter.namaEntri(i, "hal.png"), halamanPng(40, 60, c))
        }

        val nama = CbzWriter.namaArsip("Bab 1", "ID")
        val uri = Storage.writeToTree(ctx, tree, "ID", nama, CbzWriter.MIME) { os ->
            CbzWriter.tulis(os, halaman)
        }
        assertNotNull("arsip harus tertulis", uri)

        val berkas = File(File(out, "ID"), nama)
        assertTrue("berkas nyata harus ada di disk", berkas.isFile)
        assertTrue("arsip tidak boleh kosong", berkas.length() > 100)

        // Baca ulang seperti pembaca komik: buka ZIP, urutkan nama, dekode PNG.
        val isi = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(berkas.readBytes())).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) { isi[e.name] = zin.readBytes(); zin.closeEntry(); e = zin.nextEntry }
        }
        assertEquals(3, isi.size)
        assertEquals("urutan nama harus sudah benar", isi.keys.toList(), isi.keys.sorted())

        for ((i, nm) in isi.keys.withIndex()) {
            val bytes = isi[nm]!!
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("entri $nm harus PNG yang bisa didekode", bmp)
            assertEquals(40, bmp!!.width)
            assertEquals(60, bmp.height)
            assertEquals("warna halaman ${i + 1} harus utuh", warna[i], bmp.getPixel(20, 30))
            bmp.recycle()
        }
    }

    @Test
    fun namaArsipMemakaiKodeBahasaSepertiFolderKeluaran() {
        // Arsip dan subfolder keluaran harus memakai kode yang sama, kalau
        // tidak pengguna mendapat dua bab berbeda dengan nama berkas identik.
        val sub = Langs.code("indonesian").uppercase()
        val nama = CbzWriter.namaArsip("Bab 7", sub)
        assertTrue("nama $nama harus memuat $sub", nama.endsWith("_$sub.cbz"))
    }
}
