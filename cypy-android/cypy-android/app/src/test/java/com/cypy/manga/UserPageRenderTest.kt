package com.cypy.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
 * Render the user's actual page with the actual translation Gemini returned,
 * then write the PNG out so it can be inspected visually.
 *
 * GraphicsMode.NATIVE is essential: under Robolectric's default LEGACY mode
 * Canvas drawing commands are silently no-ops, so a render bug would be
 * invisible to the test while still producing a blank page on a real device.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [33], manifest = RoboConfig.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserPageRenderTest {

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name).readBytes()

    @Test
    fun rendersRealTranslationOntoUserPage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

        val bytes = resource("user_page.jpg")
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)!!
        assertTrue("bitmap harus mutable", bmp.isMutable)

        // Kotak balon dari eyecypy.onnx sungguhan pada halaman ini (conf 0.90).
        val box = intArrayOf(0, 89, 282, 409)

        // Teks yang benar-benar dikembalikan gemini-3.7-flash untuk halaman ini.
        val text = "AKU ADALAH MERLIN LUCIFER, RAJA IBLIS PERTAMA, PENGUASA KESOMBONGAN..."

        val before = countDarkInside(bmp, box)

        val renderer = TextRenderer(ctx)
        val canvas = Canvas(bmp)
        renderer.drawInBubble(
            canvas, bmp, text, box[0], box[1], box[2], box[3],
            backgroundPatch = false,
            targetLanguage = "indonesian",
            maskMarginRatio = 0.12f
        )

        val after = countDarkInside(bmp, box)

        val outDir = File(System.getProperty("user.dir"), "build/render-out").apply { mkdirs() }
        val out = File(outDir, "user_page_rendered.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENDER OUT: ${out.absolutePath}")
        println("dark pixels sebelum=$before sesudah=$after")

        assertTrue(
            "teks terjemahan tidak tergambar (sebelum=$before sesudah=$after)",
            after > 200
        )
    }

    private fun countDarkInside(bmp: Bitmap, box: IntArray): Int {
        var n = 0
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if ((r * 299 + g * 587 + b * 114) / 1000 <= 79) n++
            }
        }
        return n
    }
}
