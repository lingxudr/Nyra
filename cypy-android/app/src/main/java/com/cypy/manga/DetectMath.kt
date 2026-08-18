package com.cypy.manga

import kotlin.math.max
import kotlin.math.min

/**
 * Pure detection post-processing, kept free of Android/ONNX types so it can be
 * verified against the original Python (cypy/core/yolo_onnx.py predict()) in
 * plain JVM unit tests.
 */
object DetectMath {

    /**
     * Decodes YOLOv8 raw output rows (xc, yc, w, h, conf) into xyxy boxes.
     * Mirrors the reference implementation including its int truncation order.
     */
    fun decode(
        data: FloatArray, numRows: Int, stride: Int,
        conf: Float, iouThr: Float,
        ratioX: Float, ratioY: Float, dw: Float, dh: Float,
        origW: Int, origH: Int
    ): List<IntArray> {
        val boxes = ArrayList<IntArray>()   // xywh, int-truncated like Python
        val scores = ArrayList<Float>()

        for (i in 0 until numRows) {
            val base = i * stride
            val score = data[base + 4]
            if (score < conf) continue

            val xc = data[base]
            val yc = data[base + 1]
            val w = data[base + 2]
            val h = data[base + 3]

            val x1 = xc - w / 2f
            val y1 = yc - h / 2f

            val x1s = (x1 - dw) / ratioX
            val y1s = (y1 - dh) / ratioY
            val ws = w / ratioX
            val hs = h / ratioY

            boxes.add(intArrayOf(trunc(x1s), trunc(y1s), trunc(ws), trunc(hs)))
            scores.add(score)
        }

        val keep = nmsBoxes(boxes, scores, conf, iouThr)
        val out = ArrayList<IntArray>(keep.size)
        for (idx in keep) {
            val b = boxes[idx]
            val x1 = max(0, b[0])
            val y1 = max(0, b[1])
            val x2 = min(origW, b[0] + b[2])
            val y2 = min(origH, b[1] + b[3])
            out.add(intArrayOf(x1, y1, x2, y2))
        }
        return out
    }

    /** Python int() truncates toward zero; Kotlin toInt() does the same. */
    private fun trunc(v: Float): Int = v.toInt()

    /**
     * Equivalent of cv2.dnn.NMSBoxes: filter by score, sort desc, greedily keep
     * and suppress any remaining box whose IoU with the kept one exceeds iouThr.
     */
    fun nmsBoxes(
        boxes: List<IntArray>, scores: List<Float>,
        scoreThr: Float, iouThr: Float
    ): List<Int> {
        // Stable sort by descending score, matching OpenCV's ordering for ties.
        val order = boxes.indices
            .filter { scores[it] >= scoreThr }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .toMutableList()

        val keep = ArrayList<Int>()
        while (order.isNotEmpty()) {
            val best = order.removeAt(0)
            keep.add(best)
            val a = boxes[best]
            val iter = order.iterator()
            while (iter.hasNext()) {
                val b = boxes[iter.next()]
                if (iouXywh(a, b) > iouThr) iter.remove()
            }
        }
        return keep
    }

