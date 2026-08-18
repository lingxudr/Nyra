package com.nyra.comic

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Guards the output resolution of saved pages.
 *
 * The reported symptom was "gambar jadi burik" — translated pages came back
 * visibly worse than the source. Cause: decodeBitmap clamped the LONGEST side
 * to maxImageSide, so a 1080x11700 webtoon strip was decoded at 135x1462 and
 * 98% of the pixels were discarded before anything was drawn.
 *
 * The sizing rules are asserted through the pure sampleSizeFor() so the test
 * never has to allocate a multi-hundred-megabyte bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageResolutionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun decodedSize(w: Int, h: Int, maxSide: Int = 2200): Pair<Int, Int> {
        val s = Storage.sampleSizeFor(w, h, maxSide)
        return (w / s) to (h / s)
    }

    @Test
    fun tallStripKeepsItsFullWidth() {
        // The exact shape from the user's chapter.
        val (w, h) = decodedSize(1080, 8000)
        assertEquals("a webtoon strip must keep its native width", 1080, w)
        assertEquals(8000, h)
    }

    @Test
    fun tallStripIsNotDegradedBelowReadableWidth() {
        // Previously inSampleSize 8 -> 135px wide, which is what looked blurry.
        val (w, _) = decodedSize(1080, 11700)
        assertTrue("width collapsed to ${w}px — the blurry-output bug is back", w >= 540)
    }

    @Test
    fun theExactReportedPageIsNoLongerDownsampled() {
        assertEquals(1, Storage.sampleSizeFor(1080, 11700, 2200))
    }

    @Test
    fun wideOrNormalPageStillObeysMaxSide() {
        val (w, _) = decodedSize(4400, 3000)
        assertTrue("width $w should be capped at maxSide", w <= 2200)
    }

    @Test
    fun smallPageIsNeverTouched() {
        assertEquals(1, Storage.sampleSizeFor(900, 1119, 2200))
    }

    @Test
    fun absurdlyLargePageIsStillSubsampledToProtectMemory() {
        // The width rule must not let a page blow past the pixel ceiling.
        val (w, h) = decodedSize(3000, 30000)
        val px = w.toLong() * h
        assertTrue("decoded $px px exceeds the memory ceiling", px <= Storage.MAX_PAGE_PIXELS)
        assertTrue("height $h exceeds the strip ceiling", h <= Storage.MAX_STRIP_HEIGHT)
    }

    @Test
    fun realDecodeHonoursTheRuleOnDisk() {
        // One genuine end-to-end decode, at a size that is safe to allocate.
        val bmp = Bitmap.createBitmap(600, 2400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val f: File = tmp.newFile("strip.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val decoded = Storage.decodeBitmap(f, 2200)!!
        assertEquals("a 600x2400 strip must survive intact", 600, decoded.width)
        assertEquals(2400, decoded.height)
        decoded.recycle()
    }
}
