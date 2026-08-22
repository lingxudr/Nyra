package com.nyra.comic

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode

/**
 * Ronde 39. `shrinkIfTooTall` dulu mengembalikan salinan kecil tanpa
 * membebaskan bitmap ASLI, dan pemanggilnya baru membereskan keduanya setelah
 * mosaik selesai dirakit. Artinya untuk sesaat 20 crop asli DAN 20 salinannya
 * hidup bersamaan — puncak memori dua kali lipat, dikalikan lagi dengan jumlah
 * request paralel. Itu penyumbang terbesar proses dibunuh sistem.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PuncakMemoriMosaikTest {

    private fun crop(id: String, w: Int, h: Int) =
        Mosaic.Crop(id, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))

    /**
     * Saat pengecilan benar-benar terjadi, bitmap asli harus SUDAH dibebaskan
     * begitu fungsinya kembali — bukan ditunda ke pemanggil.
     */
    @Test
    fun bitmapAsliDibebaskanSaatDikecilkan() {
        val asli = (1..10).map { crop("$it", 400, 1000) }
        val hasil = Mosaic.shrinkIfTooTall(asli, 3000, 10, 20)

        assertTrue("harus benar-benar mengecilkan", hasil[0].bitmap.height < 1000)
        asli.forEach {
            assertTrue("bitmap asli harus sudah di-recycle", it.bitmap.isRecycled)
        }
        hasil.forEach {
            assertFalse("hasil tidak boleh ikut di-recycle", it.bitmap.isRecycled)
        }
        hasil.forEach { it.bitmap.recycle() }
    }

    /**
     * Kalau semuanya sudah muat, daftar ASLI dikembalikan apa adanya dan tidak
     * boleh ada yang dibebaskan — pemanggil masih akan merakitnya jadi mosaik.
     */
    @Test
    fun yangSudahMuatTidakDisentuh() {
        val asli = (1..3).map { crop("$it", 300, 200) }
        val hasil = Mosaic.shrinkIfTooTall(asli, 6000, 10, 20)

        assertTrue("harus daftar yang sama", hasil === asli)
        hasil.forEach {
            assertFalse("tidak boleh di-recycle", it.bitmap.isRecycled)
        }
        hasil.forEach { it.bitmap.recycle() }
    }

    /** Identitas dan urutan nomor tetap utuh setelah pengecilan. */
    @Test
    fun nomorTetapUtuhDanBerurutan() {
        val asli = (1..8).map { crop("$it", 400, 900) }
        val hasil = Mosaic.shrinkIfTooTall(asli, 2500, 10, 20)

        assertEquals(8, hasil.size)
        assertEquals((1..8).map { "$it" }, hasil.map { it.id })
        hasil.forEach { it.bitmap.recycle() }
    }

    /** Hasil pengecilan benar-benar muat dalam batas tinggi yang diminta. */
    @Test
    fun hasilMuatDalamBatas()  {
        val maxHeight = 2500
        val spacing = 10
        val pad = 20
        val asli = (1..8).map { crop("$it", 400, 900) }
        val hasil = Mosaic.shrinkIfTooTall(asli, maxHeight, spacing, pad)

        val total = hasil.sumOf { it.bitmap.height } + hasil.size * spacing + pad
        assertTrue("total $total > batas $maxHeight", total <= maxHeight)
        hasil.forEach { it.bitmap.recycle() }
    }
}
