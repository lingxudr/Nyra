package com.nyra.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Regresi ronde 11 bug 3: dropSfxAndArt membuang balon yang sah.
 *
 * Semua aturan SFX mengandaikan balon itu PUTIH -- apa pun yang gelap dan
 * bertepi banyak dianggap efek suara. Pada halaman pengguna itu menghapus
 * balon hitam "I AM PELLIN", balon biru gelap, dan balon berduri ARGH! /
 * STOOOP!. Penyelamatnya adalah kerataan interior: balon berwarna apa pun
 * punya isi yang rata, artwork tidak pernah rata.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkBubbleSfxTest {

    private fun halaman(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        }

    private fun isiRata(bmp: Bitmap, b: IntArray, warna: Int) {
        val c = Canvas(bmp)
        c.drawRect(
            b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(),
            Paint().apply { color = warna }
        )
        // Teks di dalam balon: beberapa baris tebal, tetap menyisakan
        // interior yang sebagian besar rata.
        val teks = Paint().apply {
            color = if (warna == Color.BLACK) Color.WHITE else Color.BLACK
        }
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        var y = b[1] + bh / 4
        while (y < b[3] - bh / 5) {
            c.drawRect(
                (b[0] + bw * 0.18f), y.toFloat(),
                (b[2] - bw * 0.18f), (y + bh * 0.05f),
                teks
            )
            y += (bh * 0.16f).toInt().coerceAtLeast(1)
        }
    }

    /**
     * Artwork padat: seluruh kotak ditutup petak 6x6 hitam/putih acak, jadi
     * tidak ada sisa latar putih yang bisa meloloskannya lewat whiteSafe dan
     * tidak ada interior yang rata.
     */
    private fun isiBerisik(bmp: Bitmap, b: IntArray) {
        val c = Canvas(bmp)
        val r = Random(7)
        val p = Paint()
        val petak = 6
        var y = b[1]
        while (y < b[3]) {
            var x = b[0]
            while (x < b[2]) {
                p.color = if (r.nextBoolean()) Color.BLACK else Color.rgb(90, 90, 90)
                c.drawRect(
                    x.toFloat(), y.toFloat(),
                    minOf(x + petak, b[2]).toFloat(), minOf(y + petak, b[3]).toFloat(), p
                )
                x += petak
            }
            y += petak
        }
    }

    @Test
    fun balonHitamDanBiruGelapTidakDibuangSebagaiSfx() {
        val bmp = halaman(1080, 1920)

        val hitam = intArrayOf(108, 552, 813, 1152)
        val biruGelap = intArrayOf(230, 1204, 1057, 1890)
        isiRata(bmp, hitam, Color.BLACK)
        isiRata(bmp, biruGelap, Color.rgb(18, 26, 62))

        for (mode in listOf("relaxed", "balanced", "strict")) {
            val hidup = BoxUtils.dropSfxAndArt(bmp, listOf(hitam, biruGelap), mode)
            assertTrue(
                "balon hitam hilang pada mode $mode",
                hidup.any { it.contentEquals(hitam) }
            )
            assertTrue(
                "balon biru gelap hilang pada mode $mode",
                hidup.any { it.contentEquals(biruGelap) }
            )
        }
        bmp.recycle()
    }

    @Test
    fun artworkBerisikTetapDibuang() {
        val bmp = halaman(1080, 1920)
        val artwork = intArrayOf(60, 200, 1000, 900)
        isiBerisik(bmp, artwork)

        val hidup = BoxUtils.dropSfxAndArt(bmp, listOf(artwork), "balanced")
        assertTrue(
            "artwork berisik seharusnya tetap dibuang, bukan diselamatkan kerataan",
            hidup.isEmpty()
        )
        bmp.recycle()
    }
}
