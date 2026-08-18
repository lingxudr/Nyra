package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Membuktikan halaman yang sama bisa dimuat lalu ditulisi terjemahan
 * lewat semua format masukan yang diklaim didukung: JPG, PNG, WEBP,
 * dan arsip CBZ.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiFormatRenderTest {

    private val box = intArrayOf(0, 89, 282, 409)
    private val text = "AKU ADALAH MERLIN LUCIFER, RAJA IBLIS PERTAMA, PENGUASA KESOMBONGAN..."

    private fun sourceBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("user_page.jpg").readBytes()

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "cypy-fmt").apply { mkdirs() }

    /** Tulis ulang halaman ke format tertentu, lalu muat lewat Storage. */
    private fun writeAs(fmt: Bitmap.CompressFormat, ext: String): File {
        val src = BitmapFactory.decodeByteArray(sourceBytes(), 0, sourceBytes().size)!!
        val f = File(tmpDir(), "page.$ext")
        FileOutputStream(f).use { src.compress(fmt, 92, it) }
        return f
    }

    private fun renderOn(file: File): Int {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = Storage.decodeBitmap(file, 2200)
        assertNotNull("gagal decode ${file.name}", bmp)
        assertTrue("${file.name} harus mutable", bmp!!.isMutable)

        val renderer = TextRenderer(ctx)
        renderer.drawInBubble(
            Canvas(bmp), bmp, text, box[0], box[1], box[2], box[3],
            backgroundPatch = false, targetLanguage = "indonesian",
            maskMarginRatio = 0.12f
        )
        var dark = 0
        for (y in box[1] until minOf(box[3], bmp.height)) {
            for (x in box[0] until minOf(box[2], bmp.width)) {
                val p = bmp.getPixel(x, y)
                val lum = (((p shr 16) and 0xFF) * 299 +
                        ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (lum <= 79) dark++
            }
        }
        return dark
    }

    @Test
    fun jpgPngWebpAllRenderTranslatedText() {
        val jpg = writeAs(Bitmap.CompressFormat.JPEG, "jpg")
        val png = writeAs(Bitmap.CompressFormat.PNG, "png")
        val webp = writeAs(Bitmap.CompressFormat.WEBP, "webp")

        val dJpg = renderOn(jpg)
        val dPng = renderOn(png)
        val dWebp = renderOn(webp)

        println("dark px -> jpg=$dJpg png=$dPng webp=$dWebp")
        assertTrue("jpg tidak tergambar", dJpg > 200)
        assertTrue("png tidak tergambar", dPng > 200)
        assertTrue("webp tidak tergambar", dWebp > 200)
    }

    /** Ekstensi yang dikenali harus mencakup keempat format gambar. */
    @Test
    fun supportedExtensionsCoverCommonFormats() {
        assertTrue("png" in Storage.IMAGE_EXTS)
        assertTrue("jpg" in Storage.IMAGE_EXTS)
        assertTrue("jpeg" in Storage.IMAGE_EXTS)
        assertTrue("webp" in Storage.IMAGE_EXTS)
    }

    /** CBZ berisi campuran jpg/png/webp harus terbaca urut dan lengkap. */
    @Test
    fun cbzWithMixedFormatsExtractsEveryPage() {
        val src = BitmapFactory.decodeByteArray(sourceBytes(), 0, sourceBytes().size)!!
        val cbz = File(tmpDir(), "mixed.cbz")
        ZipOutputStream(FileOutputStream(cbz)).use { zos ->
            val specs = listOf(
                "001.jpg" to Bitmap.CompressFormat.JPEG,
                "002.png" to Bitmap.CompressFormat.PNG,
                "003.webp" to Bitmap.CompressFormat.WEBP
            )
            for ((name, fmt) in specs) {
                zos.putNextEntry(ZipEntry(name))
                val bos = java.io.ByteArrayOutputStream()
                src.compress(fmt, 92, bos)
                zos.write(bos.toByteArray())
                zos.closeEntry()
            }
        }

        val outDir = File(tmpDir(), "unzip").apply { deleteRecursively(); mkdirs() }
        val pages = cbz.inputStream().use { Storage.extractZip(it, outDir) }

        assertEquals("harus 3 halaman", 3, pages.size)
        // extractZip memberi awalan indeks (0_, 1_, ...) supaya urutan arsip
        // tetap terjaga; yang penting nama asli tetap urut 001 -> 002 -> 003.
        assertTrue("urutan salah: ${pages.map { it.name }}", pages[0].name.endsWith("001.jpg"))
        assertTrue("urutan salah: ${pages.map { it.name }}", pages[1].name.endsWith("002.png"))
        assertTrue("urutan salah: ${pages.map { it.name }}", pages[2].name.endsWith("003.webp"))
        // urutan setelah natural sort harus sama dengan urutan ekstraksi
        val sorted = pages.sortedWith(compareBy(Storage.naturalComparator) { it.name })
        assertEquals("natural sort mengubah urutan halaman", pages, sorted)
        for (p in pages) {
            assertTrue("halaman kosong: ${p.name}", p.length() > 0)
            assertNotNull("tak bisa decode ${p.name}", Storage.decodeBitmap(p, 2200))
        }
    }
}
