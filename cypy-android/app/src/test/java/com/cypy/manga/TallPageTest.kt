package com.cypy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the 59-page webtoon that produced only 40 bubbles and a
 * blurry output.
 *
 * Two independent defects are covered here:
 *
 *  1. Detection. The model letterboxes its input into 640x640, so a
 *     1080x11700 strip arrives as roughly 59x640 and every bubble collapses
 *     into a couple of pixels. Measured against the real eyecypy.onnx on a
 *     strip containing 15 bubbles: whole-page detection returned 0, windowed
 *     detection returned 15 with no false positives.
 *
 *  2. Resolution. decodeBitmap used to clamp the LONGEST side, so the same
 *     strip was decoded at 135x1462 — a 98% pixel loss. That is what made the
 *     saved pages look washed out and pixelated.
 */
class TallPageTest {

    // ---- windowing ----

    @Test
    fun normalPageIsNotTiled() {
        // A regular manga page must keep the exact previous behaviour.
        val w = DetectMath.tileWindows(900, 1119)
        assertEquals(1, w.size)
        assertEquals(0, w[0][0])
        assertEquals(1119, w[0][1])
    }

    @Test
    fun mildlyTallPageIsNotTiled() {
        // 1080x2340 (a phone screenshot) is ratio 2.17 — just under the trigger.
        assertEquals(1, DetectMath.tileWindows(1080, 2340).size)
    }

    /**
     * Regresi: halaman yang baru melewati ambang pemicu harus benar-benar
     * terpotong. Dulu TILE_WINDOW_RATIO == TILE_TRIGGER_RATIO == 2.2, sehingga
     * halaman 1080x2400 (rasio 2.22) membuat SATU jendela 2376 px untuk
     * halaman 2400 px: menyala tapi tidak memotong apa pun.
     */
    @Test
    fun pageJustOverTheTriggerIsActuallySplit() {
        val windows = DetectMath.tileWindows(1080, 2400)
        assertTrue(
            "halaman rasio 2.22 harus dipotong >1 jendela, dapat ${windows.size}",
            windows.size >= 2
        )
        for (win in windows) {
            assertTrue(
                "jendela tidak boleh setinggi hampir seluruh halaman: ${win[1] - win[0]}",
                win[1] - win[0] < 2400 * 0.90
            )
        }
    }

    /** Kalau jendela >= pemicu, selalu ada pita rasio yang penjendelaannya sia-sia. */
    @Test
    fun windowIsShorterThanTheTriggerItself() {
        assertTrue(
            "TILE_WINDOW_RATIO (${DetectMath.TILE_WINDOW_RATIO}) harus < " +
                "TILE_TRIGGER_RATIO (${DetectMath.TILE_TRIGGER_RATIO})",
            DetectMath.TILE_WINDOW_RATIO < DetectMath.TILE_TRIGGER_RATIO
        )
    }

    /**
     * dropFakeGiants membandingkan luas ANTAR kotak, jadi hanya sah dipakai di
     * dalam satu jendela. Kalau dijalankan setelah semua jendela digabung,
     * balon besar di satu jendela menelan balon kecil yang sah di jendela lain
     * -- pada halaman pengguna itu memangkas 6 balon jadi 1.
     */
    @Test
    fun aBigBubbleDoesNotSwallowLegitBubblesFromOtherWindows() {
        val besar = intArrayOf(100, 100, 900, 900)
        val kecil = intArrayOf(200, 1500, 400, 1650)
        val hasil = DetectMath.dedupeTiled(listOf(besar, kecil))
        assertEquals("kedua balon harus bertahan", 2, hasil.size)
    }

    @Test
    fun webtoonStripIsSplitIntoOverlappingWindows() {
        val h = 11700
        val windows = DetectMath.tileWindows(1080, h)
        assertTrue("a tall strip must be windowed, got ${windows.size}", windows.size > 4)

        // Every window is a sane slice.
        for (win in windows) {
            assertTrue(win[0] >= 0)
            assertTrue(win[1] <= h)
            assertTrue(win[1] > win[0])
        }
        // Consecutive windows overlap, so a bubble on a seam is seen whole.
        for (i in 1 until windows.size) {
            assertTrue(
                "windows ${i - 1} and $i must overlap",
                windows[i][0] < windows[i - 1][1]
            )
        }
        // The whole page is covered, with nothing left out at the bottom.
        assertEquals(0, windows.first()[0])
        assertEquals(h, windows.last()[1])
    }

    @Test
    fun windowsAreNotSoTallThatBubblesVanishAgain() {
        // The point of the fix: each window must stay near a page-like ratio.
        val width = 1080
        for (win in DetectMath.tileWindows(width, 11700)) {
            val ratio = (win[1] - win[0]).toFloat() / width
            assertTrue("window ratio $ratio is still too tall", ratio <= 2.6f)
        }
    }

    // ---- de-duplication across windows ----

    @Test
    fun duplicateDetectionsFromOverlapAreCollapsed() {
        // The same bubble seen by two neighbouring windows, plus a distinct one.
        val boxes = listOf(
            intArrayOf(100, 500, 400, 800),
            intArrayOf(102, 498, 398, 802),   // same bubble, 1px jitter
            intArrayOf(100, 5000, 400, 5300)  // a genuinely different bubble
        )
        assertEquals(2, DetectMath.dedupeTiled(boxes).size)
    }

