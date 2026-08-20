package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Menggambar balon buatan dengan penata teks sungguhan lalu menyimpan PNG-nya,
 * supaya hasil tipografi bisa dinilai dengan mata, bukan hanya lewat angka.
 *
 * Berbeda dari UserPageRenderTest yang memakai satu halaman nyata, berkas ini
 * memakai balon polos berukuran terkendali sehingga perbedaan penataan terlihat
 * jelas tanpa terganggu artwork di belakangnya.
 *
 * GraphicsMode.NATIVE wajib: pada mode LEGACY bawaan Robolectric, perintah
 * Canvas diam-diam tidak melakukan apa pun sehingga bukti render jadi kosong.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BuktiTipografiTest {

    private fun balonKosong(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        // Garis tepi tipis supaya batas balon terlihat pada gambar bukti.
        val tepi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.rgb(190, 190, 195)
        }
        c.drawRect(1f, 1f, w - 1f, h - 1f, tepi)
        return bmp
    }

    private fun gambar(teks: String, w: Int, h: Int, nama: String): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = balonKosong(w, h)
        val canvas = Canvas(bmp)
        TextRenderer(ctx).drawInBubble(
            canvas, bmp, teks, 0, 0, w, h,
            backgroundPatch = false,
            targetLanguage = "indonesian",
            maskMarginRatio = 0.12f
        )
        val outDir = File(System.getProperty("user.dir"), "build/render-out").apply { mkdirs() }
        FileOutputStream(File(outDir, nama)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun barisTergambar(bmp: Bitmap): Int {
        // Menghitung pita baris: baris piksel yang mengandung tinta gelap,
        // dipisahkan oleh baris kosong.
        var pita = 0
        var didalam = false
        for (y in 0 until bmp.height) {
            var ada = false
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) * 77 + Color.green(p) * 151 + Color.blue(p) * 28) shr 8
                if (lum < 100) { ada = true; break }
            }
            if (ada && !didalam) { pita++; didalam = true }
            else if (!ada) didalam = false
        }
        return pita
    }

    @Test
    fun buktiKalimatPanjangDanPendek() {
        // Kasus yang pada pengukuran memberi perbaikan keseimbangan terbesar:
        // rakus menghasilkan "ORANG ITU SUDAH / LAMA / ..." dengan satu kata
        // menggantung, seimbang membaginya rata.
        val a = gambar("ORANG ITU SUDAH LAMA MENGHILANG TANPA JEJAK", 500, 300, "bukti_a.png")
        val b = gambar("AKU TIDAK PERNAH BILANG BEGITU PADAMU", 420, 300, "bukti_b.png")
        val c = gambar("JANGAN BERGERAK!", 380, 240, "bukti_c.png")
        val d = gambar(
            "AKU ADALAH MERLIN LUCIFER, RAJA IBLIS PERTAMA, PENGUASA KESOMBONGAN...",
            420, 380, "bukti_d.png"
        )
        for ((nama, bmp) in listOf("a" to a, "b" to b, "c" to c, "d" to d)) {
            assertTrue("balon $nama kosong - teks tidak tergambar", barisTergambar(bmp) >= 1)
        }
        println("BUKTI DITULIS: ${File(System.getProperty("user.dir"), "build/render-out")}")
    }

    /** Batas atas dan bawah tinta di dalam gambar. */
    private fun rentangTinta(bmp: Bitmap): Pair<Int, Int> {
        var atas = -1
        var bawah = -1
        for (y in 0 until bmp.height) {
            var ada = false
            for (x in 2 until bmp.width - 2) {
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) * 77 + Color.green(p) * 151 + Color.blue(p) * 28) shr 8
                if (lum < 100) { ada = true; break }
            }
            if (ada) { if (atas < 0) atas = y; bawah = y }
        }
        return atas to bawah
    }

    @Test
    fun tintaTerpusatSecaraTegak() {
        // Menengahkan memakai ascent/descent membuat huruf besar tampak
        // melorot: font menyediakan ruang untuk aksen dan ekor huruf yang
        // tidak dipakai teks komik. Sebelum perbaikan, selisih ruang atas
        // dan bawah mencapai 11-16 px; sesudahnya harus sekitar nol.
        val kasus = listOf(
            Triple("ORANG ITU SUDAH LAMA MENGHILANG TANPA JEJAK", 500, 300),
            Triple("AKU TIDAK PERNAH BILANG BEGITU PADAMU", 420, 300),
            Triple("JANGAN BERGERAK!", 380, 240),
            Triple("HENTIKAN!", 300, 200)
        )
        for ((teks, w, h) in kasus) {
            val bmp = gambar(teks, w, h, "sementara.png")
            val (atas, bawah) = rentangTinta(bmp)
            assertTrue("tidak ada tinta untuk '$teks'", atas >= 0)
            val sisaAtas = atas
            val sisaBawah = h - 1 - bawah
            val selisih = kotlin.math.abs(sisaAtas - sisaBawah)
            assertTrue(
                "'$teks' pada ${w}x$h tidak terpusat tegak: " +
                    "atas=$sisaAtas bawah=$sisaBawah selisih=$selisih",
                selisih <= 4
            )
        }
    }

    @Test
    fun teksTidakPernahLuberKeluarBalon() {
        // Penjagaan sesungguhnya: apa pun penataannya, tinta tidak boleh
        // menyentuh tepi kotak. Kalau letterSpacing dipasang setelah
        // pengukuran, tes ini yang akan menangkapnya.
        val kalimat = listOf(
            "ORANG ITU SUDAH LAMA MENGHILANG TANPA JEJAK",
            "AKU MOHON DENGARKAN PENJELASANKU DULU SEBENTAR SAJA",
            "JANGAN BERGERAK!",
            "KAU PIKIR AKU AKAN MEMBIARKANMU PERGI BEGITU SAJA?",
            "APAKAH KAU BENAR-BENAR YAKIN DENGAN KEPUTUSAN INI?"
        )
        val ukuran = listOf(240 to 200, 320 to 260, 420 to 300, 500 to 380)
        for (teks in kalimat) {
            for ((w, h) in ukuran) {
                val bmp = gambar(teks, w, h, "sementara.png")
                // Sisi paling luar harus tetap bersih (garis tepi abu-abu
                // tidak dihitung: ambangnya jauh lebih gelap).
                for (x in 0 until w) {
                    for (y in intArrayOf(2, 3, h - 3, h - 4)) {
                        val p = bmp.getPixel(x, y)
                        val lum = (Color.red(p) * 77 + Color.green(p) * 151 + Color.blue(p) * 28) shr 8
                        assertTrue("luber di ($x,$y) untuk '$teks' pada ${w}x$h", lum >= 100)
                    }
                }
                for (y in 0 until h) {
                    for (x in intArrayOf(2, 3, w - 3, w - 4)) {
                        val p = bmp.getPixel(x, y)
                        val lum = (Color.red(p) * 77 + Color.green(p) * 151 + Color.blue(p) * 28) shr 8
                        assertTrue("luber di ($x,$y) untuk '$teks' pada ${w}x$h", lum >= 100)
                    }
                }
            }
        }
    }
}
