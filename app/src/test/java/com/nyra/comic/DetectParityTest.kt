package com.nyra.comic

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parity test for detection post-processing.
 *
 * The raw tensors in resources are the ACTUAL output of eyecypy.onnx run on
 * synthetic manga pages; the expected boxes were produced by the original
 * Python predict() using cv2.dnn.NMSBoxes. This pins decode + NMS behaviour.
 */
class DetectParityTest {

    private fun readMeta(): JSONArray {
        val s = javaClass.classLoader!!.getResourceAsStream("detect.json")
        return JSONArray(s.bufferedReader().readText())
    }

    private fun readRaw(name: String, rows: Int, stride: Int): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name).readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(rows * stride)
        for (i in out.indices) out[i] = bb.getFloat()
        return out
    }

    private fun fmt(l: List<IntArray>) = l.joinToString(" ") { it.joinToString(",") }

    @Test
    fun decodeMatchesPythonOnRealModelOutput() {
        val meta = readMeta()
        var checkedStages = 0
        var totalBoxes = 0

        for (i in 0 until meta.length()) {
            val m = meta.getJSONObject(i)
            val rows = m.getInt("rows")
            val stride = m.getInt("stride")
            val data = readRaw(m.getString("bin"), rows, stride)
            val w = m.getInt("W")
            val h = m.getInt("H")
            val ratio = m.getDouble("ratio").toFloat()
            val dw = m.getDouble("dw").toFloat()
            val dh = m.getDouble("dh").toFloat()

            val stages = m.getJSONArray("stages")
            for (s in 0 until stages.length()) {
                val st = stages.getJSONObject(s)
                val conf = st.getDouble("conf").toFloat()
                val iou = st.getDouble("iou").toFloat()

                val expArr = st.getJSONArray("boxes")
                val expected = (0 until expArr.length()).map { k ->
                    val b = expArr.getJSONArray(k)
                    intArrayOf(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
                }

                val got = DetectMath.decode(
                    data, rows, stride, conf, iou, ratio, ratio, dw, dh, w, h
                )

                assertEquals(
                    "case $i (${w}x$h) stage conf=$conf iou=$iou\n  kt =${fmt(got)}\n  py =${fmt(expected)}",
                    fmt(expected), fmt(got)
                )
                checkedStages++
                totalBoxes += expected.size
            }
        }
        println("detect parity: $checkedStages stages checked, $totalBoxes boxes matched")
        assertTrue("expected real detections in fixtures", totalBoxes > 0)
        assertEquals(15, checkedStages)
    }

    @Test
    fun iouIsSymmetricAndBounded() {
        val a = intArrayOf(10, 10, 100, 50)
        val b = intArrayOf(30, 20, 100, 50)
        val ab = DetectMath.iouXywh(a, b)
        assertEquals(ab, DetectMath.iouXywh(b, a), 1e-6f)
        assertTrue(ab in 0f..1f)
        assertEquals(1f, DetectMath.iouXywh(a, a), 1e-6f)
        assertEquals(0f, DetectMath.iouXywh(a, intArrayOf(500, 500, 10, 10)), 1e-6f)
    }
}