    @Test
    fun clippedSeamCopyIsCollapsedIntoTheWholeBubble() {
        // A window boundary cuts a bubble: one copy is the full bubble, the
        // other is the visible sliver. They must not both survive.
        val whole = intArrayOf(100, 1000, 500, 1400)
        val sliver = intArrayOf(100, 1000, 500, 1330) // 82% contained
        assertEquals(1, DetectMath.dedupeTiled(listOf(whole, sliver)).size)
    }

    @Test
    fun degenerateBoxesAreDropped() {
        // Tiling used to emit boxes with negative width after coordinate
        // mapping; those must never reach the crop stage.
        val boxes = listOf(
            intArrayOf(0, 952, -3, 1620),  // negative width
            intArrayOf(10, 10, 11, 11),    // 1px
            intArrayOf(100, 200, 400, 500) // real
        )
        val kept = DetectMath.dedupeTiled(boxes)
        assertEquals(1, kept.size)
        assertTrue(kept[0][2] - kept[0][0] > 1)
    }

    @Test
    fun outputKeepsTopToBottomReadingOrder() {
        // Windows are processed in order but dedupe sorts by size, so the
        // result has to be re-sorted or the bubble numbering scrambles.
        val boxes = listOf(
            intArrayOf(100, 9000, 900, 9900), // big, low down
            intArrayOf(100, 100, 300, 300),   // small, at the top
            intArrayOf(100, 4000, 500, 4400)
        )
        val ys = DetectMath.dedupeTiled(boxes).map { it[1] }
        assertEquals(listOf(100, 4000, 9000), ys)
    }

    @Test
    fun separateBubblesAreNotMerged() {
        // Two bubbles that merely touch must both survive.
        val a = intArrayOf(100, 1000, 400, 1300)
        val b = intArrayOf(410, 1000, 700, 1300)
        assertEquals(2, DetectMath.dedupeTiled(listOf(a, b)).size)
    }

    // ---- shape filter must not eat bubbles on a strip ----

    /**
     * The third defect behind the 40-bubble chapter. dropAbsurd rejects "flat
     * banners" using h <= 16% of the page height. On an 11700px strip that
     * threshold is 1872px, so normal bubbles looked flat and were discarded:
     * 15 correctly detected bubbles became 10.
     */
    @Test
    fun ordinaryBubblesSurviveTheShapeFilterOnATallStrip() {
        // Real detections from the reported chapter (1080x11700).
        val boxes = listOf(
            intArrayOf(100, 235, 611, 668),
            intArrayOf(528, 604, 1054, 1007),
            intArrayOf(192, 1307, 886, 1870),   // used to be dropped
            intArrayOf(100, 2576, 610, 2998),
            intArrayOf(530, 2941, 1056, 3346),
            intArrayOf(192, 3647, 888, 4206),   // used to be dropped
            intArrayOf(99, 4916, 611, 5332),
            intArrayOf(533, 5284, 1055, 5679),
            intArrayOf(191, 5986, 882, 6550),   // used to be dropped
            intArrayOf(99, 7256, 609, 7685),
            intArrayOf(534, 7621, 1053, 8030),
            intArrayOf(192, 8329, 889, 8885),   // used to be dropped
            intArrayOf(100, 9592, 611, 10021),
            intArrayOf(528, 9964, 1056, 10362),
            intArrayOf(190, 10662, 888, 11230)  // used to be dropped
        )
        val kept = BoxUtils.dropAbsurd(boxes, 1080, 11700)
        assertEquals("the shape filter is eating real bubbles again", 15, kept.size)
    }

    @Test
    fun genuineWideBannerIsStillRejectedOnAStrip() {
        // The filter must keep doing its job: a page-wide flat band is not a bubble.
        val banner = listOf(intArrayOf(0, 100, 1000, 220))   // 1000x120, ratio 8.3
        assertEquals(0, BoxUtils.dropAbsurd(banner, 1080, 11700).size)
    }

    @Test
    fun shapeFilterIsUntouchedForMerelyTallPages() {
        // 800x1800 (ratio 2.25) appears in the Python oracle. Such a page must
        // keep the reference arithmetic exactly, or BoxParityTest diverges.
        val flat = listOf(intArrayOf(200, 700, 700, 900))  // w=500 (62%W), h=200 (11%H)
        assertEquals("a tall page must still use Python's own rule", 0,
            BoxUtils.dropAbsurd(flat, 800, 1800).size)
    }

    @Test
    fun shapeFilterIsUnchangedForNormalPages() {
        // Same banner on an ordinary page: still rejected, exactly as before.
        val banner = listOf(intArrayOf(0, 100, 1000, 220))
        assertEquals(0, BoxUtils.dropAbsurd(banner, 1080, 1500).size)
        // And an ordinary bubble on an ordinary page still passes.
        val bubble = listOf(intArrayOf(100, 200, 500, 600))
        assertEquals(1, BoxUtils.dropAbsurd(bubble, 1080, 1500).size)
    }
}
