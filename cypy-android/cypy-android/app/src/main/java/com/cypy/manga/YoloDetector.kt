package com.cypy.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of cypy/core/yolo_onnx.py — YOLOv8 speech-bubble detector running
 * on-device via ONNX Runtime. Output layout is [1, 5, 8400] -> transposed
 * to rows of (xc, yc, w, h, conf).
 */
class YoloDetector(ctx: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputSize = 640

    init {
        val modelBytes = ctx.assets.open("eyecypy.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
        inputName = session.inputNames.iterator().next()
    }

    data class Letterbox(val bitmap: Bitmap, val ratio: Float, val dw: Float, val dh: Float)

    private fun letterbox(src: Bitmap): Letterbox {
        val r = min(inputSize.toFloat() / src.height, inputSize.toFloat() / src.width)
        val newW = (src.width * r).roundToInt()
        val newH = (src.height * r).roundToInt()
        val dw = (inputSize - newW) / 2f
        val dh = (inputSize - newH) / 2f

        val out = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val resized = Bitmap.createScaledBitmap(src, max(1, newW), max(1, newH), true)
        canvas.drawBitmap(resized, dw, dh, Paint(Paint.FILTER_BITMAP_FLAG))
        if (resized !== src) resized.recycle()
        return Letterbox(out, r, dw, dh)
    }

    /**
     * Runs the 3-stage prediction sweep used by translator.py and returns
     * raw boxes (all stages concatenated, filtering happens downstream).
     */
    fun predictStages(src: Bitmap): List<IntArray> {
        val lb = letterbox(src)
        val raw = runInference(lb.bitmap)
        lb.bitmap.recycle()

        val stages = listOf(
            0.28f to 0.45f,
            0.18f to 0.55f,
            0.10f to 0.65f
        )
        val all = ArrayList<IntArray>()
        for ((conf, iou) in stages) {
            all.addAll(decode(raw, conf, iou, lb, src.width, src.height))
        }
        return all
    }

    private class RawOutput(val data: FloatArray, val numRows: Int, val stride: Int)

    private fun runInference(input: Bitmap): RawOutput {
        val pixels = IntArray(inputSize * inputSize)
        input.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val chw = FloatArray(3 * inputSize * inputSize)
        val plane = inputSize * inputSize
        for (i in 0 until plane) {
            val p = pixels[i]
            chw[i] = ((p shr 16) and 0xFF) / 255f          // R
            chw[plane + i] = ((p shr 8) and 0xFF) / 255f   // G
            chw[2 * plane + i] = (p and 0xFF) / 255f       // B
        }

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<FloatArray>> // [1][5][8400]
                val attrs = out[0]
                val numRows = attrs[0].size
                val stride = attrs.size
                val flat = FloatArray(numRows * stride)
                for (a in 0 until stride) {
                    val row = attrs[a]
                    for (i in 0 until numRows) flat[i * stride + a] = row[i]
                }
                return RawOutput(flat, numRows, stride)
            }
        }
    }

    private fun decode(
        raw: RawOutput, conf: Float, iouThr: Float,
        lb: Letterbox, origW: Int, origH: Int
    ): List<IntArray> = DetectMath.decode(
        raw.data, raw.numRows, raw.stride, conf, iouThr,
        lb.ratio, lb.ratio, lb.dw, lb.dh, origW, origH
    )

    override fun close() {
        runCatching { session.close() }
    }
}
