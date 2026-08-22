package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Berkas kerja (potongan halaman & crop balon) disimpan dengan
 * [Storage.saveKerja].
 *
 * Berkas ini dibaca ulang oleh tahap deteksi, OCR, dan tipografi, jadi
 * kompresinya WAJIB lossless. Kalau sampai lossy, artefak JPEG-like akan
 * membuat ambang tinta (`AMBANG_TINTA`) salah menilai piksel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RoboConfig(sdk = [33])
class SimpanKerjaTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Gambar uji dengan tepi tajam hitam/putih — paling rentan artefak lossy. */
    private fun contoh(w: Int = 40, h: Int = 24): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val hitam = ((x / 3) + (y / 3)) % 2 == 0
                bmp.setPixel(x, y, if (hitam) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }

    @Test
    fun berkasKerjaBenarBenarDitulis() {
        val f = File(folder.root, "kerja.webp")
        Storage.saveKerja(contoh(), f)
        assertTrue("berkas harus ada", f.exists())
        assertTrue("berkas tidak boleh kosong", f.length() > 0)
    }

    @Test
    fun indukDirektoriDibuatOtomatis() {
        val f = File(folder.root, "belum/ada/kerja.webp")
        Storage.saveKerja(contoh(), f)
        assertTrue(f.exists())
    }

    @Test
    fun hasilBacaUlangSamaPersisDenganAslinya() {
        val asli = contoh()
        val f = File(folder.root, "kerja.webp")
        Storage.saveKerja(asli, f)

        val ulang = BitmapFactory.decodeFile(f.absolutePath)
        assertTrue("berkas kerja harus bisa dibaca ulang", ulang != null)
        assertEquals(asli.width, ulang.width)
        assertEquals(asli.height, ulang.height)

        var beda = 0
        for (y in 0 until asli.height) {
            for (x in 0 until asli.width) {
                if (asli.getPixel(x, y) != ulang.getPixel(x, y)) beda++
            }
        }
        assertEquals("penyimpanan berkas kerja harus lossless", 0, beda)
    }

    @Test
    fun ukuranPiksellGanjilTetapUtuh() {
        // Lebar/tinggi ganjil memicu pembulatan subsampling pada kompresi lossy.
        val asli = contoh(w = 37, h = 21)
        val f = File(folder.root, "ganjil.webp")
        Storage.saveKerja(asli, f)

        val ulang = BitmapFactory.decodeFile(f.absolutePath)
        assertEquals(37, ulang.width)
        assertEquals(21, ulang.height)
        assertEquals(asli.getPixel(0, 0), ulang.getPixel(0, 0))
        assertEquals(asli.getPixel(36, 20), ulang.getPixel(36, 20))
    }
}
