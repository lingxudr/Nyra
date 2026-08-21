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
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode

/**
 * Membuktikan penimpaan gaya benar-benar mengubah piksel, bukan cuma mengubah
 * struktur data.
 *
 * Uji satuan pada [Typography.putuskan] hanya menunjukkan angkanya berubah.
 * Yang penting bagi pengguna adalah hasil gambarnya berubah - dan itu hanya
 * terlihat kalau teks benar-benar dirender.
 *
 * GraphicsMode.NATIVE wajib: pada mode LEGACY bawaan Robolectric, perintah
 * Canvas diabaikan diam-diam sehingga setiap perbandingan piksel akan lolos
 * secara palsu.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GayaManualRenderTest {

    private val teks = "KENAPA KAU ADA DI SINI SEKARANG"
    private val x1 = 20
    private val y1 = 20
    private val x2 = 220
    private val y2 = 180

    private fun renderer() =
        TextRenderer(ApplicationProvider.getApplicationContext<android.content.Context>())

    /** Menggambar [gaya] pada kanvas putih dan mengembalikan piksel hasilnya. */
    private fun gambar(gaya: Typography.Gaya): IntArray {
        val bmp = Bitmap.createBitmap(260, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        renderer().drawInBubble(
            canvas, bmp, teks, x1, y1, x2, y2,
            backgroundPatch = false,
            targetLanguage = "Indonesian",
            maskMarginRatio = 0.06f,
            gaya = gaya
        )
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    /** Berapa piksel yang bukan putih - ukuran kasar "berapa banyak tinta". */
    private fun tinta(px: IntArray): Int = px.count { (it and 0xFFFFFF) != 0xFFFFFF }

    private fun beda(a: IntArray, b: IntArray): Int =
        a.indices.count { a[it] != b[it] }

    private val terukur = Typography.Gaya(
        arah = Typography.Arah.DATAR,
        rasioIsi = 0.70f,
        kepadatan = 0.06f,
        tebalGoresan = 2f,
        berat = Typography.Berat.NORMAL,
        terukur = true
    )

    @Test
    fun menebalkanHurufMengubahPiksel() {
        val normal = gambar(terukur)
        val tebal = gambar(terukur.copy(berat = Typography.Berat.TEBAL, dikunci = true))

        assertTrue("render normal harus menghasilkan tinta", tinta(normal) > 200)
        assertTrue(
            "menebalkan harus mengubah gambar, beda=${beda(normal, tebal)}",
            beda(normal, tebal) > 100
        )
        assertTrue(
            "huruf tebal harus memakai lebih banyak tinta: ${tinta(normal)} -> ${tinta(tebal)}",
            tinta(tebal) > tinta(normal)
        )
    }

    @Test
    fun memperbesarIsiMembuatTeksLebihBesar() {
        val kecil = gambar(terukur.copy(rasioIsi = 0.58f, dikunci = true))
        val besar = gambar(terukur.copy(rasioIsi = 0.92f, dikunci = true))
        assertTrue(
            "isi lebih besar harus memakai lebih banyak tinta: ${tinta(kecil)} -> ${tinta(besar)}",
            tinta(besar) > tinta(kecil)
        )
    }

    @Test
    fun gayaManualBerlakuWalauTidakTerukur() {
        // Balon yang gagal diukur: tanpa penanganan khusus, TextRenderer akan
        // pulang lebih awal dan pilihan pengguna tidak berpengaruh sama sekali.
        val bawaan = gambar(Typography.Gaya.BAWAAN)
        val manual = gambar(
            Typography.Gaya.BAWAAN.copy(
                berat = Typography.Berat.TEBAL,
                rasioIsi = 0.92f,
                dikunci = true
            )
        )
        assertTrue(
            "gaya manual pada balon tak terukur harus tetap mengubah gambar",
            beda(bawaan, manual) > 100
        )
    }

    @Test
    fun skalaTerkunciBerlakuPadaBalonTakTerukur() {
        // Sengaja HANYA membedakan rasioIsi, dan sengaja pada balon yang tidak
        // terukur. Ketebalan dan jarak baris ditangani baris kode lain, jadi
        // kalau ikut diubah, keduanya akan menutupi kerusakan pada jalur skala
        // di settingUntuk() dan uji ini lolos secara palsu.
        val dasar = Typography.Gaya.BAWAAN
        val kecil = gambar(dasar.copy(rasioIsi = 0.56f, dikunci = true))
        val besar = gambar(dasar.copy(rasioIsi = 0.92f, dikunci = true))
        assertTrue(
            "skala terkunci harus berlaku walau balon tak terukur: " +
                "${tinta(kecil)} vs ${tinta(besar)}",
            tinta(besar) > tinta(kecil)
        )
    }

    @Test
    fun tanpaKunciHasilnyaIdentikDenganSebelumFiturIni() {
        // Paritas: dua render dari gaya yang sama persis harus sama persis.
        // Ini yang menjamin fitur penimpaan tidak menggeser hasil lama.
        val a = gambar(terukur)
        val bb = gambar(terukur)
        assertEquals("render harus deterministik", 0, beda(a, bb))
    }

    @Test
    fun jarakBarisPaksaMengubahTataLetak() {
        val rapat = gambar(terukur.copy(spasiPaksa = Typography.SPASI_RAPAT, dikunci = true))
        val longgar = gambar(terukur.copy(spasiPaksa = Typography.SPASI_LONGGAR, dikunci = true))
        assertTrue(
            "jarak baris berbeda harus menghasilkan tata letak berbeda",
            beda(rapat, longgar) > 50
        )
    }

    @Test
    fun teksTetapDiDalamBalon() {
        // Penimpaan tidak boleh membuat teks meluber keluar kotak balon.
        val px = gambar(terukur.copy(rasioIsi = 0.92f, berat = Typography.Berat.TEBAL, dikunci = true))
        var luar = 0
        for (y in 0 until 220) {
            for (x in 0 until 260) {
                val di = x in x1..x2 && y in y1..y2
                if (!di && (px[y * 260 + x] and 0xFFFFFF) != 0xFFFFFF) luar++
            }
        }
        assertTrue("teks tidak boleh keluar balon, ada $luar piksel di luar", luar == 0)
    }
}