    /** IoU on xywh boxes, using OpenCV's rectangle intersection semantics. */
    fun iouXywh(a: IntArray, b: IntArray): Float {
        val ax2 = a[0] + a[2]
        val ay2 = a[1] + a[3]
        val bx2 = b[0] + b[2]
        val by2 = b[1] + b[3]

        val ix = min(ax2, bx2) - max(a[0], b[0])
        val iy = min(ay2, by2) - max(a[1], b[1])
        if (ix <= 0 || iy <= 0) return 0f

        val inter = ix.toFloat() * iy.toFloat()
        val areaA = a[2].toFloat() * a[3].toFloat()
        val areaB = b[2].toFloat() * b[3].toFloat()
        val union = areaA + areaB - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    // ------------------------------------------------------------------
    // Tall-page (webtoon) tiling
    // ------------------------------------------------------------------

    /**
     * Aspect ratio above which a page is treated as a vertical strip and must
     * be detected in windows. YOLO letterboxes its input to 640x640, so a
     * 1080x11700 strip is squashed to 59x640 and every bubble becomes a few
     * pixels tall — the model then reports nothing at all. Measured against
     * the real eyecypy.onnx on a 15-bubble strip: whole-page detection found
     * 0/15, windowed detection at these settings found 15/15 with 0 false
     * positives.
     */
    const val TILE_TRIGGER_RATIO = 2.2f

    /** Window height as a multiple of page width. */
    /**
     * HARUS di bawah TILE_TRIGGER_RATIO. Saat keduanya 2.2, halaman 1080x2400
     * (rasio 2.22) memicu penjendelaan lalu membuat SATU jendela 2376 px untuk
     * halaman 2400 px: menyala tapi tidak memotong apa pun.
     */
    const val TILE_WINDOW_RATIO = 1.5f

    /** Overlap between consecutive windows, so bubbles on a seam survive. */
    const val TILE_OVERLAP = 0.35f

    /**
     * Ratio above which a page stops being a page and is a vertical strip, for
     * the purposes of the SHAPE filter (BoxUtils.dropAbsurd).
     *
     * Deliberately much higher than TILE_TRIGGER_RATIO: tiling is about
     * detection quality and is safe to apply early, whereas the shape filter
     * must stay bit-identical to the Python reference for anything that is
     * merely a tall page (the oracle contains e.g. an 800x1800 page at 2.25).
     * Real webtoon strips sit around 8-15.
     */
    const val STRIP_SHAPE_RATIO = 4.0f

    /** Vertical windows (yStart, yEnd) covering a page of the given size. */
    fun tileWindows(width: Int, height: Int): List<IntArray> {
        if (width <= 0 || height <= 0) return emptyList()
        if (height <= width * TILE_TRIGGER_RATIO) return listOf(intArrayOf(0, height))

        val win = max(1, (width * TILE_WINDOW_RATIO).toInt())
        val step = max(1, (win * (1f - TILE_OVERLAP)).toInt())
        val out = ArrayList<IntArray>()
        var y = 0
        while (true) {
            val y2 = min(height, y + win)
            if (y2 - y > 40) out.add(intArrayOf(y, y2))
            if (y2 >= height) break
            y += step
        }
        return out
    }

    /**
     * Removes boxes that the overlapping windows found twice. Two detections
     * are the same bubble when they overlap a lot, or when one is essentially
     * contained in the other (a seam often yields one clipped copy).
     */
    fun dedupeTiled(boxes: List<IntArray>): List<IntArray> {
        val valid = boxes.filter { it[2] - it[0] > 1 && it[3] - it[1] > 1 }
        val sorted = valid.sortedByDescending { areaOf(it) }
        val keep = ArrayList<IntArray>()
        for (b in sorted) {
            var dup = false
            for (k in keep) {
                val it0 = interOf(b, k)
                if (it0 == 0) continue
                val union = areaOf(b) + areaOf(k) - it0
                val iou = if (union > 0) it0.toFloat() / union else 0f
                val smaller = min(areaOf(b), areaOf(k))
                if (iou >= 0.35f || (smaller > 0 && it0 >= 0.75f * smaller)) { dup = true; break }
            }
            if (!dup) keep.add(b)
        }
        // Restore top-to-bottom reading order: numbering must follow the page.
        return keep.sortedWith(compareBy({ it[1] }, { it[0] }))
    }

    private fun areaOf(b: IntArray) = max(0, b[2] - b[0]) * max(0, b[3] - b[1])

    private fun interOf(a: IntArray, b: IntArray): Int {
        val x = min(a[2], b[2]) - max(a[0], b[0])
        val y = min(a[3], b[3]) - max(a[1], b[1])
        return if (x <= 0 || y <= 0) 0 else x * y
    }
}
