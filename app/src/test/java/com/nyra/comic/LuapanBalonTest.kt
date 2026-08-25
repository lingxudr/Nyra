package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config as RoboConfig

/**
 * Cacat lapangan ronde 40 C: teks meluap keluar garis balon dan bertindih
 * dengan balon tetangga (terlihat di uploads/005.png panel 1).
 *
 * Penyebabnya, [Typography.cariUkuran] mengembalikan batas bawahnya ketika
 * tidak ada ukuran yang muat, dan `max(setting.minFont, ...)` bisa menaikkan
 * kembali ukuran yang sudah ditemukan mengecil. Hasilnya digambar apa adanya.
 *
 * GraphicsMode.NATIVE wajib: pada mode LEGACY perintah Canvas diam-diam tidak
 * melakukan apa-apa, jadi luapan tidak akan terlihat oleh tes.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LuapanBalonTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun kanvas(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    /** Menghitung piksel gelap di dalam persegi tertentu. */
    private fun tinta(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        var n = 0
        for (y in y1.coerceAtLeast(0) until y2.coerceAtMost(bmp.height)) {
            for (x in x1.coerceAtLeast(0) until x2.coerceAtMost(bmp.width)) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) < 100 && Color.green(c) < 100 && Color.blue(c) < 100) n++
            }
        }
        return n
    }

    private fun gambar(
        bmp: Bitmap, teks: String, box: IntArray, gaya: Typography.Gaya = Typography.Gaya.BAWAAN
    ) {
        TextRenderer(ctx).drawInBubble(
            Canvas(bmp), bmp, teks, box[0], box[1], box[2], box[3],
            backgroundPatch = true,
            targetLanguage = "indonesian",
            maskMarginRatio = 0.12f,
            gaya = gaya
        )
    }

    /**
     * Kasus yang paling sering meluap: balon kecil dengan terjemahan panjang.
     * Sebelum penjamin muat, pencarian biner gagal menemukan ukuran yang muat
     * dan mengembalikan minFont, yang tetap terlalu besar untuk kotak ini.
     */
    @Test
    fun teksPanjangDiBalonKecilTidakKeluarKotak() {
        val bmp = kanvas(400, 400)
        val box = intArrayOf(150, 150, 250, 210) // 100x60
        val teks = "KALAU MEMANG BEGITU KENAPA KAU TIDAK BILANG DARI KEMARIN SEBELUM SEMUANYA TERLANJUR JADI SERUMIT INI"

        gambar(bmp, teks, box)

        val luar = tinta(bmp, 0, 0, 400, 400) - tinta(bmp, box[0], box[1], box[2], box[3])
        assertEquals("tidak boleh ada tinta di luar kotak balon", 0, luar)
    }

    /** Satu kata tanpa tanda hubung yang lebih lebar daripada balon. */
    @Test
    fun kataTunggalTerlaluPanjangTetapDijinakkan() {
        val bmp = kanvas(300, 300)
        val box = intArrayOf(120, 130, 180, 170) // 60x40
        gambar(bmp, "KEBERLANGSUNGANNYA", box)

        val luar = tinta(bmp, 0, 0, 300, 300) - tinta(bmp, box[0], box[1], box[2], box[3])
        assertEquals("kata raksasa tidak boleh meluber", 0, luar)
    }

    /**
     * Dua balon bersebelahan rapat seperti di panel 1 halaman JJK. Tinta dari
     * balon kiri tidak boleh masuk ke wilayah balon kanan.
     */
    @Test
    fun balonBertetanggaTidakSalingBertindih() {
        val bmp = kanvas(500, 300)
        val kiri = intArrayOf(40, 60, 230, 170)
        val kanan = intArrayOf(240, 60, 460, 170)

        gambar(bmp, "AKU SUDAH MENDUGA KAU AKAN DATANG KE SINI SENDIRIAN MALAM INI", kiri)
        val masukKanan = tinta(bmp, kanan[0], kanan[1], kanan[2], kanan[3])
        assertEquals("balon kiri merembes ke wilayah balon kanan", 0, masukKanan)

        gambar(bmp, "ITU KARENA TIDAK ADA YANG BISA KUAJAK BICARA SOAL INI", kanan)
        val diLuarKeduanya = tinta(bmp, 0, 0, 500, 300) -
            tinta(bmp, kiri[0], kiri[1], kiri[2], kiri[3]) -
            tinta(bmp, kanan[0], kanan[1], kanan[2], kanan[3])
        assertEquals("tidak ada tinta di luar kedua balon", 0, diLuarKeduanya)
    }

    /**
     * Gaya tategaki terukur - jalur yang menukar sumbu. Penukaran memberi
     * lebar lebih besar, jadi justru di sinilah luapan paling mungkin lolos
     * kalau penjamin muat tidak ada.
     */
    @Test
    fun gayaTategakiTerukurTetapMuat() {
        val bmp = kanvas(400, 500)
        val box = intArrayOf(100, 100, 300, 380)
        val gaya = Typography.Gaya(
            arah = Typography.Arah.TEGAK,
            rasioIsi = 0.62f,
            kepadatan = 0.13f,
            tebalGoresan = 3f,
            berat = Typography.Berat.TEBAL,
            terukur = true,
            fraksiLebar = 0.32f,
            fraksiTinggi = 0.90f
        )

        gambar(bmp, "SEJAK AWAL AKU SUDAH TAHU BAHWA HARI SEPERTI INI PASTI AKAN DATANG JUGA", box, gaya)

        val luar = tinta(bmp, 0, 0, 400, 500) - tinta(bmp, box[0], box[1], box[2], box[3])
        assertEquals("gaya tategaki terukur ikut dijaga penjamin muat", 0, luar)
    }

    /**
     * Penjamin muat tidak boleh membuat teks pendek jadi mungil di balon
     * lapang - itu justru cacat B. Narasi pendek pada balon besar harus
     * memakai bagian tinggi balon yang layak.
     */
    @Test
    fun teksPendekDiBalonBesarTetapBesar() {
        val bmp = kanvas(600, 400)
        val box = intArrayOf(100, 80, 500, 320) // 400x240

        gambar(bmp, "DIA SUDAH PERGI", box)

        // Cari batas atas dan bawah tinta di dalam balon.
        var atas = -1
        var bawah = -1
        for (y in box[1] until box[3]) {
            if (tinta(bmp, box[0], y, box[2], y + 1) > 0) {
                if (atas < 0) atas = y
                bawah = y
            }
        }
        assertTrue("harus ada teks tergambar", atas >= 0)
        val tinggiTinta = (bawah - atas).toFloat()
        val tinggiBalon = (box[3] - box[1]).toFloat()
        assertTrue(
            "teks pendek di balon lapang terlalu kecil: ${tinggiTinta / tinggiBalon}",
            tinggiTinta / tinggiBalon >= 0.12f
        )
    }
}
